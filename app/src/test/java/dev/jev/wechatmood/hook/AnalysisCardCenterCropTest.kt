package dev.jev.wechatmood.hook

import org.junit.Assert.*
import org.junit.Test

class AnalysisCardCenterCropTest {
    @Test fun `two to one source is cropped symmetrically to a square without stretching or gaps`() {
        val crop = requireNotNull(AnalysisCardBackdrop.centerCrop(200, 100, 100f, 100f))
        assertEquals(1f, crop.scale, 0f)
        assertEquals(-50f, crop.translateX, 0f)
        assertEquals(0f, crop.translateY, 0f)
        // Source x=50..150 maps to target x=0..100; y=0..100 stays unchanged.
        assertEquals(0f, 50f * crop.scale + crop.translateX, 0f)
        assertEquals(100f, 150f * crop.scale + crop.translateX, 0f)
        assertEquals(100f, 100f * crop.scale + crop.translateY, 0f)
    }

    @Test fun `wide tall and downsampled images fill the entire target through height changes`() {
        for ((sw, sh) in listOf(200 to 100, 100 to 200, 1 to 1, 83 to 179)) {
            for ((width, height) in listOf(100f to 100f, 300f to 1f, 300f to 75f, 300f to 170f)) {
                val crop = requireNotNull(AnalysisCardBackdrop.centerCrop(sw, sh, width, height))
                assertTrue(crop.translateX <= 0.0001f)
                assertTrue(crop.translateY <= 0.0001f)
                assertTrue(crop.translateX + sw * crop.scale >= width - 0.0001f)
                assertTrue(crop.translateY + sh * crop.scale >= height - 0.0001f)
                assertEquals(-crop.translateX, crop.translateX + sw * crop.scale - width, 0.0001f)
                assertEquals(-crop.translateY, crop.translateY + sh * crop.scale - height, 0.0001f)
            }
        }
    }

    @Test fun `unmeasured or invalid dimensions cannot produce a usable snapshot transform`() {
        assertNull(AnalysisCardBackdrop.centerCrop(0, 100, 100f, 100f))
        assertNull(AnalysisCardBackdrop.centerCrop(100, 0, 100f, 100f))
        assertNull(AnalysisCardBackdrop.centerCrop(100, 100, 0f, 100f))
        assertNull(AnalysisCardBackdrop.centerCrop(100, 100, 100f, 0f))
    }
}
