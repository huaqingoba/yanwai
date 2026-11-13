package dev.jev.wechatmood.hook

import android.graphics.Paint
import android.text.style.LineHeightSpan
import kotlin.math.ceil

/** Three nonzero reserved lines match the result schema; status strings cannot grow the placeholder. */
class AnalysisPlaceholderSpan(private val density: Float) : LineHeightSpan {
    override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, v: Int, fm: Paint.FontMetricsInt) {
        val height = maxOf(ceil(AnalysisCardMotion.LOADING_LINE_DP * density).toInt(), fm.descent - fm.ascent)
        val extra = height - (fm.descent - fm.ascent)
        fm.ascent -= extra / 2
        fm.descent += extra - extra / 2
        fm.top = fm.ascent
        fm.bottom = fm.descent
    }
}
