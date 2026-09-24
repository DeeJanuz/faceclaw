package com.faceclaw.sdk;

import static org.junit.Assert.*;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class RetainedRegionCopyTest {
    @Test
    public void encodesCopyHintInCompatibleMetadataRecord() {
        FrameMetadata metadata =
                FrameMetadata.builder(1, 1).retainedCopy(12, 34, 320, 200, 4, 34).build();
        byte[] wire = DrawBatch.encodeMetadata(metadata.draws, metadata.retainedCopies);
        assertEquals(DrawBatch.RECORD_BYTES, wire.length);
        ByteBuffer in = ByteBuffer.wrap(wire).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(2, in.get() & 255);
        assertEquals(12, in.getShort());
        assertEquals(34, in.getShort());
        assertEquals(320, in.getShort() & 0xffff);
        assertEquals(200, in.getShort() & 0xffff);
        assertEquals(4, in.getShort());
        assertEquals(34, in.getShort());
        assertEquals(0, in.get() & 255);
    }

    @Test
    public void boundsHintCount() {
        FrameMetadata.Builder builder = FrameMetadata.builder(1, 1);
        for (int i = 0; i < Protocol.MAX_RETAINED_COPIES; i++)
            builder.retainedCopy(0, 0, 1, 1, 1, 0);
        try {
            builder.retainedCopy(0, 0, 1, 1, 1, 0);
            fail("Unbounded copy hints accepted");
        } catch (IllegalStateException expected) {
        }
    }

    @Test
    public void rejectsEmptyCopy() {
        try {
            new RetainedRegionCopy(0, 0, 0, 1, 1, 0);
            fail("Empty copy accepted");
        } catch (IllegalArgumentException expected) {
        }
    }
}
