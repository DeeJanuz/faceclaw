package com.faceclaw.app

import kotlin.random.Random
import kotlin.test.*

class SurfaceCompositorRegressionTest {
    private fun draws(glyph: Int): ArrayByteReader {
        val bytes = ByteSink()
        bytes.write(0)
        bytes.write(1)
        bytes.write(0)
        repeat(4) { bytes.write(glyph ushr (it * 8)) }
        repeat(4) { bytes.write(0) }
        bytes.write(255)
        bytes.write(1)
        repeat(4) { bytes.write((glyph + 1) ushr (it * 8)) }
        repeat(4) { bytes.write(0) }
        return ArrayByteReader(bytes.toByteArray())
    }

    private fun assertPixels(frame: SurfaceCompositor.Composite?, vararg expected: Int) {
        val actual = assertNotNull(frame).gray.map { it.toInt() and 255 }
        assertEquals(expected.toList(), actual)
    }

    @Test
    fun zeroUnderlayConcealsRasterIdentitiesAndFingerprint() {
        val compositor = SurfaceCompositor()
        compositor.configureScreen(3, 1)
        compositor.configureSurface("private-app", 0, 0, 3, 1, 0, SurfaceCompositor.TRANSPARENCY_OPAQUE)
        compositor.configureSurface("notification", 0, 0, 3, 1, 1, SurfaceCompositor.TRANSPARENCY_COLOR_KEY)
        compositor.configureSurface("lock", 0, 0, 3, 1, 1000, SurfaceCompositor.TRANSPARENCY_COLOR_KEY)
        compositor.setSurfaceVisible("lock", false)
        compositor.applyAndComposite(
            "private-app",
            ArrayByteReader(byteArrayOf(180.toByte(), 80, 255.toByte())),
            0,
            0,
            3,
            1,
            "secret-one",
            draws(900),
        )
        compositor.applyAndComposite(
            "notification",
            ArrayByteReader(byteArrayOf(0, 200.toByte(), 0)),
            0,
            0,
            3,
            1,
            "preview",
        )
        compositor.setUnderlayDim(1, 0)
        val hidden = compositor.composite()
        assertPixels(hidden, 0, 200, 0)
        assertPixels(compositor.previewComposite(), 0, 200, 0)
        assertTrue(hidden.draws!!.isEmpty())
        assertFalse(hidden.fingerprint.contains("secret-one"))

        val updated =
            compositor.applyAndComposite(
                "private-app",
                ArrayByteReader(byteArrayOf(90, 40, 100)),
                0,
                0,
                3,
                1,
                "secret-two",
                draws(950),
            )
        assertPixels(updated, 0, 200, 0)
        assertEquals(hidden.fingerprint, updated.fingerprint)

        compositor.applyAndComposite(
            "lock",
            ArrayByteReader(byteArrayOf(0, 0, 222.toByte())),
            0,
            0,
            3,
            1,
            "lock",
        )
        compositor.setSurfaceVisible("lock", true)
        assertPixels(compositor.composite(), 0, 200, 222)
        compositor.setSurfaceVisible("lock", false)
        compositor.setUnderlayDim(1, 128)
        val dimmed = compositor.composite()
        assertPixels(dimmed, 45, 200, 50)
        assertEquals(1, dimmed.draws!!.size)
        assertEquals(128, dimmed.draws[0].value)

        compositor.setUnderlayDim(1, 256)
        val restored = compositor.composite()
        assertPixels(restored, 90, 200, 100)
        assertEquals(2, restored.draws!!.size)
        assertEquals(950, restored.draws[0].encoding)
    }

    @Test
    fun packedDamageMatchesFullPackingAndFailedTargetsDoNotMutateState() {
        val compositor = SurfaceCompositor()
        compositor.configureScreen(4, 2)
        compositor.configureSurface("apk", 0, 0, 4, 2, 0, SurfaceCompositor.TRANSPARENCY_OPAQUE)
        val initial = byteArrayOf(0, 16, 32, 48, 64, 80, 96, 112)
        val target = ByteArray(4)
        val first =
            compositor.applyDamageAndCompositePacked(
                "apk",
                ArrayByteReader(initial),
                intArrayOf(0, 0, 4, 2),
                "one",
                null,
                target,
            )
        assertTrue(first.composite.gray.isEmpty())
        assertSame(target, first.packed)
        val changed = byteArrayOf(0, 16, 32, 48, 64, 240.toByte(), 96, 112)
        val second =
            compositor.applyDamageAndCompositePacked(
                "apk",
                ArrayByteReader(changed),
                intArrayOf(1, 1, 1, 1),
                "two",
                null,
                ByteArray(4),
            )
        assertContentEquals(BmpUtil.pack4bppFromGray8(changed, 4, 2), second.packed)

        val windowed = SurfaceCompositor()
        windowed.configureScreen(4, 3)
        windowed.configureSurface("apk", 0, 1, 4, 2, 0, SurfaceCompositor.TRANSPARENCY_OPAQUE)
        windowed.applyAndComposite("apk", ArrayByteReader(ByteArray(8)), 0, 0, 4, 2, "empty")
        assertEquals(6, windowed.packedFrameSize())
        val white = ByteArray(8) { -1 }
        assertFailsWith<IllegalArgumentException> {
            windowed.applyDamageAndCompositePacked(
                "apk",
                ArrayByteReader(white),
                null,
                "invalid",
                null,
                ByteArray(4),
            )
        }
        assertPixels(windowed.previewComposite(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        val valid =
            windowed.applyDamageAndCompositePacked(
                "apk",
                ArrayByteReader(white),
                null,
                "valid",
                null,
                ByteArray(windowed.packedFrameSize()),
            )
        assertTrue(valid.composite.damage.isNotEmpty())
        assertPixels(windowed.previewComposite(), 0, 0, 0, 0, 255, 255, 255, 255, 255, 255, 255, 255)
    }

    @Test
    fun sparseDamageNeverDivergesFromFullPacking() {
        val width = 96
        val height = 48
        val compositor = SurfaceCompositor()
        compositor.configureScreen(width, height)
        compositor.configureSurface("outline", 0, 0, width, height, 0, SurfaceCompositor.TRANSPARENCY_OPAQUE)
        val pixels = ByteArray(width * height)
        compositor.applyAndComposite("outline", ArrayByteReader(pixels), 0, 0, width, height, "empty")
        pixels[0] = -1
        pixels[pixels.lastIndex] = -1
        val corners =
            compositor.applyDamageAndCompositePacked(
                "outline",
                ArrayByteReader(pixels),
                null,
                "corners",
                null,
                ByteArray(compositor.packedFrameSize()),
            )
        var area = 0
        for (index in corners.composite.damage.indices step 4) {
            area += corners.composite.damage[index + 2] * corners.composite.damage[index + 3]
        }
        assertTrue(area < width * height)
        assertContentEquals(BmpUtil.pack4bppFromGray8(pixels, width, height), corners.packed)

        val random = Random(73)
        repeat(40) { frame ->
            repeat(12) { pixels[random.nextInt(pixels.size)] = random.nextInt(256).toByte() }
            val update =
                compositor.applyDamageAndCompositePacked(
                    "outline",
                    ArrayByteReader(pixels),
                    null,
                    "random-$frame",
                    null,
                    ByteArray(compositor.packedFrameSize()),
                )
            assertContentEquals(BmpUtil.pack4bppFromGray8(pixels, width, height), update.packed)
        }
    }
}
