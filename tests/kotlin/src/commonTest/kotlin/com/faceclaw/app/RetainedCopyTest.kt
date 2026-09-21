package com.faceclaw.app

import kotlin.random.Random
import kotlin.test.*

class RetainedCopyTest {
    private fun pixel(packed: ByteArray, width: Int, x: Int, y: Int): Int {
        val value = packed[y * ((width + 1) shr 1) + (x shr 1)].toInt() and 255
        return if (x and 1 == 0) value shr 4 else value and 15
    }

    private fun set(packed: ByteArray, width: Int, x: Int, y: Int, value: Int) {
        val at = y * ((width + 1) shr 1) + (x shr 1)
        val old = packed[at].toInt() and 255
        packed[at] =
            if (x and 1 == 0) (((value and 15) shl 4) or (old and 15)).toByte()
            else ((old and 0xf0) or (value and 15)).toByte()
    }

    private fun expectedCopy(
        input: ByteArray,
        width: Int,
        copy: SurfaceCompositor.ScreenCopy,
    ): ByteArray {
        val output = input.copyOf()
        val source = IntArray(copy.width * copy.height)
        for (y in 0 until copy.height) for (x in 0 until copy.width) {
            source[y * copy.width + x] = pixel(input, width, copy.sourceX + x, copy.sourceY + y)
        }
        for (y in 0 until copy.height) for (x in 0 until copy.width) {
            set(output, width, copy.destinationX + x, copy.destinationY + y, source[y * copy.width + x])
        }
        return output
    }

    @Test
    fun overlappingCopiesMatchReference() {
        val random = Random(9017)
        repeat(1000) { trial ->
            val width = random.nextInt(1, 81)
            val height = random.nextInt(1, 41)
            val copyWidth = random.nextInt(1, width + 1)
            val copyHeight = random.nextInt(1, height + 1)
            val copy =
                SurfaceCompositor.ScreenCopy(
                    random.nextInt(width - copyWidth + 1),
                    random.nextInt(height - copyHeight + 1),
                    copyWidth,
                    copyHeight,
                    random.nextInt(width - copyWidth + 1),
                    random.nextInt(height - copyHeight + 1),
                )
            val packed = random.nextBytes(((width + 1) shr 1) * height)
            val actual = packed.copyOf()
            BleImageOptimizer.applyRetainedCopy(actual, width, copy)
            assertContentEquals(expectedCopy(packed, width, copy), actual, "trial $trial")
        }
    }

    @Test
    fun copyPlanBeatsRasterAndComposesWithTextureDraws() {
        val random = Random(9018)
        val width = 96
        val height = 32
        val previous = random.nextBytes((width / 2) * height)
        val next = ByteArray(previous.size)
        for (y in 0 until height) {
            for (x in 0 until width - 12) set(next, width, x, y, pixel(previous, width, x + 12, y))
            for (x in width - 12 until width) set(next, width, x, y, random.nextInt(16))
        }
        val copy = SurfaceCompositor.ScreenCopy(12, 0, width - 12, height, 0, 0)
        val plan =
            assertNotNull(
                BleImageOptimizer.buildRetainedCopyPayload(
                    previous,
                    next,
                    width,
                    height,
                    arrayOf(copy),
                    7,
                    6,
                )
            )
        assertEquals(8, plan.payload[0].toInt())
        assertEquals(1, plan.copyCount)
        assertTrue(plan.repairRectCount >= 1)
        val firstLength = (plan.payload[2].toInt() and 255) or ((plan.payload[3].toInt() and 255) shl 8)
        assertEquals(17, firstLength)
        assertEquals(9, plan.payload[4].toInt())
        val raster = BleImageOptimizer.buildIncrementalImagePayload(previous, next, width, height, 7)
        val rasterBytes = raster?.payload?.size ?: BleImageOptimizer.maybeCompress(next, width, height).size
        assertTrue(plan.payload.size < rasterBytes)

        val imageId =
            ImageAtlas.ensure(
                "retained-copy-fork",
                2,
                2,
                ArrayByteReader(byteArrayOf(0, 64, 128.toByte(), 255.toByte())),
            )
        val live = TextureCacheState()
        val imageOffset = live.ensureImage(imageId, assertNotNull(ImageAtlas.get(imageId)))
        assertTrue(imageOffset >= 0)
        assertTrue(live.hasPendingUploads())
        val speculative = live.fork()
        speculative.drainUploadPayloads(3600)
        assertTrue(live.hasPendingUploads())
        live.adopt(speculative)
        assertFalse(live.hasPendingUploads())
        assertEquals(imageOffset, live.ensureImage(imageId, assertNotNull(ImageAtlas.get(imageId))))
        val reset = live.fork()
        reset.reset()
        assertTrue(live.usedBytes() > 0)
        assertNotEquals(live.generation(), reset.generation())

        val glyphGray = ByteArray(4 * 8) { -1 }
        val font = GlyphAtlas.ensureGray("hybrid-copy-font", 'A'.code, 4, 8, ArrayByteReader(glyphGray))
        val hybridPrevious = ByteArray((width / 2) * height)
        for (x in intArrayOf(12, 28, 44, 60, 76)) {
            for (y in 8 until 16) for (px in x until x + 4) set(hybridPrevious, width, px, y, 15)
        }
        val hybridCopy = SurfaceCompositor.ScreenCopy(8, 0, width - 8, height, 0, 0)
        val hybridNext = expectedCopy(hybridPrevious, width, hybridCopy)
        val nextXs = intArrayOf(4, 20, 36, 52, 68, 88)
        for (y in 8 until 16) for (x in 88 until 92) set(hybridNext, width, x, y, 15)
        val draws = nextXs.map { SurfaceCompositor.ScreenDraw.glyph(font, 'A'.code, it, 8, 255) }.toTypedArray()
        val hybridCopyPlan =
            assertNotNull(
                BleImageOptimizer.buildRetainedCopyPayload(
                    hybridPrevious,
                    hybridNext,
                    width,
                    height,
                    arrayOf(hybridCopy),
                    11,
                    6,
                )
            )
        val hybrid =
            assertNotNull(
                TexturePlanner.planAfterCopies(
                    hybridCopyPlan,
                    hybridNext,
                    width,
                    height,
                    draws,
                    TextureCacheState(),
                    11,
                    true,
                    6,
                    testPlatform(),
                )
            )
        val ordinary =
            assertNotNull(
                TexturePlanner.plan(
                    hybridPrevious,
                    hybridNext,
                    width,
                    height,
                    draws,
                    TextureCacheState(),
                    11,
                    true,
                    6,
                    testPlatform(),
                )
            )
        assertEquals(1, hybrid.drawnGlyphs)
        assertTrue(hybrid.drawnGlyphs < ordinary.drawnGlyphs)
        assertEquals(0, hybrid.rectCount)
        val hybridFirstLength =
            (hybrid.payload[2].toInt() and 255) or ((hybrid.payload[3].toInt() and 255) shl 8)
        assertEquals(17, hybridFirstLength)
        assertEquals(9, hybrid.payload[4].toInt())
    }

    @Test
    fun surfaceCopyHintsTranslateToScreenCoordinates() {
        val compositor = SurfaceCompositor()
        compositor.configureScreen(20, 10)
        compositor.configureSurface("apk", 2, 3, 10, 4, 0, SurfaceCompositor.TRANSPARENCY_OPAQUE)
        val gray = ByteArray(40)
        compositor.applyDamageAndCompositePacked(
            "apk",
            ArrayByteReader(gray),
            null,
            "first",
            null,
            null,
            ByteArray(100),
        )
        gray[0] = -1
        val translated =
            compositor.applyDamageAndCompositePacked(
                "apk",
                ArrayByteReader(gray),
                null,
                "second",
                null,
                intArrayOf(2, 0, 8, 4, 0, 0),
                ByteArray(100),
            )
        val copy = assertNotNull(translated.composite.copies.singleOrNull())
        assertEquals(4, copy.sourceX)
        assertEquals(3, copy.sourceY)
        assertEquals(2, copy.destinationX)
        assertEquals(3, copy.destinationY)
        assertEquals(8, copy.width)
        assertEquals(4, copy.height)
    }
}
