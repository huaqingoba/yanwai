package dev.jev.wechatmood.hook

import dev.jev.wechatmood.core.EmotionIndicator

/** Shared by the host view and offline tests; no Android objects needed for render decisions. */
object AnalysisCardMotion {
    const val ENTER_MS = 180L
    const val BUBBLE_FADE_MS = 120L
    const val CONTENT_OUT_MS = 120L
    const val CONTENT_IN_MS = 150L
    const val HEIGHT_MS = 180L
    const val CLOSE_MS = 120L
    const val ENTER_SCALE = 0.96f
    const val CLOSE_SCALE = 0.98f
    const val ENTER_TRANSLATION_DP = 8
    const val WIDTH_DP = 300
    const val HORIZONTAL_PADDING_DP = 12
    const val LOADING_LINE_DP = 28
    enum class Curve { DECELERATE }
    val curve = Curve.DECELERATE

    data class Content(val lines: List<AnalysisCardContent.Line>, val emotions: List<EmotionIndicator>, val loading: Boolean = false)
    enum class Change { DIRECT, ENTER, CONTENT, RINGS, NONE }

    class Presentation {
        private var current: Content? = null
        fun bind(next: Content, cacheHit: Boolean): Change {
            val old = current
            current = next
            return when {
                old == null -> if (cacheHit) Change.DIRECT else Change.ENTER
                old == next -> Change.NONE
                old.lines == next.lines && old.emotions.map { it.label } == next.emotions.map { it.label } -> Change.RINGS
                else -> Change.CONTENT
            }
        }
    }

    /** Retarget from the displayed height, never an intermediate zero/wrap_content measurement. */
    fun heightAt(start: Int, end: Int, fraction: Float): Int =
        (start + (end - start) * fraction.coerceIn(0f, 1f)).toInt()
}
