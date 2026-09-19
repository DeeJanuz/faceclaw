package com.faceclaw.app;

import java.util.ArrayDeque;

/** A bounded producer/consumer queue that can close either by draining or aborting. */
final class DrainableAudioQueue<T> {
    private final int capacity;
    private final ArrayDeque<T> queue = new ArrayDeque<>();
    private boolean accepting;
    private boolean aborted;
    private long dropped;

    DrainableAudioQueue(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    synchronized void open() {
        queue.clear();
        accepting = true;
        aborted = false;
        dropped = 0;
        notifyAll();
    }

    /** Returns false after the producer side has closed. */
    synchronized boolean offer(T value) {
        if (!accepting || value == null) return false;
        if (queue.size() >= capacity) {
            queue.removeFirst();
            dropped++;
        }
        queue.addLast(value);
        notifyAll();
        return true;
    }

    /** Stop accepting new values and let the consumer empty what was accepted. */
    synchronized void closeForDrain() {
        accepting = false;
        notifyAll();
    }

    /** Stop immediately and discard values that have not reached the consumer. */
    synchronized void abort() {
        accepting = false;
        aborted = true;
        queue.clear();
        notifyAll();
    }

    /** Returns null once closed and empty, or immediately after an abort. */
    synchronized T take() throws InterruptedException {
        while (queue.isEmpty() && accepting && !aborted) {
            wait(250);
        }
        if (aborted) return null;
        return queue.pollFirst();
    }

    synchronized boolean isAborted() {
        return aborted;
    }

    synchronized long droppedCount() {
        return dropped;
    }
}
