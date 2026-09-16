package com.faceclaw.app;

import java.nio.ByteBuffer;

/**
 * Single host-side ownership boundary for retained display surfaces. Trusted
 * shell/built-in producers and isolated SDK sessions enter through the same
 * methods and therefore receive identical composition and planner input.
 */
final class RenderBroker {
    private final SurfaceCompositor compositor = new SurfaceCompositor();

    void configureScreen(int width,int height){compositor.configureScreen(width,height);}
    int packedFrameSize(){return compositor.packedFrameSize();}
    void configureSurface(String id,int x,int y,int width,int height,int zOrder,int transparency){compositor.configureSurface(id,x,y,width,height,zOrder,transparency);}
    void removeSurface(String id){compositor.removeSurface(id);}
    void setSurfaceVisible(String id,boolean visible){compositor.setSurfaceVisible(id,visible);}
    void setBlanked(boolean blanked){compositor.setBlanked(blanked);}
    void setUnderlayDim(int belowZOrder,int factor256){compositor.setUnderlayDim(belowZOrder,factor256);}
    SurfaceCompositor.Composite composite(){return compositor.composite();}
    SurfaceCompositor.Composite previewComposite(){return compositor.previewComposite();}
    SurfaceCompositor.Composite applyAndComposite(String id,ByteBuffer pixels,int x,int y,int width,int height,String fingerprint){return compositor.applyAndComposite(id,pixels,x,y,width,height,fingerprint);}
    SurfaceCompositor.Composite applyAndComposite(String id,ByteBuffer pixels,int x,int y,int width,int height,String fingerprint,ByteBuffer draws){return compositor.applyAndComposite(id,pixels,x,y,width,height,fingerprint,draws);}
    SurfaceCompositor.Composite applyDamageAndComposite(String id,ByteBuffer pixels,int[] damage,String fingerprint,ByteBuffer draws){return compositor.applyDamageAndComposite(id,pixels,damage,fingerprint,draws);}
    SurfaceCompositor.PackedComposite applyDamageAndCompositePacked(String id,ByteBuffer pixels,int[] damage,String fingerprint,ByteBuffer draws,byte[] target){return compositor.applyDamageAndCompositePacked(id,pixels,damage,fingerprint,draws,target);}
    SurfaceCompositor.PackedComposite applyDamageAndCompositePacked(String id,ByteBuffer pixels,int[] damage,String fingerprint,ByteBuffer draws,int[] retainedCopies,byte[] target){return compositor.applyDamageAndCompositePacked(id,pixels,damage,fingerprint,draws,retainedCopies,target);}
}
