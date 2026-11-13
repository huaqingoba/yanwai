package dev.jev.wechatmood.hook

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Host Views require Android/Xposed; protect their integration with the tested presentation policy. */
class AnalysisCardAnimationWiringTest {
    private fun source(name: String) = File("src/main/java/dev/jev/wechatmood/hook/$name.kt").readText()

    @Test fun `cache decision feeds the real renderer and updates never reattach`() {
        val show = source("BubbleDecorator").substringAfter("fun show(").substringBefore("private fun attach(")
        assertTrue(show.contains("state.presentation.bind(content, cacheHit = cached != null)"))
        assertTrue(show.contains("state.view.render(content,"))
        assertTrue(show.indexOf("SignalAnalyzer.cachedMood(message)") < show.indexOf("state = attach("))
        assertFalse(show.contains("removeAllViews"))
        assertFalse(show.contains("addView("))
        assertFalse(show.contains("bringToFront"))
        assertFalse(show.contains("visibility ="))
        val bind = source("MessageSniffer").substringAfter("override fun beforeHookedMethod(").substringBefore("override fun afterHookedMethod(")
        assertTrue(bind.contains("BubbleDecorator.prepareForBind(row)"))
        assertFalse(bind.contains("BubbleDecorator.clear(row)"))
    }

    @Test fun `renderer uses decelerate interpolation and isolates content alpha from surface`() {
        val view = source("FrostedAnalysisView")
        assertTrue(view.contains("AnalysisCardMotion.Curve.DECELERATE -> DecelerateInterpolator()"))
        assertFalse(view.contains("OvershootInterpolator"))
        assertTrue(view.contains(".setDuration(AnalysisCardMotion.ENTER_MS)"))
        assertTrue(view.contains("fadeContent(0f, AnalysisCardMotion.CONTENT_OUT_MS)"))
        assertTrue(view.contains("fadeContent(1f, AnalysisCardMotion.CONTENT_IN_MS)"))
        assertTrue(view.contains("canvas.saveLayerAlpha"))
        assertTrue(view.contains("maxOf(loadingHeight, displayedHeight)"))
        assertTrue(view.contains("AnalysisCardMotion.heightAt(startHeight, natural,"))
        val rings = view.substringAfter("if (change == AnalysisCardMotion.Change.RINGS").substringBefore("cancelContentAnimation()")
        assertTrue(rings.contains("span.update(emotion)"))
        assertTrue(rings.contains("invalidate()"))
        assertFalse(rings.contains("text ="))
    }

    @Test fun `close waits for removal and never snaps a partial entrance to fully visible`() {
        val close = source("FrostedAnalysisView").substringAfter("fun close(").substringBefore("fun refreshBackdrop()")
        assertFalse(close.contains("finishAnimations()"))
        assertTrue(close.contains(".setDuration(AnalysisCardMotion.CLOSE_MS)"))
        assertTrue(close.contains(".withEndAction(after)"))
        val decorator = source("BubbleDecorator")
        assertTrue(decorator.contains("state.view.close { if (cards[row] === state && state.closing) clear(row) }"))
    }
}
