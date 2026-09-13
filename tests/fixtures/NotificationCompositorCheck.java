package com.faceclaw.app;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class NotificationCompositorCheck {
    private static ByteBuffer draws(int glyph) {
        ByteBuffer buffer = ByteBuffer.allocate(21).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put((byte) 0).putShort((short) 1).putInt(glyph).putShort((short) 0).putShort((short) 0).put((byte) 255);
        buffer.put((byte) 1).putInt(glyph + 1).putShort((short) 0).putShort((short) 0);
        buffer.flip();
        return buffer;
    }
    private static void pixels(SurfaceCompositor.Composite frame, int... expected) {
        for (int i = 0; i < expected.length; i++) {
            if ((frame.gray[i] & 255) != expected[i]) throw new AssertionError("pixel " + i + " = " + (frame.gray[i] & 255) + ", expected " + expected[i]);
        }
    }
    public static void main(String[] args) {
        SurfaceCompositor compositor = new SurfaceCompositor();
        compositor.configureScreen(3, 1);
        compositor.configureSurface("private-app", 0, 0, 3, 1, 0, SurfaceCompositor.TRANSPARENCY_OPAQUE);
        compositor.configureSurface("notification", 0, 0, 3, 1, 1, SurfaceCompositor.TRANSPARENCY_COLOR_KEY);
        compositor.configureSurface("lock", 0, 0, 3, 1, 1000, SurfaceCompositor.TRANSPARENCY_COLOR_KEY);
        compositor.setSurfaceVisible("lock", false);
        compositor.applyAndComposite("private-app", ByteBuffer.wrap(new byte[] {(byte) 180, 80, (byte) 255}), 0, 0, 3, 1, "secret-one", draws(900));
        compositor.applyAndComposite("notification", ByteBuffer.wrap(new byte[] {0, (byte) 200, 0}), 0, 0, 3, 1, "preview");
        compositor.setUnderlayDim(1, 0);
        SurfaceCompositor.Composite hidden = compositor.composite();
        pixels(hidden, 0, 200, 0);
        pixels(compositor.previewComposite(), 0, 200, 0);
        if (hidden.draws.length != 0) throw new AssertionError("hidden glyph/image identities leaked");
        if (hidden.fingerprint.contains("secret-one")) throw new AssertionError("hidden content fingerprint leaked");
        SurfaceCompositor.Composite updated = compositor.applyAndComposite("private-app", ByteBuffer.wrap(new byte[] {90, 40, 100}), 0, 0, 3, 1, "secret-two", draws(950));
        pixels(updated, 0, 200, 0);
        pixels(compositor.previewComposite(), 0, 200, 0);
        if (!hidden.fingerprint.equals(updated.fingerprint)) throw new AssertionError("hidden update changed preview fingerprint");
        compositor.applyAndComposite("lock", ByteBuffer.wrap(new byte[] {0, 0, (byte) 222}), 0, 0, 3, 1, "lock");
        compositor.setSurfaceVisible("lock", true);
        pixels(compositor.composite(), 0, 200, 222);
        pixels(compositor.previewComposite(), 0, 200, 222);
        compositor.setSurfaceVisible("lock", false);
        compositor.setUnderlayDim(1, 128);
        SurfaceCompositor.Composite dimmed = compositor.composite();
        pixels(dimmed, 45, 200, 50);
        pixels(compositor.previewComposite(), 45, 200, 50);
        if (dimmed.draws.length != 1 || dimmed.draws[0].value != 128) throw new AssertionError("positive dimming changed");
        compositor.setUnderlayDim(1, 256);
        SurfaceCompositor.Composite restored = compositor.composite();
        pixels(restored, 90, 200, 100);
        pixels(compositor.previewComposite(), 90, 200, 100);
        if (restored.draws.length != 2 || restored.draws[0].encoding != 950) throw new AssertionError("retained content did not restore");

        SurfaceCompositor packedCompositor = new SurfaceCompositor();
        packedCompositor.configureScreen(4, 2);
        packedCompositor.configureSurface("apk", 0, 0, 4, 2, 0, SurfaceCompositor.TRANSPARENCY_OPAQUE);
        byte[] target = new byte[4];
        SurfaceCompositor.PackedComposite first = packedCompositor.applyDamageAndCompositePacked("apk",
            ByteBuffer.wrap(new byte[]{0, 16, 32, 48, 64, 80, 96, 112}), new int[]{0, 0, 4, 2}, "one", null, target);
        if (first.composite.gray != null || first.packed != target) throw new AssertionError("packed intake allocated a Gray8 snapshot");
        byte[] changed = new byte[]{0, 16, 32, 48, 64, (byte) 240, 96, 112};
        byte[] nextTarget = new byte[4];
        SurfaceCompositor.PackedComposite second = packedCompositor.applyDamageAndCompositePacked("apk",
            ByteBuffer.wrap(changed), new int[]{1, 1, 1, 1}, "two", null, nextTarget);
        byte[] expected = BmpUtil.pack4bppFromGray8(changed, 4, 2);
        if (!java.util.Arrays.equals(second.packed, expected)) throw new AssertionError("dirty packed output diverged from full packing");

        // An APK viewport excludes shell chrome. Reject a viewport-sized output
        // before changing either retained pixels or dirty state.
        SurfaceCompositor windowed = new SurfaceCompositor();
        windowed.configureScreen(4, 3);
        windowed.configureSurface("apk", 0, 1, 4, 2, 0, SurfaceCompositor.TRANSPARENCY_OPAQUE);
        windowed.applyAndComposite("apk", ByteBuffer.wrap(new byte[8]), 0, 0, 4, 2, "empty");
        if (windowed.packedFrameSize() != 6) throw new AssertionError("packed size must use display geometry");
        byte[] white = new byte[8]; java.util.Arrays.fill(white, (byte)255);
        try {
            windowed.applyDamageAndCompositePacked("apk", ByteBuffer.wrap(white), null, "invalid", null, new byte[4]);
            throw new AssertionError("accepted a window-sized packed buffer");
        } catch (IllegalArgumentException expectedFailure) { }
        pixels(windowed.previewComposite(), 0,0,0,0,0,0,0,0,0,0,0,0);
        SurfaceCompositor.PackedComposite valid = windowed.applyDamageAndCompositePacked("apk", ByteBuffer.wrap(white), null, "valid", null, new byte[windowed.packedFrameSize()]);
        if (valid.composite.damage.length == 0) throw new AssertionError("failed submission consumed damage");
        pixels(windowed.previewComposite(), 0,0,0,0,255,255,255,255,255,255,255,255);

        SurfaceCompositor sparse = new SurfaceCompositor();
        sparse.configureScreen(96,48);
        sparse.configureSurface("outline",0,0,96,48,0,SurfaceCompositor.TRANSPARENCY_OPAQUE);
        byte[] outline=new byte[96*48];
        sparse.applyAndComposite("outline",ByteBuffer.wrap(outline),0,0,96,48,"empty");
        outline[0]=(byte)255;outline[outline.length-1]=(byte)255;
        SurfaceCompositor.PackedComposite corners=sparse.applyDamageAndCompositePacked("outline",ByteBuffer.wrap(outline),null,"corners",null,new byte[sparse.packedFrameSize()]);
        int area=0;for(int i=0;i<corners.composite.damage.length;i+=4)area+=corners.composite.damage[i+2]*corners.composite.damage[i+3];
        if(area>=96*48)throw new AssertionError("sparse damage recomposed the entire bounding box");
        if(!java.util.Arrays.equals(corners.packed,BmpUtil.pack4bppFromGray8(outline,96,48)))throw new AssertionError("sparse tiles differ from full packing");
        java.util.Random random=new java.util.Random(73);
        for(int frame=0;frame<40;frame++){
            for(int change=0;change<12;change++)outline[random.nextInt(outline.length)]=(byte)random.nextInt(256);
            SurfaceCompositor.PackedComposite update=sparse.applyDamageAndCompositePacked("outline",ByteBuffer.wrap(outline),null,"random-"+frame,null,new byte[sparse.packedFrameSize()]);
            if(!java.util.Arrays.equals(update.packed,BmpUtil.pack4bppFromGray8(outline,96,48)))throw new AssertionError("dirty packing diverged on frame "+frame);
        }
    }
}
