package dev.jev.wechatmood.hook

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Source contracts for host-only drawing, which cannot run against the JVM Android stubs. */
class AnalysisCardSurfaceTest {
    private fun source(name: String) = File("src/main/java/dev/jev/wechatmood/hook/$name.kt").readText()

    @Test fun `default frosted gradient is 88 percent and light never uses pure white`() {
        val colors = AnalysisCardBackdrop.colors(hasSnapshot = true)
        assertEquals(0xE02B2D34.toInt() to 0xE0202229.toInt(), colors)
        assertEquals(0xE0EEF1F5.toInt() to 0xE0E3E7ED.toInt(),
            AnalysisCardBackdrop.colors(hasSnapshot = true, dark = false))
        for (dark in listOf(false, true)) for (opacity in 60..100) {
            val (top, bottom) = AnalysisCardBackdrop.colors(true, opacity, dark)
            assertNotEquals(0xFFFFFF, top and 0xFFFFFF)
            assertNotEquals(0xFFFFFF, bottom and 0xFFFFFF)
        }
    }

    @Test fun `missing or failed snapshot selects an opaque palette fallback at every slider value`() {
        for (opacity in 60..100) {
            assertEquals(0xFF2B2D34.toInt() to 0xFF202229.toInt(),
                AnalysisCardBackdrop.colors(false, opacity, dark = true))
            assertEquals(0xFFEEF1F5.toInt() to 0xFFE3E7ED.toInt(),
                AnalysisCardBackdrop.colors(false, opacity, dark = false))
        }
        val view = source("FrostedAnalysisView")
        val capture = view.substringAfter("private fun capture(): Snapshot?").substringBefore("private fun stopListening()")
        assertTrue(capture.contains("MoodLog.e(\"CARD_SNAPSHOT_FAILED"))
        assertFalse(capture.contains("setText"))
        assertFalse(capture.contains("Toast"))
    }

    @Test fun `renderer draws cached blur under the tested gradient and rounded border`() {
        // The surface is drawn in draw(), before onDraw() fades only the text and rings.
        val draw = source("FrostedAnalysisView").substringAfter("override fun draw(canvas: Canvas)")
            .substringBefore("private fun acceptSnapshot(")
        assertTrue(draw.contains("canvas.drawRect(bounds, paint)"))
        assertTrue(draw.contains("AnalysisCardBackdrop.colors(hasSnapshot = crop != null, opacity = surface.opacity, dark = dark)"))
        assertTrue(draw.contains("top, bottom, Shader.TileMode.CLAMP"))
        assertFalse(draw.contains("drawColor("))
        assertTrue(draw.indexOf("canvas.clipPath(clip)") < draw.indexOf("canvas.drawBitmap("))
        assertTrue(draw.indexOf("canvas.drawBitmap(") < draw.indexOf("paint.shader = LinearGradient("))
        assertEquals(1, Regex("canvas\\.drawRect\\(").findAll(draw).count())
        assertTrue(draw.indexOf("canvas.drawRect(bounds, paint)") < draw.indexOf("bounds.inset("))
        assertTrue(draw.indexOf("bounds.inset(") < draw.indexOf("canvas.drawRoundRect("))
        assertTrue(draw.contains("bounds.set(0f, 0f, width.toFloat(), height.toFloat().coerceAtLeast(1f))"))
        assertTrue(draw.contains("LinearGradient(0f, 0f, 0f, bounds.height(),"))
        assertTrue(draw.contains("clip.addRoundRect(bounds, radius, radius, Path.Direction.CW)"))
        assertTrue(draw.contains("paint.color = if (dark) 0x32E3E8F0 else 0x322B2D34"))
    }

    @Test fun `every gradient fill resets alpha left by the translucent border`() {
        val draw = source("FrostedAnalysisView").substringAfter("override fun draw(canvas: Canvas)")
        val reset = draw.indexOf("paint.alpha = 255")
        val gradient = draw.indexOf("paint.shader = LinearGradient(")
        val fill = draw.indexOf("canvas.drawRect(bounds, paint)")
        val border = draw.indexOf("paint.color = if (dark) 0x32E3E8F0")
        assertTrue("Each draw must reset the shared paint before the gradient fill",
            reset >= 0 && gradient > reset && fill > gradient && border > fill)
    }

    @Test fun `snapshot capture is excluded from routine drawing and center crop uses one scale`() {
        val view = source("FrostedAnalysisView")
        val draw = view.substringAfter("override fun draw(canvas: Canvas)").substringBefore("private fun acceptSnapshot(")
        assertTrue(draw.contains("if (capturing) return"))
        assertTrue(draw.contains("canvas.drawBitmap(requireNotNull(image), snapshotMatrix, paint)"))
        assertTrue(draw.contains("snapshotMatrix.setScale(transform.scale, transform.scale)"))
        assertTrue(draw.contains("snapshotMatrix.postTranslate(transform.translateX, transform.translateY)"))
        assertTrue(draw.contains("AnalysisCardBackdrop.centerCrop(it.width, it.height, bounds.width(), bounds.height())"))
        listOf("getLocationOnScreen", "request(", "capture(", "root.draw(").forEach { assertFalse(draw.contains(it)) }
        val preDraw = view.substringAfter("override fun onPreDraw()").substringBefore("private fun capture()")
        assertTrue(preDraw.contains("it.snapshotState.needsCapture"))
        assertTrue(preDraw.contains("if (ready.isEmpty()) return true"))
        assertTrue(preDraw.contains("if (pending.isEmpty()) stopListening()"))
        assertTrue(view.contains("override fun doFrame(frameTimeNanos: Long)"))
        assertTrue(view.contains("choreographer.removeFrameCallback(this)"))
        assertFalse(view.contains("SystemClock"))
        assertFalse(view.contains("now - lastCapture"))
        assertFalse(view.contains("RenderEffect"))
        assertTrue(view.contains("AnalysisCardBackdrop.soften(pixels, w, h)"))
        val retry = source("BubbleDecorator").substringAfter("setOnClickListener {").substringBefore("var branch:")
        assertTrue(retry.indexOf("refreshBackdrop()") < retry.indexOf("SignalAnalyzer.reanalyze(message)"))
        val menu = source("MessageSniffer").substringAfter("fun analyzeMessage(").substringBefore("fun replyTargetForView(")
        assertTrue(menu.contains("if (force) {"))
        assertTrue(menu.contains("BubbleDecorator.refreshBackdrop(input.key)"))
    }

    @Test fun `entrance and cancellation settle at full view alpha`() {
        val view = source("FrostedAnalysisView")
        val layout = view.substringAfter("override fun onLayout(").substringBefore("override fun onDraw(")
        assertTrue(layout.contains("animate().alpha(1f)"))
        val finish = view.substringAfter("fun finishAnimations()").substringBefore("fun close(")
        assertTrue(finish.contains("alpha = 1f"))
        assertTrue(finish.contains("contentAlpha = 1f"))
        val draw = view.substringAfter("override fun draw(canvas: Canvas)").substringBefore("private fun acceptSnapshot(")
        assertFalse(draw.contains("saveLayerAlpha"))
        assertFalse(draw.contains("contentAlpha"))
    }

    @Test fun `reply card and loading surfaces are opaque in both themes`() {
        val theme = source("ReplyTheme")
        listOf("surface", "card", "soft").forEach { name ->
            val palette = Regex("val $name = color\\(\"(#[0-9A-Fa-f]+)\", \"(#[0-9A-Fa-f]+)\"\\)").find(theme)
            assertNotNull("Missing $name theme colors", palette)
            requireNotNull(palette).groupValues.drop(1).forEach { color ->
                val argb = if (color.length == 7) 0xFF000000L or color.drop(1).toLong(16)
                    else color.drop(1).toLong(16)
                assertEquals("$name must be opaque: $color", 0xFFL, argb ushr 24)
            }
        }
    }
}
