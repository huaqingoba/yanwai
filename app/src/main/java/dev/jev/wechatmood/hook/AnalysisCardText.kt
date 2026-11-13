package dev.jev.wechatmood.hook

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import dev.jev.wechatmood.core.Mood
import dev.jev.wechatmood.core.EmotionIndicator
import dev.jev.wechatmood.core.CardDisplaySettings

object AnalysisCardText {
    fun content(mood: Mood, display: CardDisplaySettings, loading: Boolean = false) = AnalysisCardMotion.Content(
        AnalysisCardContent.lines(mood, display, loading),
        // Stable slot order allows percent-only changes to update the existing spans with invalidate().
        EmotionIndicator.from(mood.distribution).sortedBy { EmotionIndicator.colors.keys.indexOf(it.label) },
        loading,
    )

    @Suppress("UNUSED_PARAMETER")
    fun format(mood: Mood, density: Float, availableWidth: Int, paint: android.graphics.Paint,
        display: CardDisplaySettings = CardDisplaySettings()): CharSequence {
        return format(content(mood, display), density, availableWidth)
    }

    fun format(content: AnalysisCardMotion.Content, density: Float, availableWidth: Int, dark: Boolean = true): CharSequence {
        val emotions = content.emotions
        val lines = content.lines
        val text = SpannableStringBuilder()
        if (lines.isEmpty()) return text
        lines.forEachIndexed { index, line ->
            if (index > 0) text.append("\n")
            val lineStart = text.length
            if (line.emotion) {
                emotions.forEachIndexed { i, emotion ->
                    if (i > 0) text.append(" ")
                    val start = text.length
                    text.append(emotion.label)
                    text.setSpan(EmotionRingSpan(emotion, density, availableWidth), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            } else {
                text.append(line.text)
                if (line.placeholder || (line.label != null && line.label != "智能分析"))
                    text.setSpan(AnalysisPlaceholderSpan(density), lineStart, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            line.label?.let { label ->
                text.setSpan(StyleSpan(Typeface.BOLD), lineStart, lineStart + label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text.setSpan(ForegroundColorSpan(if (dark) 0xFFB2E3D5.toInt() else 0xFF20685C.toInt()), lineStart, lineStart + label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return text
    }

}
