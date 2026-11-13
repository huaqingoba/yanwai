package dev.jev.wechatmood.hook

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AnalysisCardOpacityTest {
    @Test fun `100 percent skips the backdrop and fills an opaque gradient in either theme`() {
        for (dark in listOf(false, true)) {
            for (opacity in 60..99) assertTrue(AnalysisCardBackdrop.drawsBackdrop(true, opacity))
            assertFalse(AnalysisCardBackdrop.drawsBackdrop(true, 100))
            assertFalse(AnalysisCardBackdrop.drawsBackdrop(false, 96))
            val colors = AnalysisCardBackdrop.colors(true, 100, dark)
            assertEquals(255, colors.first ushr 24)
            assertEquals(255, colors.second ushr 24)
            val fallback = AnalysisCardBackdrop.colors(false, dark = dark)
            assertEquals(fallback.first and 0xFFFFFF, colors.first and 0xFFFFFF)
            assertEquals(fallback.second and 0xFFFFFF, colors.second and 0xFFFFFF)
        }
        val draw = source("hook/FrostedAnalysisView").substringAfter("override fun draw(canvas: Canvas)")
            .substringBefore("private fun acceptSnapshot(")
        val backdrop = draw.substringAfter("if (AnalysisCardBackdrop.drawsBackdrop(").substringBefore("val (top, bottom)")
        assertTrue(backdrop.contains("canvas.drawBitmap("))
        assertTrue(backdrop.contains("canvas.drawColor("))
        assertTrue(draw.indexOf("canvas.drawRoundRect(") > draw.indexOf("val (top, bottom)"))
    }

    @Test fun `dragging updates tint while preserving the consumed snapshot request`() {
        val surface = AnalysisCardSurfaceState()
        val snapshot = surface.snapshotState
        snapshot.attach()
        assertTrue(snapshot.consume())
        for (opacity in (60..100) + (100 downTo 60)) {
            val changed = surface.opacity != opacity
            assertEquals(changed, surface.updateOpacity(opacity))
            assertEquals(opacity, surface.opacity)
            assertSame(snapshot, surface.snapshotState)
            assertFalse(snapshot.needsCapture)
            assertFalse(snapshot.consume())
        }
        assertTrue(surface.updateOpacity(-1))
        assertEquals(88, surface.opacity)
        assertFalse(snapshot.needsCapture)
        snapshot.detach(); snapshot.attach()
        assertFalse(snapshot.consume())
    }

    @Test fun `slider pushes alpha to visible cards without rendering content or requesting a new frame`() {
        val activity = source("MainActivity").substringAfter("binding.cardOpacity.setOnSeekBarChangeListener(")
            .substringBefore("listOf(binding.showIntent")
        assertTrue(activity.contains("override fun onProgressChanged("))
        assertTrue(activity.contains("if (!fromUser) return"))
        assertTrue(activity.contains("putInt(CardOpacity.KEY, CardOpacity.normalize(progress))"))
        val provider = source("core/SettingsProvider")
        assertTrue(provider.contains("putInt(CardOpacity.KEY, CardOpacity.load { prefs.all[it] })"))
        assertTrue(provider.contains("ModulePrefs.applyLocalSettings(config)"))
        assertTrue(source("core/ModulePrefs").contains("SettingsSync.decode(bundle)?.let(bridge::receive)"))
        assertTrue(source("core/SettingsSync").contains("CardOpacity.load { bundle.getInt(it, CardOpacity.DEFAULT) }"))
        val callback = source("core/ModulePrefs").substringAfter("onCardOpacityChanged = {").substringBefore("private val bridgeWorker")
        assertTrue(callback.contains("android.os.Looper.getMainLooper()"))
        assertTrue(callback.contains("BubbleDecorator.updateOpacity("))
        val preview = source("hook/BubbleDecorator").substringAfter("fun updateOpacity(opacity: Int,")
            .substringBefore("fun prepareForBind(")
        assertTrue(preview.contains("cards.values.forEach { it.view.updateOpacity(opacity, palette) }"))
        val update = source("hook/FrostedAnalysisView").substringAfter("fun updateOpacity(opacity: Int,")
            .substringBefore("private fun updateTextPalette()")
        assertTrue(update.contains("surface.updateOpacity(opacity)"))
        assertTrue(update.contains("surface.updatePalette(palette)"))
        assertTrue(update.contains("if (opacityChanged || paletteChanged) invalidate()"))
        assertTrue(callback.contains("session.current?.cardPalette ?: CardPalette.DEFAULT"))
        for (block in listOf(preview, update, callback)) {
            for (forbidden in listOf("refreshBackdrop(", "snapshotState.", "request(this)", ".render(", "MessageSniffer.refresh(", "requestLayout()")) {
                assertFalse("Preview must not invoke $forbidden", block.contains(forbidden))
            }
        }
    }

    private fun source(path: String) = File("src/main/java/dev/jev/wechatmood/$path.kt").readText()
}
