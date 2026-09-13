package com.faceclaw.app;

public final class RenderPacerCheck {
    public static void main(String[] args) {
        RenderPacer pacer = new RenderPacer();
        if (pacer.delay(100, false) != 0) throw new AssertionError("first frame delayed");
        if (pacer.delay(112, true) != 4) throw new AssertionError("render time not deducted");
        if (pacer.delay(150, true) != 0) throw new AssertionError("missed opportunity delayed again");
        if (pacer.delay(151, false) != 0) throw new AssertionError("in-flight preparation blocked");
        for (int i=0;i<50;i++) pacer.acknowledged(10000);
        pacer.delay(200, false);
        long delay=pacer.delay(201, true);
        if (delay<0 || delay>100) throw new AssertionError("outlier produced unbounded delay");
    }
}
