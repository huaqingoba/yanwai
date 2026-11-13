package dev.jev.wechatmood.hook

import dev.jev.wechatmood.core.CardDisplaySettings
import dev.jev.wechatmood.core.EmotionIndicator
import dev.jev.wechatmood.core.Mood
import org.junit.Assert.*
import org.junit.Test

class AnalysisCardMotionTest {
    private val mood = Mood("平静", 0.8, 0, "", "意图解析：说明安排\n可能在意：时间\n情绪倾向：平和")
    private val lines = AnalysisCardContent.lines(mood, CardDisplaySettings())
    private fun result(percent: Int = 80) = AnalysisCardMotion.Content(lines, listOf(
        EmotionIndicator("平静", percent, EmotionIndicator.colors.getValue("平静")),
        EmotionIndicator("焦虑", 100 - percent, EmotionIndicator.colors.getValue("焦虑")),
    ))
    private fun loading() = AnalysisCardMotion.Content(
        AnalysisCardContent.lines(mood, CardDisplaySettings(), loading = true), result().emotions, loading = true)

    private class FakeCard {
        private val presentation = AnalysisCardMotion.Presentation()
        var entrances = 0
        val changes = mutableListOf<AnalysisCardMotion.Change>()
        fun show(content: AnalysisCardMotion.Content, cacheHit: Boolean = false) {
            val change = presentation.bind(content, cacheHit)
            if (change == AnalysisCardMotion.Change.ENTER) entrances++
            changes += change
        }
    }

    @Test fun `motion constants have short decelerating entry and no bounce`() {
        assertEquals(180L, AnalysisCardMotion.ENTER_MS)
        assertEquals(120L, AnalysisCardMotion.BUBBLE_FADE_MS)
        assertEquals(120L, AnalysisCardMotion.CONTENT_OUT_MS)
        assertEquals(150L, AnalysisCardMotion.CONTENT_IN_MS)
        assertEquals(180L, AnalysisCardMotion.HEIGHT_MS)
        assertEquals(120L, AnalysisCardMotion.CLOSE_MS)
        assertEquals(0.96f, AnalysisCardMotion.ENTER_SCALE, 0f)
        assertEquals(0.98f, AnalysisCardMotion.CLOSE_SCALE, 0f)
        assertEquals(8, AnalysisCardMotion.ENTER_TRANSLATION_DP)
        assertEquals(AnalysisCardMotion.Curve.DECELERATE, AnalysisCardMotion.curve)
        assertEquals(300, AnalysisCardMotion.WIDTH_DP)
        assertEquals(12, AnalysisCardMotion.HORIZONTAL_PADDING_DP)
        assertEquals(28, AnalysisCardMotion.LOADING_LINE_DP)
    }

    @Test fun `cache hit on first bind and later rebinds never start entrance`() {
        val card = FakeCard()
        card.show(result(), cacheHit = true)
        repeat(10) { card.show(result(), cacheHit = true) }
        assertEquals(0, card.entrances)
        assertEquals(AnalysisCardMotion.Change.DIRECT, card.changes.first())
        assertTrue(card.changes.drop(1).all { it == AnalysisCardMotion.Change.NONE })
        // Reentering with a fresh row still displays the cached result directly.
        val restored = FakeCard()
        restored.show(result(), cacheHit = true)
        assertEquals(0, restored.entrances)
    }

    @Test fun `first miss enters once and loading to completion only updates content`() {
        val card = FakeCard()
        card.show(loading())
        card.show(loading())
        // Completion is in MoodStore already: cacheHit alone must not start another entry.
        card.show(result(), cacheHit = true)
        card.show(result(), cacheHit = true)
        assertEquals(1, card.entrances)
        assertEquals(listOf(AnalysisCardMotion.Change.ENTER, AnalysisCardMotion.Change.NONE,
            AnalysisCardMotion.Change.CONTENT, AnalysisCardMotion.Change.NONE), card.changes)
    }

    @Test fun `an uncached result appearing without a placeholder still enters once`() {
        val card = FakeCard()
        card.show(result(), cacheHit = false)
        card.show(result(), cacheHit = true)
        assertEquals(1, card.entrances)
        assertEquals(listOf(AnalysisCardMotion.Change.ENTER, AnalysisCardMotion.Change.NONE), card.changes)
    }

    @Test fun `reanalyze and rapidly arriving results stay content updates`() {
        val card = FakeCard()
        card.show(result(), cacheHit = true)
        card.show(loading())
        card.show(result(60), cacheHit = true)
        card.show(result(90), cacheHit = true)
        assertEquals(0, card.entrances)
        assertEquals(listOf(AnalysisCardMotion.Change.DIRECT, AnalysisCardMotion.Change.CONTENT,
            AnalysisCardMotion.Change.CONTENT, AnalysisCardMotion.Change.RINGS), card.changes)
    }

    @Test fun `probability-only updates preserve slots while label changes require new text`() {
        val presentation = AnalysisCardMotion.Presentation()
        presentation.bind(result(), true)
        assertEquals(AnalysisCardMotion.Change.RINGS, presentation.bind(result(42), true))
        val otherLabels = result().copy(emotions = listOf(EmotionIndicator("开心", 100, 0)))
        assertEquals(AnalysisCardMotion.Change.CONTENT, presentation.bind(otherLabels, true))
    }

    @Test fun `loading reserves the three result rows without printing fallback results`() {
        val placeholder = loading().lines
        assertEquals(5, placeholder.size)
        assertTrue(placeholder.first().emotion)
        assertEquals("智能分析", placeholder[1].text)
        assertEquals("分析中…", placeholder[2].text)
        assertTrue(placeholder.drop(2).all { it.placeholder && it.text.isNotEmpty() })
        val longStatus = mood.copy(detail = "正在转写语音 123/123…\n".repeat(100))
        assertEquals(placeholder, AnalysisCardContent.lines(longStatus, CardDisplaySettings(), loading = true))
    }

    @Test fun `height grows and shrinks monotonically and retargets from the displayed frame`() {
        val frames = (0..180).map { AnalysisCardMotion.heightAt(160, 280, it / 180f) }
        assertEquals(160, frames.first()); assertEquals(280, frames.last())
        assertTrue(frames.zipWithNext().all { (a, b) -> b >= a })
        val start = frames[65]
        val retarget = (0..180).map { AnalysisCardMotion.heightAt(start, 190, it / 180f) }
        assertEquals(start, retarget.first()); assertEquals(190, retarget.last())
        assertTrue(retarget.zipWithNext().all { (a, b) -> b <= a })
        assertTrue(retarget.all { it in 190..start })
    }
}
