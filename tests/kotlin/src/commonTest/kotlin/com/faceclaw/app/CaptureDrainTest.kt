package com.faceclaw.app

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaptureDrainTest {
    @Test
    fun drainsEveryAcceptedValueInOrderAndRejectsLateValues() {
        val queue = DrainableAudioQueue<Int>(16)
        queue.open()
        for (i in 0 until 10) assertTrue(queue.offer(i), "accepted before close")
        queue.closeForDrain()
        assertFalse(queue.offer(10), "late value rejected")
        val consumed = ArrayList<Int>()
        while (true) consumed.add(queue.take() ?: break)
        assertEquals((0 until 10).toList(), consumed)
    }

    @Test
    fun abortDropsPendingValues() {
        val queue = DrainableAudioQueue<Int>(8)
        queue.open()
        queue.offer(1)
        queue.offer(2)
        queue.abort()
        assertNull(queue.take(), "aborted queue is empty")
        assertTrue(queue.isAborted())
    }

    @Test
    fun boundsBacklogAndReopensWithoutLeakingTheLastSession() {
        val queue = DrainableAudioQueue<Int>(3)
        queue.open()
        for (i in 0 until 5) queue.offer(i)
        queue.closeForDrain()
        assertEquals(2L, queue.droppedCount())
        assertEquals(listOf(2, 3, 4), listOf(queue.take(), queue.take(), queue.take()))
        assertNull(queue.take(), "drained queue closes")
        queue.open()
        queue.offer(7)
        queue.closeForDrain()
        assertEquals(7, queue.take())
        assertNull(queue.take())
    }

    private class HopBuffer(private val hop: Int) {
        private var pending = ByteArray(0)
        fun process(input: ByteArray): ByteArray {
            val all = pending + input
            val complete = all.size / (hop * 2) * hop * 2
            pending = all.copyOfRange(complete, all.size)
            return all.copyOf(complete)
        }
    }

    @Test
    fun pcmAdapterKeepsEverySampleAcrossVariableProcessorOutput() {
        val expected = ShortArray(3200) { (it - 1600).toShort() }
        val processor = HopBuffer(128)
        val outputs = (0 until expected.size step 800).map { offset ->
            Pcm16StreamAdapter.process(expected.copyOfRange(offset, offset + 800), 800) { processor.process(it) }
        }
        assertEquals(768, outputs[0].size)
        assertEquals(896, outputs[3].size)
        assertContentEquals(expected, outputs.reduce { a, b -> a + b })
        assertContentEquals(shortArrayOf(10, 20), Pcm16StreamAdapter.process(shortArrayOf(10, 20, 30, 40), 2) { it })
        assertFailsWith<IllegalStateException> { Pcm16StreamAdapter.process(shortArrayOf(1), 1) { null } }
        assertFailsWith<IllegalStateException> { Pcm16StreamAdapter.process(shortArrayOf(1), 1) { ByteArray(1) } }
        assertFailsWith<IllegalArgumentException> { Pcm16StreamAdapter.process(shortArrayOf(1), 2) { it } }
    }
}
