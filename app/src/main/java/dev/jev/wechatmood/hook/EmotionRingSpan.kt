package dev.jev.wechatmood.hook

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.style.ReplacementSpan
import dev.jev.wechatmood.core.EmotionIndicator
import kotlin.math.ceil
import kotlin.math.max

/** One atomic inline chip, so Android can wrap between emotions, never inside a ring. */
class EmotionRingSpan(private val emotion: EmotionIndicator, private val density: Float,
    availableWidth: Int = Int.MAX_VALUE) : ReplacementSpan() {
    private val maxWidth = availableWidth.coerceAtLeast(1).toFloat()
    private fun labelPaint(paint: Paint) = Paint(paint).apply {
        textSize *= 0.8f; color = emotion.color
        val width = measureText(emotion.label)
        if (width > maxWidth) textSize *= maxWidth / width
    }
    private fun numberPaint(paint: Paint) = Paint(paint).apply { textSize *= 0.65f; color = 0xFFF0F1F5.toInt() }
    private fun diameter(paint: Paint) = max(24f * density, numberPaint(paint).measureText("100%") + 6f * density).coerceAtMost(maxWidth)
    private fun stacked(paint: Paint) = diameter(paint) + 3f * density + labelPaint(paint).measureText(emotion.label) > maxWidth
    private fun width(paint: Paint): Float = if (stacked(paint)) max(diameter(paint), labelPaint(paint).measureText(emotion.label))
        else diameter(paint) + 3f * density + labelPaint(paint).measureText(emotion.label)
    private fun extraHeight(paint: Paint): Float = if (stacked(paint)) labelPaint(paint).fontMetrics.let { it.descent - it.ascent } + 3f * density else 0f
    private fun height(paint: Paint) = max(diameter(paint) + extraHeight(paint), paint.fontMetrics.descent - paint.fontMetrics.ascent) + 4f * density

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        val height = height(paint)
        val center = (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f
        fm?.apply {
            ascent = kotlin.math.floor(center - height / 2f).toInt()
            descent = ceil(center + height / 2f).toInt()
            top = ascent; bottom = descent; leading = 0
        }
        return ceil(width(paint)).toInt()
    }

    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float,
        top: Int, y: Int, bottom: Int, paint: Paint) {
        val diameter = diameter(paint)
        val centerY = y + (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f - extraHeight(paint) / 2f
        val centerX = x + if (stacked(paint)) width(paint) / 2f else diameter / 2f
        val stroke = (2f * density).coerceAtMost(diameter / 4)
        val rect = RectF(centerX - diameter / 2 + stroke / 2, centerY - diameter / 2 + stroke / 2,
            centerX + diameter / 2 - stroke / 2, centerY + diameter / 2 - stroke / 2)
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = stroke; color = 0x446E7B89
        }
        canvas.drawOval(rect, ring)
        ring.color = emotion.color
        // Display weight (including qualitative priors), never elapsed analysis progress.
        if (emotion.probability > 0) canvas.drawArc(rect, -90f, (emotion.probability * 360).toFloat(), false, ring)
        val number = numberPaint(paint)
        val numberWidth = number.measureText("100%")
        if (numberWidth > diameter - 2 * stroke) number.textSize *= (diameter - 2 * stroke) / numberWidth
        canvas.drawText(emotion.percent, centerX - number.measureText(emotion.percent) / 2,
            centerY - (number.fontMetrics.ascent + number.fontMetrics.descent) / 2, number)
        val label = labelPaint(paint)
        if (stacked(paint)) canvas.drawText(emotion.label, centerX - label.measureText(emotion.label) / 2,
            centerY + diameter / 2 + 3f * density - label.fontMetrics.ascent, label)
        else canvas.drawText(emotion.label, x + diameter + 3f * density,
            centerY - (label.fontMetrics.ascent + label.fontMetrics.descent) / 2, label)
    }
}
