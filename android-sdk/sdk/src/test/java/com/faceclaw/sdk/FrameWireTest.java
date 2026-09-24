package com.faceclaw.sdk;

import static org.junit.Assert.*;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class FrameWireTest {
    @Test
    public void crc32cMatchesStandardVectorOnApi27Implementation() {
        assertEquals(
                0xe3069283,
                FrameWire.crc32c(ByteBuffer.wrap("123456789".getBytes(StandardCharsets.US_ASCII))));
    }

    @Test
    public void completedFrameValidatesAndPixelMutationIsTorn() {
        ByteBuffer mapping = ByteBuffer.allocate(Protocol.FRAME_HEADER_BYTES + 4);
        FrameWire.begin(mapping, 1);
        FrameWire.pixels(mapping, 4).put(new byte[] {1, 2, 3, 4});
        FrameWire.finish(mapping, 7, 11, 13, 4, 1);
        assertEquals(FrameWire.Validation.VALID, FrameWire.validate(mapping, 7, 11, 4));
        FrameWire.pixels(mapping, 4).put(2, (byte) 9);
        assertEquals(FrameWire.Validation.TORN, FrameWire.validate(mapping, 7, 11, 4));
    }
}
