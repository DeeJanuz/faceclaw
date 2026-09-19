package com.faceclaw.app;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class Pcm16StreamAdapterCheck {
    private static final int HOP = 128;

    public static void main(String[] args) {
        preservesFourGlassesPackets();
        preservesArbitraryPacketization();
        acceptsEmptyAndLargerOutput();
        respectsValidInputCount();
        rejectsInvalidProcessorOutput();
        matchesRealSuppressorOutput();
    }

    private static void preservesFourGlassesPackets() {
        HopBuffer processor = new HopBuffer();
        List<short[]> outputs = new ArrayList<>();
        short[] expected = sequence(3200, -1600);
        for (int offset = 0; offset < expected.length; offset += 800) {
            short[] input = Arrays.copyOfRange(expected, offset, offset + 800);
            outputs.add(Pcm16StreamAdapter.process(input, input.length, processor::process));
        }
        assertArrayEquals(expected, concatenate(outputs), "four 800-sample packets");
        assertEquals(768, outputs.get(0).length, "first output length");
        assertEquals(896, outputs.get(3).length, "fourth output length");
    }

    private static void preservesArbitraryPacketization() {
        int[] chunks = {1, 7, 120, 800, 3, 333};
        short[] input = sequence(Arrays.stream(chunks).sum(), -500);
        HopBuffer processor = new HopBuffer();
        List<short[]> outputs = new ArrayList<>();
        int offset = 0;
        for (int chunk : chunks) {
            short[] block = Arrays.copyOfRange(input, offset, offset + chunk);
            outputs.add(Pcm16StreamAdapter.process(block, block.length, processor));
            offset += chunk;
        }
        short[] actual = concatenate(outputs);
        int completeSamples = input.length / HOP * HOP;
        assertArrayEquals(Arrays.copyOf(input, completeSamples), actual, "arbitrary chunks");
    }

    private static void acceptsEmptyAndLargerOutput() {
        short[] empty = Pcm16StreamAdapter.process(new short[] {1}, 1, ignored -> new byte[0]);
        assertEquals(0, empty.length, "empty output");

        short[] larger = Pcm16StreamAdapter.process(
                new short[] {1, 2}, 2,
                ignored -> toBytes(new short[] {10, 20, 30, 40}));
        assertArrayEquals(new short[] {10, 20, 30, 40}, larger, "larger output");
    }

    private static void respectsValidInputCount() {
        short[] input = {10, 20, 30, 40};
        short[] actual = Pcm16StreamAdapter.process(input, 2, bytes -> bytes);
        assertArrayEquals(new short[] {10, 20}, actual, "valid input count");
    }

    private static void rejectsInvalidProcessorOutput() {
        assertThrows(() -> Pcm16StreamAdapter.process(new short[] {1}, 1, ignored -> null));
        assertThrows(() -> Pcm16StreamAdapter.process(new short[] {1}, 1, ignored -> new byte[1]));
        assertThrows(() -> Pcm16StreamAdapter.process(new short[] {1}, 2, bytes -> bytes));
    }

    private static void matchesRealSuppressorOutput() {
        FaceclawNoiseSuppressor direct = new FaceclawNoiseSuppressor(16000);
        FaceclawNoiseSuppressor adapted = new FaceclawNoiseSuppressor(16000);
        int[] chunks = {800, 800, 17, 91, 1200};
        int offset = 0;
        short[] source = sequence(Arrays.stream(chunks).sum(), -1200);
        for (int chunk : chunks) {
            short[] input = Arrays.copyOfRange(source, offset, offset + chunk);
            byte[] expectedBytes = direct.process(toBytes(input));
            short[] actual = Pcm16StreamAdapter.process(input, input.length, adapted::process);
            assertArrayEquals(fromBytes(expectedBytes), actual, "real suppressor output");
            offset += chunk;
        }
    }

    private static short[] sequence(int length, int start) {
        short[] values = new short[length];
        for (int i = 0; i < length; i++) values[i] = (short) (start + i);
        return values;
    }

    private static byte[] toBytes(short[] samples) {
        byte[] bytes = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            bytes[i * 2] = (byte) (samples[i] & 0xff);
            bytes[i * 2 + 1] = (byte) ((samples[i] >> 8) & 0xff);
        }
        return bytes;
    }

    private static short[] fromBytes(byte[] bytes) {
        short[] samples = new short[bytes.length / 2];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (short) ((bytes[i * 2] & 0xff) | (bytes[i * 2 + 1] << 8));
        }
        return samples;
    }

    private static short[] concatenate(List<short[]> blocks) {
        int length = blocks.stream().mapToInt(block -> block.length).sum();
        short[] result = new short[length];
        int offset = 0;
        for (short[] block : blocks) {
            System.arraycopy(block, 0, result, offset, block.length);
            offset += block.length;
        }
        return result;
    }

    private static void assertThrows(Runnable runnable) {
        try {
            runnable.run();
            throw new AssertionError("expected exception");
        } catch (IllegalArgumentException | IllegalStateException expected) {
            // Expected.
        }
    }

    private static void assertArrayEquals(short[] expected, short[] actual, String label) {
        if (!Arrays.equals(expected, actual)) {
            throw new AssertionError(label + ": sample arrays differ");
        }
    }

    private static void assertEquals(int expected, int actual, String label) {
        if (expected != actual) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static final class HopBuffer implements Pcm16StreamAdapter.Processor {
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

        @Override
        public byte[] process(byte[] input) {
            pending.write(input, 0, input.length);
            byte[] all = pending.toByteArray();
            int completeBytes = all.length / (HOP * 2) * HOP * 2;
            byte[] output = Arrays.copyOf(all, completeBytes);
            pending.reset();
            pending.write(all, completeBytes, all.length - completeBytes);
            return output;
        }
    }
}
