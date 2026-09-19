package com.faceclaw.app;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public final class CaptureDrainCheck {
    private static final int HOP = 128;

    public static void main(String[] args) throws Exception {
        drainsEveryAcceptedValueInOrder();
        abortDropsPendingValuesAndUnblocksConsumer();
        boundsBacklogAndRejectsLateValues();
        reopensWithoutLeakingThePreviousSession();
        flushesTheSuppressorTailExactlyOnce();
    }

    private static void drainsEveryAcceptedValueInOrder() throws Exception {
        DrainableAudioQueue<Integer> queue = new DrainableAudioQueue<>(16);
        queue.open();
        List<Integer> consumed = new ArrayList<>();
        Thread consumer = new Thread(() -> {
            try {
                Integer value;
                while ((value = queue.take()) != null) {
                    consumed.add(value);
                    Thread.sleep(2);
                }
            } catch (InterruptedException error) {
                throw new AssertionError(error);
            }
        });
        consumer.start();
        for (int i = 0; i < 10; i++) assertTrue(queue.offer(i), "accepted before close");
        queue.closeForDrain();
        assertTrue(!queue.offer(10), "late value rejected");
        consumer.join(2000);
        assertTrue(!consumer.isAlive(), "draining consumer finished");
        assertEquals(Arrays.asList(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), consumed, "drain order");
    }

    private static void abortDropsPendingValuesAndUnblocksConsumer() throws Exception {
        DrainableAudioQueue<Integer> queue = new DrainableAudioQueue<>(8);
        queue.open();
        queue.offer(1);
        queue.offer(2);
        queue.abort();
        assertEquals(null, queue.take(), "aborted queue is empty");
        assertTrue(queue.isAborted(), "abort state");

        queue.open();
        final Object[] taken = new Object[1];
        Thread waiter = new Thread(() -> {
            try {
                taken[0] = queue.take();
            } catch (InterruptedException error) {
                throw new AssertionError(error);
            }
        });
        waiter.start();
        Thread.sleep(10);
        queue.abort();
        waiter.join(2000);
        assertTrue(!waiter.isAlive(), "abort unblocked waiter");
        assertEquals(null, taken[0], "waiter received no phantom value");
    }

    private static void boundsBacklogAndRejectsLateValues() throws Exception {
        DrainableAudioQueue<Integer> queue = new DrainableAudioQueue<>(3);
        queue.open();
        for (int i = 0; i < 5; i++) queue.offer(i);
        queue.closeForDrain();
        assertEquals(2L, queue.droppedCount(), "bounded queue drop count");
        assertEquals(2, queue.take(), "oldest retained value");
        assertEquals(3, queue.take(), "middle retained value");
        assertEquals(4, queue.take(), "newest retained value");
        assertEquals(null, queue.take(), "drained queue closes");
    }

    private static void reopensWithoutLeakingThePreviousSession() throws Exception {
        DrainableAudioQueue<Integer> queue = new DrainableAudioQueue<>(3);
        queue.open();
        queue.offer(1);
        queue.closeForDrain();
        assertEquals(1, queue.take(), "first generation");
        assertEquals(null, queue.take(), "first generation complete");
        queue.open();
        queue.offer(2);
        queue.closeForDrain();
        assertEquals(2, queue.take(), "second generation");
        assertEquals(null, queue.take(), "second generation complete");
    }

    private static void flushesTheSuppressorTailExactlyOnce() {
        checkSuppressionLength(new int[] {800, 800, 800, 800});
        checkSuppressionLength(new int[] {1, 7, 120, 800, 3, 333});
        checkSuppressionLength(new int[] {17});

        FaceclawNoiseSuppressor unused = new FaceclawNoiseSuppressor(16000);
        assertEquals(0, unused.finish().length, "unused suppressor has no tail");
    }

    private static void checkSuppressionLength(int[] chunks) {
        FaceclawNoiseSuppressor suppressor = new FaceclawNoiseSuppressor(16000);
        int inputSamples = 0;
        int outputBytes = 0;
        for (int chunk : chunks) {
            inputSamples += chunk;
            outputBytes += suppressor.process(toBytes(sequence(chunk))).length;
        }
        byte[] tail = suppressor.finish();
        outputBytes += tail.length;
        assertEquals((inputSamples + HOP) * 2, outputBytes, "flushed stream length");
        assertTrue(tail.length >= HOP * 2, "flush releases delayed hop");
        assertEquals(0, suppressor.finish().length, "flush is once-only");
    }

    private static short[] sequence(int length) {
        short[] values = new short[length];
        for (int i = 0; i < length; i++) values[i] = (short) (1000 + i);
        return values;
    }

    private static byte[] toBytes(short[] samples) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(samples.length * 2);
        for (short sample : samples) {
            bytes.write(sample & 0xff);
            bytes.write((sample >> 8) & 0xff);
        }
        return bytes.toByteArray();
    }

    private static void assertTrue(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }

    private static void assertEquals(Object expected, Object actual, String label) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }
}
