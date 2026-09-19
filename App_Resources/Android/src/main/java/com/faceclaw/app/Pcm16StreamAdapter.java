package com.faceclaw.app;

/**
 * Adapts short-array PCM blocks to a streaming processor whose output block
 * size can differ from its input block size.
 *
 * <p>Streaming DSP commonly retains an incomplete frame and emits it during a
 * later call. Callers must therefore consume the complete returned block
 * instead of copying it back into the fixed-size input buffer.</p>
 */
final class Pcm16StreamAdapter {
    interface Processor {
        byte[] process(byte[] pcm16le);
    }

    private Pcm16StreamAdapter() {}

    static short[] process(short[] input, int count, Processor processor) {
        if (input == null) {
            throw new IllegalArgumentException("input must not be null");
        }
        if (count < 0 || count > input.length) {
            throw new IllegalArgumentException("count is outside the input buffer");
        }
        if (processor == null) {
            throw new IllegalArgumentException("processor must not be null");
        }

        byte[] inputBytes = new byte[count * 2];
        for (int i = 0; i < count; i++) {
            short sample = input[i];
            inputBytes[i * 2] = (byte) (sample & 0xff);
            inputBytes[i * 2 + 1] = (byte) ((sample >> 8) & 0xff);
        }

        byte[] outputBytes = processor.process(inputBytes);
        if (outputBytes == null) {
            throw new IllegalStateException("PCM processor returned null");
        }
        if ((outputBytes.length & 1) != 0) {
            throw new IllegalStateException("PCM processor returned an incomplete sample");
        }

        short[] output = new short[outputBytes.length / 2];
        for (int i = 0; i < output.length; i++) {
            output[i] = (short) ((outputBytes[i * 2] & 0xff) | (outputBytes[i * 2 + 1] << 8));
        }
        return output;
    }
}
