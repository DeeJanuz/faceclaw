package com.faceclaw.app;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Retained-surface compositor: the phone-side model of what is on the glasses
 * screen.
 *
 * Each surface is an 8bpp grayscale buffer retained at its last-submitted
 * contents, positioned on the screen with a z-order and a transparency mode.
 * Sources on the TS side (today the dashboard; later the shell and per-app
 * worker threads) submit updates to their surface, and the compositor
 * recombines the retained surfaces into a full-screen frame that feeds the
 * existing dedupe/compress/transmit pipeline. Because every surface is
 * retained, a full screen frame can be regenerated at any time (frame drops,
 * reconnects, previews, screenshots) without asking sources to repaint.
 *
 * Surface format contract (mirrored by the TS side):
 * - Pixels are 8bpp grayscale, row-major, one byte per pixel. The wire
 *   pipeline quantizes to 4bpp downstream, so the low nibble is not visible.
 * - TRANSPARENCY_COLOR_KEY surfaces treat pixel value 0 as fully transparent
 *   and value 1 as black. Quantization collapses 1 into the same 4bpp level
 *   as 0, so reserving 0 costs no visible shade; painters of color-key
 *   surfaces must clamp intentional black to 1.
 * - An update may cover any rect of its surface; the rest is retained. The
 *   update buffer holds exactly rectWidth*rectHeight bytes, row-major.
 * - Surfaces composite in ascending z-order onto a black (0) background.
 * - Surface geometry changes take effect when the next frame composites;
 *   they do not trigger a recomposite by themselves.
 *
 * Thread safety: all methods are safe to call from any thread. Apply and
 * composite happen atomically under an internal lock, and each composite
 * carries a monotonic sequence number so callers can detect when a composite
 * was superseded by a concurrent one before being acted on.
 */
public final class SurfaceCompositor {
    public static final int TRANSPARENCY_OPAQUE = 0;
    public static final int TRANSPARENCY_COLOR_KEY = 1;
    public static final int TILE_WIDTH = 32;
    public static final int TILE_HEIGHT = 16;

    /**
     * One deferred draw (a text glyph or an icon image) within a frame, in
     * screen coordinates. The draw's pixels are already baked into the
     * composited gray buffer (the TS side bakes before submitting); this
     * record preserves the draw's identity so the texture-cache planner can
     * replay it as an on-glasses cached draw instead of image bytes.
     *
     * Glyphs: x/y are the pen position and line top; the raster (and its
     * bearing/cell placement) comes from GlyphAtlas under (fontId, encoding).
     * Images: x/y are the blit's top-left; the raster comes from ImageAtlas
     * under imageId.
     */
    public static final class ScreenDraw {
        public static final int KIND_GLYPH = 0;
        public static final int KIND_IMAGE = 1;
        /** A firmware-builtin-font text run (CFW mode 15). */
        public static final int KIND_FWTEXT = 2;

        public final int kind;
        /** Glyph draws only. */
        public final int fontId;
        /** Glyph draws only. */
        public final int encoding;
        /** Image draws only. */
        public final int imageId;
        public final int x;
        public final int y;
        /** Glyph and fw-text draws: 8-bit brightness. */
        public final int value;
        /** Fw-text runs only: member codepoints, in text order. */
        public final int[] fwCps;
        /** Fw-text runs only: member pen offsets relative to x. */
        public final int[] fwDx;
        /** Fw-text runs only: whether each member has visible pixels. */
        public final boolean[] fwInk;

        ScreenDraw(int kind, int fontId, int encoding, int imageId, int x, int y, int value,
                   int[] fwCps, int[] fwDx, boolean[] fwInk) {
            this.kind = kind;
            this.fontId = fontId;
            this.encoding = encoding;
            this.imageId = imageId;
            this.x = x;
            this.y = y;
            this.value = value;
            this.fwCps = fwCps;
            this.fwDx = fwDx;
            this.fwInk = fwInk;
        }

        static ScreenDraw glyph(int fontId, int encoding, int penX, int lineY, int value) {
            return new ScreenDraw(KIND_GLYPH, fontId, encoding, 0, penX, lineY, value, null, null, null);
        }

        static ScreenDraw image(int imageId, int x, int y) {
            return new ScreenDraw(KIND_IMAGE, 0, 0, imageId, x, y, 0, null, null, null);
        }

        static ScreenDraw fwText(int x, int y, int value, int[] cps, int[] dx, boolean[] ink) {
            return new ScreenDraw(KIND_FWTEXT, 0, 0, 0, x, y, value, cps, dx, ink);
        }
    }

    private static final ScreenDraw[] NO_DRAWS = new ScreenDraw[0];

    /** One composited full-screen frame plus the metadata the pipeline needs. */
    public static final class Composite {
        /**
         * Full-screen 8bpp grayscale pixels, screenWidth*screenHeight bytes.
         * Null for the allocation-free packed intake path; preview and
         * screenshot consumers request their own retained snapshot.
         */
        public final byte[] gray;
        public final int width;
        public final int height;
        /**
         * Stable identifier of screen content, combining every surface's
         * geometry and content fingerprint; equal fingerprints mean equal
         * composited pixels.
         */
        public final String fingerprint;
        /** Monotonic: a Composite with a higher seq contains strictly newer state. */
        public final long seq;
        /**
         * Screen-space deferred draws of every visible surface, in composite
         * order (surface z ascending, then each surface's draw order). Their
         * pixels are already baked into gray.
         */
        public final ScreenDraw[] draws;
        /** Bounding screen-space damage produced by this composition. */
        public final int[] damage;

        Composite(byte[] gray, int width, int height, String fingerprint, long seq, ScreenDraw[] draws, int[] damage) {
            this.gray = gray;
            this.width = width;
            this.height = height;
            this.fingerprint = fingerprint;
            this.seq = seq;
            this.draws = draws == null ? NO_DRAWS : draws;
            this.damage = damage == null ? new int[0] : damage;
        }
    }

    /** Packed result produced while retained Gray8 is protected by the compositor lock. */
    public static final class PackedComposite {
        public final Composite composite;
        public final byte[] packed;
        PackedComposite(Composite composite, byte[] packed) { this.composite = composite; this.packed = packed; }
    }

    private static final class Surface {
        final String id;
        int x;
        int y;
        int width;
        int height;
        int zOrder;
        int transparency;
        boolean visible = true;
        byte[] pixels;
        String fingerprint = "";
        /** Surface-local deferred draws of the retained content (already baked into pixels). */
        ScreenDraw[] draws = NO_DRAWS;

        Surface(String id) {
            this.id = id;
        }
    }

    private final Object lock = new Object();
    private int screenWidth;
    private int screenHeight;
    private boolean blanked;
    /** See setUnderlayDim: surfaces with zOrder below this are dimmed by underlayDim/256. */
    private int underlayDimBelowZOrder = Integer.MIN_VALUE;
    private int underlayDim = 256;
    private final Map<String, Surface> surfaces = new HashMap<>();
    private long nextCompositeSeq = 1;
    private byte[] retainedGray;
    /** Canonical packed framebuffer, patched in place from the same dirty tiles. */
    private byte[] retainedPacked;
    private boolean retainedPackedValid;
    private boolean[] dirtyTiles;
    private int tileColumns;
    private boolean retainedValid;
    private final boolean compareDirtyWithFull = Boolean.getBoolean("faceclaw.compositor.compareDirty");

    public int packedFrameSize() {
        synchronized (lock) { requireScreenConfiguredLocked(); return ((screenWidth + 1) >> 1) * screenHeight; }
    }

    /** Set the output frame size. Must be called before any surface work. */
    public void configureScreen(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("bad screen size " + width + "x" + height);
        }
        synchronized (lock) {
            boolean changed = this.screenWidth != width || this.screenHeight != height;
            this.screenWidth = width;
            this.screenHeight = height;
            if (changed || retainedGray == null) {
                retainedGray = new byte[width * height];
                retainedPacked = new byte[((width + 1) >> 1) * height];
                retainedPackedValid = false;
                tileColumns = (width + TILE_WIDTH - 1) / TILE_WIDTH;
                dirtyTiles = new boolean[tileColumns * ((height + TILE_HEIGHT - 1) / TILE_HEIGHT)];
                retainedValid = false;
                markAllDirtyLocked();
            }
        }
    }

    /**
     * Create a surface or update an existing one's geometry. A resize discards
     * the surface's retained pixels (reset to 0).
     */
    public void configureSurface(String id, int x, int y, int width, int height, int zOrder, int transparency) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("surface id must be non-empty");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("bad surface size " + width + "x" + height + " for " + id);
        }
        if (transparency != TRANSPARENCY_OPAQUE && transparency != TRANSPARENCY_COLOR_KEY) {
            throw new IllegalArgumentException("bad transparency mode " + transparency + " for " + id);
        }
        synchronized (lock) {
            requireScreenConfiguredLocked();
            Surface surface = surfaces.get(id);
            if (surface == null) {
                surface = new Surface(id);
                surfaces.put(id, surface);
            } else if (surface.visible) {
                markScreenRectDirtyLocked(surface.x, surface.y, surface.width, surface.height);
            }
            if (surface.pixels == null || surface.width != width || surface.height != height) {
                surface.pixels = new byte[width * height];
                surface.fingerprint = "";
                surface.draws = NO_DRAWS;
            }
            surface.x = x;
            surface.y = y;
            surface.width = width;
            surface.height = height;
            surface.zOrder = zOrder;
            surface.transparency = transparency;
            if (surface.visible) markScreenRectDirtyLocked(x, y, width, height);
        }
    }

    public void removeSurface(String id) {
        synchronized (lock) {
            Surface removed = surfaces.remove(id);
            if (removed != null && removed.visible) markScreenRectDirtyLocked(removed.x, removed.y, removed.width, removed.height);
        }
    }

    /**
     * Dim every surface whose zOrder is below belowZOrder to factor256/256 of
     * its brightness (256 = no dimming): how a shell overlay that dims what it
     * covers (the TS Layer.dimUnderneath) reaches the window surfaces beneath
     * the shell surface, which the shell's own layer stack cannot paint.
     * A zero factor excludes underlay surfaces and their deferred draws from
     * the composite. With a positive factor, visible pixels stay at least 1
     * (the color-key black); glyph and
     * firmware-text draws keep their cached-draw form with a dimmed value;
     * image draws leave the composite's draw list (their pixels, already
     * baked into the surface, dim as raster). Takes effect when the next
     * frame composites.
     */
    public void setUnderlayDim(int belowZOrder, int factor256) {
        synchronized (lock) {
            int next = Math.max(0, Math.min(256, factor256));
            if (underlayDimBelowZOrder != belowZOrder || underlayDim != next) markAllDirtyLocked();
            underlayDimBelowZOrder = belowZOrder;
            underlayDim = next;
        }
    }

    /** The dim factor (256 = none) that applies to a surface. */
    private int dimForLocked(Surface surface) {
        return surface.zOrder < underlayDimBelowZOrder ? underlayDim : 256;
    }

    private static int dimValue(int value, int dim) {
        return Math.max(1, (value * dim + 128) >> 8);
    }

    /**
     * Hidden surfaces keep their retained pixels and accept updates, but are
     * excluded from the composite and its fingerprint (used for background
     * windows). Takes effect when the next frame composites.
     */
    public void setSurfaceVisible(String id, boolean visible) {
        synchronized (lock) {
            Surface surface = surfaces.get(id);
            if (surface == null) {
                throw new IllegalArgumentException("unknown surface " + id);
            }
            if (surface.visible != visible) markScreenRectDirtyLocked(surface.x, surface.y, surface.width, surface.height);
            surface.visible = visible;
        }
    }

    /**
     * While blanked (screen off), composites are all-zero regardless of
     * surface content; retained state is untouched, so unblanking restores
     * the screen without asking sources to repaint.
     */
    public void setBlanked(boolean blanked) {
        synchronized (lock) {
            if (this.blanked != blanked) markAllDirtyLocked();
            this.blanked = blanked;
        }
    }

    /**
     * Apply an update to one surface and composite the whole screen, as one
     * atomic step. The update covers the rect (rectX, rectY, rectWidth,
     * rectHeight) in surface-local coordinates; pixels must hold exactly
     * rectWidth*rectHeight bytes. contentFingerprint identifies the surface's
     * full content after this update.
     */
    public Composite applyAndComposite(
            String surfaceId,
            ByteBuffer pixels,
            int rectX,
            int rectY,
            int rectWidth,
            int rectHeight,
            String contentFingerprint
    ) {
        return applyAndComposite(surfaceId, pixels, rectX, rectY, rectWidth, rectHeight,
                contentFingerprint, null);
    }

    /**
     * Isolated-adapter intake. {@code fullPixels} is a full Gray8 surface, but
     * only the declared damage rectangles are read and copied into retained
     * broker storage. This is the APK path's single host-private pixel copy.
     */
    public Composite applyDamageAndComposite(String surfaceId,ByteBuffer fullPixels,int[] damage,
            String contentFingerprint,ByteBuffer draws) {
        ScreenDraw[] parsed=parseDraws(draws);
        synchronized(lock){
            return applyDamageAndCompositeLocked(surfaceId,fullPixels,damage,contentFingerprint,parsed,true);
        }
    }

    /** External broker intake: compose and patch caller-owned packed storage without a Gray8 snapshot allocation. */
    public PackedComposite applyDamageAndCompositePacked(String surfaceId,ByteBuffer fullPixels,int[] damage,
            String contentFingerprint,ByteBuffer draws,byte[] packedTarget) {
        ScreenDraw[] parsed=parseDraws(draws);
        synchronized(lock){
            requireScreenConfiguredLocked();
            int size=((screenWidth+1)>>1)*screenHeight;
            if(packedTarget==null||packedTarget.length!=size)throw new IllegalArgumentException("Invalid packed target");
            Composite composite=applyDamageAndCompositeLocked(surfaceId,fullPixels,damage,contentFingerprint,parsed,false);
            System.arraycopy(retainedPacked,0,packedTarget,0,size);
            return new PackedComposite(composite,packedTarget);
        }
    }

    private Composite applyDamageAndCompositeLocked(String surfaceId,ByteBuffer fullPixels,int[] damage,
            String contentFingerprint,ScreenDraw[] parsed,boolean snapshotGray) {
            requireScreenConfiguredLocked();Surface surface=surfaces.get(surfaceId);
            if(surface==null)throw new IllegalArgumentException("unknown surface "+surfaceId);
            if(fullPixels==null||fullPixels.remaining()!=surface.width*surface.height)throw new IllegalArgumentException("invalid full surface buffer");
            int[] rects=damage==null||damage.length==0?new int[]{0,0,surface.width,surface.height}:damage;
            if(rects.length%4!=0||rects.length>32)throw new IllegalArgumentException("invalid damage list");
            int minX=surface.width,minY=surface.height,maxX=-1,maxY=-1,base=fullPixels.position();
            for(int i=0;i<rects.length;i+=4){int x=rects[i],y=rects[i+1],width=rects[i+2],height=rects[i+3];
                if(x<0||y<0||width<=0||height<=0||x+width>surface.width||y+height>surface.height)throw new IllegalArgumentException("damage outside surface");
                for(int row=0;row<height;row++){int offset=(y+row)*surface.width+x;for(int col=0;col<width;col++){int index=offset+col;byte value=fullPixels.get(base+index);if(surface.pixels[index]!=value){surface.pixels[index]=value;int px=x+col,py=y+row;minX=Math.min(minX,px);minY=Math.min(minY,py);maxX=Math.max(maxX,px);maxY=Math.max(maxY,py);}}}
            }
            if(maxX>=minX&&surface.visible)markScreenRectDirtyLocked(surface.x+minX,surface.y+minY,maxX-minX+1,maxY-minY+1);
            surface.fingerprint=contentFingerprint==null?"":contentFingerprint;surface.draws=parsed;return compositeLocked(snapshotGray);
    }

    /**
     * As above, with the frame's deferred draws: a little-endian buffer of
     * tagged records
     *   [0][fontId u16][encoding u32][penX s16][lineY s16][value u8]  (glyph)
     *   [1][imageId u32][x s16][y s16]                                (image)
     * in surface-local coordinates and draw order. The list describes the
     * surface's FULL retained content and replaces the previous list, so it is
     * only meaningful for full-surface updates (which is what every submitter
     * sends); pass null to clear. Draw pixels must already be baked into pixels.
     */
    public Composite applyAndComposite(
            String surfaceId,
            ByteBuffer pixels,
            int rectX,
            int rectY,
            int rectWidth,
            int rectHeight,
            String contentFingerprint,
            ByteBuffer glyphs
    ) {
        ScreenDraw[] parsed = parseDraws(glyphs);
        synchronized (lock) {
            requireScreenConfiguredLocked();
            Surface surface = surfaces.get(surfaceId);
            if (surface == null) {
                throw new IllegalArgumentException("unknown surface " + surfaceId);
            }
            if (rectX < 0 || rectY < 0 || rectWidth <= 0 || rectHeight <= 0
                    || rectX + rectWidth > surface.width || rectY + rectHeight > surface.height) {
                throw new IllegalArgumentException("update rect " + rectWidth + "x" + rectHeight
                        + "+" + rectX + "+" + rectY + " outside surface " + surfaceId
                        + " (" + surface.width + "x" + surface.height + ")");
            }
            int expectedBytes = rectWidth * rectHeight;
            if (pixels == null || pixels.remaining() != expectedBytes) {
                throw new IllegalArgumentException("update buffer for " + surfaceId + " has "
                        + (pixels == null ? 0 : pixels.remaining()) + " bytes, expected " + expectedBytes);
            }
            int minX=rectX+rectWidth,minY=rectY+rectHeight,maxX=-1,maxY=-1;
            for (int row = 0; row < rectHeight; row++) {
                int dstOffset = (rectY + row) * surface.width + rectX;
                for(int col=0;col<rectWidth;col++){
                    byte value=pixels.get();int index=dstOffset+col;
                    if(surface.pixels[index]!=value){surface.pixels[index]=value;int x=rectX+col,y=rectY+row;minX=Math.min(minX,x);minY=Math.min(minY,y);maxX=Math.max(maxX,x);maxY=Math.max(maxY,y);}
                }
            }
            if(maxX>=minX&&surface.visible)markScreenRectDirtyLocked(surface.x+minX,surface.y+minY,maxX-minX+1,maxY-minY+1);
            surface.fingerprint = contentFingerprint == null ? "" : contentFingerprint;
            surface.draws = parsed;
            return compositeLocked();
        }
    }

    private static ScreenDraw[] parseDraws(ByteBuffer draws) {
        if (draws == null || draws.remaining() < 1) {
            return NO_DRAWS;
        }
        ByteBuffer in = draws.order(java.nio.ByteOrder.LITTLE_ENDIAN);
        List<ScreenDraw> out = new ArrayList<>();
        while (in.remaining() >= 1) {
            int kind = in.get() & 0xff;
            if (kind == ScreenDraw.KIND_GLYPH && in.remaining() >= 11) {
                int fontId = in.getShort() & 0xffff;
                int encoding = in.getInt();
                int penX = in.getShort();
                int lineY = in.getShort();
                int value = in.get() & 0xff;
                out.add(ScreenDraw.glyph(fontId, encoding, penX, lineY, value));
            } else if (kind == ScreenDraw.KIND_IMAGE && in.remaining() >= 8) {
                int imageId = in.getInt();
                int x = in.getShort();
                int y = in.getShort();
                out.add(ScreenDraw.image(imageId, x, y));
            } else if (kind == ScreenDraw.KIND_FWTEXT && in.remaining() >= 6) {
                int x = in.getShort();
                int y = in.getShort();
                int value = in.get() & 0xff;
                int count = in.get() & 0xff;
                if (in.remaining() < count * 7) {
                    break; // malformed tail: keep what parsed cleanly
                }
                int[] cps = new int[count];
                int[] dx = new int[count];
                boolean[] ink = new boolean[count];
                for (int i = 0; i < count; i++) {
                    cps[i] = in.getInt();
                    dx[i] = in.getShort();
                    ink[i] = in.get() != 0;
                }
                out.add(ScreenDraw.fwText(x, y, value, cps, dx, ink));
            } else {
                break; // malformed tail: keep what parsed cleanly
            }
        }
        return out.toArray(new ScreenDraw[0]);
    }

    /** Composite the current retained state without applying an update. */
    public Composite composite() {
        synchronized (lock) {
            requireScreenConfiguredLocked();
            return compositeLocked();
        }
    }

    /**
     * Current composited pixels for the phone-side preview / screenshot, or
     * null before the screen is configured. Does not consume a sequence
     * number (it is never stored as the desired frame).
     */
    public Composite previewComposite() {
        synchronized (lock) {
            if (screenWidth <= 0 || screenHeight <= 0) {
                return null;
            }
            recomposeDirtyLocked();
            return new Composite(retainedGray.clone(), screenWidth, screenHeight, "preview", 0, NO_DRAWS, new int[0]);
        }
    }

    private byte[] buildGrayLocked() {
        byte[] gray = new byte[screenWidth * screenHeight];
        if (blanked) {
            return gray;
        }
        List<Surface> ordered = new ArrayList<>(surfaces.values());
        ordered.sort(Comparator
                .comparingInt((Surface s) -> s.zOrder)
                .thenComparing(s -> s.id));
        for (Surface surface : ordered) {
            // Preview, screenshots and recordings must hide the same underlays
            // as the transmitted composite, before preview gamma brightens them.
            if (surface.visible && dimForLocked(surface) != 0) {
                blendLocked(gray, surface);
            }
        }
        return gray;
    }

    private Composite compositeLocked() { return compositeLocked(true); }
    private Composite compositeLocked(boolean snapshotGray) {
        int[] damage=recomposeDirtyLocked();
        if(!retainedPackedValid){BmpUtil.patch4bppFromGray8(retainedGray,screenWidth,screenHeight,null,retainedPacked);retainedPackedValid=true;}
        else if(damage.length>0)BmpUtil.patch4bppFromGray8(retainedGray,screenWidth,screenHeight,damage,retainedPacked);
        if (blanked) return new Composite(snapshotGray?retainedGray.clone():null, screenWidth, screenHeight,
                "blanked:" + screenWidth + "x" + screenHeight, nextCompositeSeq++, NO_DRAWS, damage);
        List<Surface> ordered = new ArrayList<>(surfaces.values());
        ordered.sort(Comparator
                .comparingInt((Surface s) -> s.zOrder)
                .thenComparing(s -> s.id));
        StringBuilder fingerprint = new StringBuilder();
        fingerprint.append(screenWidth).append('x').append(screenHeight);
        List<ScreenDraw> draws = new ArrayList<>();
        for (Surface surface : ordered) {
            // Zero means fully hidden, including cached glyph/image identities.
            // dimValue's positive-brightness floor must not reveal the old app.
            if (!surface.visible || dimForLocked(surface) == 0) continue;
            int dim = dimForLocked(surface);
            for (ScreenDraw draw : surface.draws) {
                if (dim < 256 && draw.kind == ScreenDraw.KIND_IMAGE) continue;
                int value = dim < 256 ? dimValue(draw.value, dim) : draw.value;
                draws.add(new ScreenDraw(draw.kind, draw.fontId, draw.encoding, draw.imageId,
                        draw.x + surface.x, draw.y + surface.y, value,
                        draw.fwCps, draw.fwDx, draw.fwInk));
            }
            fingerprint.append('|').append(surface.id)
                    .append('@').append(surface.x).append(',').append(surface.y)
                    .append('+').append(surface.width).append('x').append(surface.height)
                    .append('#').append(surface.zOrder)
                    .append(':').append(surface.transparency)
                    .append(':').append(surface.fingerprint);
            if (dim < 256) {
                fingerprint.append(":dim").append(dim);
            }
        }
        return new Composite(snapshotGray?retainedGray.clone():null, screenWidth, screenHeight, fingerprint.toString(), nextCompositeSeq++,
                draws.toArray(new ScreenDraw[0]), damage);
    }

    private int[] recomposeDirtyLocked() {
        if (!retainedValid) markAllDirtyLocked();
        int minX=screenWidth,minY=screenHeight,maxX=-1,maxY=-1;
        List<Surface> ordered = new ArrayList<>(surfaces.values());
        ordered.sort(Comparator.comparingInt((Surface s)->s.zOrder).thenComparing(s->s.id));
        for(int tile=0;tile<dirtyTiles.length;tile++){
            if(!dirtyTiles[tile])continue;dirtyTiles[tile]=false;
            int tx=(tile%tileColumns)*TILE_WIDTH,ty=(tile/tileColumns)*TILE_HEIGHT;
            int right=Math.min(screenWidth,tx+TILE_WIDTH),bottom=Math.min(screenHeight,ty+TILE_HEIGHT);
            for(int y=ty;y<bottom;y++)java.util.Arrays.fill(retainedGray,y*screenWidth+tx,y*screenWidth+right,(byte)0);
            if(!blanked)for(Surface surface:ordered)if(surface.visible&&dimForLocked(surface)!=0)blendRegionLocked(retainedGray,surface,tx,ty,right,bottom);
            minX=Math.min(minX,tx);minY=Math.min(minY,ty);maxX=Math.max(maxX,right);maxY=Math.max(maxY,bottom);
        }
        retainedValid=true;
        if(compareDirtyWithFull){byte[] full=buildGrayLocked();if(!java.util.Arrays.equals(full,retainedGray))throw new IllegalStateException("Dirty compositor diverged from full composition");}
        return maxX<minX?new int[0]:new int[]{minX,minY,maxX-minX,maxY-minY};
    }

    private void markAllDirtyLocked(){if(dirtyTiles!=null)java.util.Arrays.fill(dirtyTiles,true);}
    private void markScreenRectDirtyLocked(int x,int y,int width,int height){
        if(dirtyTiles==null||width<=0||height<=0)return;int left=Math.max(0,x),top=Math.max(0,y),right=Math.min(screenWidth,x+width),bottom=Math.min(screenHeight,y+height);if(left>=right||top>=bottom)return;
        int firstCol=left/TILE_WIDTH,lastCol=(right-1)/TILE_WIDTH,firstRow=top/TILE_HEIGHT,lastRow=(bottom-1)/TILE_HEIGHT;
        for(int row=firstRow;row<=lastRow;row++)for(int col=firstCol;col<=lastCol;col++)dirtyTiles[row*tileColumns+col]=true;
    }

    private void blendRegionLocked(byte[] gray, Surface surface, int regionLeft, int regionTop, int regionRight, int regionBottom) {
        int dstX=Math.max(Math.max(0,surface.x),regionLeft),dstY=Math.max(Math.max(0,surface.y),regionTop);
        int right=Math.min(Math.min(screenWidth,surface.x+surface.width),regionRight),bottom=Math.min(Math.min(screenHeight,surface.y+surface.height),regionBottom);
        if(dstX>=right||dstY>=bottom)return;int copyWidth=right-dstX,copyHeight=bottom-dstY,srcX=dstX-surface.x,srcY=dstY-surface.y,dim=dimForLocked(surface);
        for(int row=0;row<copyHeight;row++){
            int srcOffset=(srcY+row)*surface.width+srcX,dstOffset=(dstY+row)*screenWidth+dstX;
            if(dim<256){for(int col=0;col<copyWidth;col++){int value=surface.pixels[srcOffset+col]&0xff;if(value!=0)gray[dstOffset+col]=(byte)dimValue(value,dim);else if(surface.transparency==TRANSPARENCY_OPAQUE)gray[dstOffset+col]=0;}}
            else if(surface.transparency==TRANSPARENCY_OPAQUE)System.arraycopy(surface.pixels,srcOffset,gray,dstOffset,copyWidth);
            else for(int col=0;col<copyWidth;col++){byte value=surface.pixels[srcOffset+col];if(value!=0)gray[dstOffset+col]=value;}
        }
    }

    private void blendLocked(byte[] gray, Surface surface) {
        // Clip the surface rect to the screen.
        int srcX = Math.max(0, -surface.x);
        int srcY = Math.max(0, -surface.y);
        int dstX = Math.max(0, surface.x);
        int dstY = Math.max(0, surface.y);
        int copyWidth = Math.min(surface.width - srcX, screenWidth - dstX);
        int copyHeight = Math.min(surface.height - srcY, screenHeight - dstY);
        if (copyWidth <= 0 || copyHeight <= 0) {
            return;
        }
        int dim = dimForLocked(surface);
        for (int row = 0; row < copyHeight; row++) {
            int srcOffset = (srcY + row) * surface.width + srcX;
            int dstOffset = (dstY + row) * screenWidth + dstX;
            if (dim < 256) {
                // Dimmed: opaque or color-keyed, 0 stays 0 (black / transparent)
                // and everything visible scales, never below 1.
                for (int col = 0; col < copyWidth; col++) {
                    int value = surface.pixels[srcOffset + col] & 0xff;
                    if (value != 0) {
                        gray[dstOffset + col] = (byte) dimValue(value, dim);
                    } else if (surface.transparency == TRANSPARENCY_OPAQUE) {
                        gray[dstOffset + col] = 0;
                    }
                }
            } else if (surface.transparency == TRANSPARENCY_OPAQUE) {
                System.arraycopy(surface.pixels, srcOffset, gray, dstOffset, copyWidth);
            } else {
                for (int col = 0; col < copyWidth; col++) {
                    byte value = surface.pixels[srcOffset + col];
                    if (value != 0) {
                        gray[dstOffset + col] = value;
                    }
                }
            }
        }
    }

    private void requireScreenConfiguredLocked() {
        if (screenWidth <= 0 || screenHeight <= 0) {
            throw new IllegalStateException("configureScreen must be called first");
        }
    }
}
