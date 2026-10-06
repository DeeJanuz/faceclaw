package com.faceclaw.app

/**
 * A bounded producer/consumer queue that closes either by draining (the consumer
 * still receives every accepted value) or by aborting (pending values are dropped).
 */
class DrainableAudioQueue<T : Any>(private val capacity: Int, platform: ProtocolPlatform = protocolPlatform()) {
    private val condition = platform.createCondition()
    private val items = ArrayDeque<T>()
    private var accepting = false
    private var aborted = false
    private var dropped = 0L

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    fun open() {
        condition.withLock {
            items.clear()
            accepting = true
            aborted = false
            dropped = 0
            condition.signalAll()
        }
    }

    /** Returns false after the producer side has closed. */
    fun offer(value: T): Boolean {
        condition.withLock {
            if (!accepting) return false
            if (items.size >= capacity) {
                items.removeFirst()
                dropped++
            }
            items.addLast(value)
            condition.signalAll()
            return true
        }
    }

    /** Stop accepting new values and let the consumer empty what was accepted. */
    fun closeForDrain() {
        condition.withLock {
            accepting = false
            condition.signalAll()
        }
    }

    /** Stop immediately and discard values that have not reached the consumer. */
    fun abort() {
        condition.withLock {
            accepting = false
            aborted = true
            items.clear()
            condition.signalAll()
        }
    }

    /** Blocks for the next value; null once closed and empty, or immediately after an abort. */
    fun take(): T? {
        condition.withLock {
            while (items.isEmpty() && accepting && !aborted) {
                condition.awaitMs(250)
            }
            if (aborted) return null
            return items.removeFirstOrNull()
        }
    }

    fun isAborted(): Boolean = condition.withLock { aborted }

    fun droppedCount(): Long = condition.withLock { dropped }
}

/**
 * Adapts short-array PCM blocks to a streaming processor whose output block size can
 * differ from its input. Streaming DSP keeps an incomplete frame and emits it on a later
 * call, so callers consume the whole returned block instead of copying it back into the
 * fixed-size input buffer.
 */
object Pcm16StreamAdapter {
    fun process(input: ShortArray, count: Int, processor: (ByteArray) -> ByteArray?): ShortArray {
        require(count in 0..input.size) { "count is outside the input buffer" }
        return decode(processor(AudioSegmentation.pcm16ToLittleEndian(input, 0, count)))
    }

    fun decode(outputBytes: ByteArray?): ShortArray {
        checkNotNull(outputBytes) { "PCM processor returned null" }
        check(outputBytes.size and 1 == 0) { "PCM processor returned an incomplete sample" }
        return ShortArray(outputBytes.size / 2) { AudioSegmentation.pcm16le(outputBytes[it * 2], outputBytes[it * 2 + 1]) }
    }
}
