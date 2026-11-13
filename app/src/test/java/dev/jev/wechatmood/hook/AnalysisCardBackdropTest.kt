package dev.jev.wechatmood.hook

import org.junit.Assert.*
import org.junit.Test

class AnalysisCardBackdropTest {
    @Test fun `blur suppresses fine text strokes in both directions`() {
        val width = 63; val height = 63
        for (vertical in listOf(false, true)) {
            val pixels = IntArray(width * height) { index ->
                val coordinate = if (vertical) index % width else index / width
                if (coordinate % 2 == 0) -1 else 0xFF000000.toInt()
            }
            AnalysisCardBackdrop.soften(pixels, width, height)
            val interior = (12 until height - 12).flatMap { y ->
                (12 until width - 12).map { x -> pixels[y * width + x] and 255 }
            }
            assertTrue("Fine strokes must lose at least 95% of their contrast",
                interior.max() - interior.min() <= 12)
            assertTrue(pixels.all { it ushr 24 == 255 })
        }
    }

    @Test fun `blur preserves broad color blocks for the glass texture`() {
        val width = 63; val height = 31
        val red = 0xFFC06040.toInt(); val blue = 0xFF4060C0.toInt()
        val pixels = IntArray(width * height) { if (it % width < width / 2) red else blue }
        AnalysisCardBackdrop.soften(pixels, width, height)
        assertEquals(red, pixels[15 * width + 10])
        assertEquals(blue, pixels[15 * width + 50])
        val edge = pixels[15 * width + 31]
        assertTrue((edge and 255) in 65..191)
        assertTrue(((edge ushr 16) and 255) in 65..191)
    }

    @Test fun `tiny downsampled images are safe and uniform colors remain uniform`() {
        for ((width, height) in listOf(1 to 1, 1 to 7, 7 to 1)) {
            val pixels = IntArray(width * height) { 0xE62B2D34.toInt() }
            AnalysisCardBackdrop.soften(pixels, width, height)
            assertTrue(pixels.all { it == 0xE62B2D34.toInt() })
        }
    }
}
