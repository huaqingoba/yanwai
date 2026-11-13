package dev.jev.wechatmood.hook

import dev.jev.wechatmood.core.CardDisplaySettings
import dev.jev.wechatmood.core.Mood

/** Fixed card schema; historical headers/decorations stay in cached detail, never in the overlay. */
object AnalysisCardContent {
    data class Line(val text: String, val emotion: Boolean = false, val label: String? = null)

    @Suppress("UNUSED_PARAMETER") // Keep stored preferences compatible; this card now has three fixed sections.
    fun lines(mood: Mood, display: CardDisplaySettings): List<Line> {
        val original = mood.detail.lines().map(String::trim).filter(String::isNotEmpty)
        val split = original.indexOfFirst { it.startsWith("语音转写：") }.let { if (it < 0) original.size else it }
        val analysis = original.take(split)
        fun value(prefix: String): String? = analysis.firstOrNull { it.startsWith(prefix) }
            ?.removePrefix(prefix)?.trim()?.takeIf(String::isNotEmpty)
        val dominant = mood.distribution.maxByOrNull { it.percent }!!
        val tone = if (dominant.label == "不明确") "信息不足，无法判断文字情绪。"
            else "文字可能偏向${dominant.label}，仍需结合语境判断。"
        val legacyConcern = analysis.dropWhile { !it.startsWith("事件：") }.takeWhile { !it.startsWith("建议：") }
            .joinToString(" ").removePrefix("事件：").takeIf(String::isNotBlank)
        val sections = listOf(
            "意图解析：" to (value("意图解析：") ?: value("意图：") ?: "信息不足，无法判断意图。"),
            "可能在意：" to (value("可能在意：") ?: legacyConcern ?: "信息不足，无法判断在意的点。"),
            "情绪倾向：" to (value("情绪倾向：") ?: tone),
        )
        return buildList {
            // Structural row, even when the backend supplied neither a distribution nor an emotion text node.
            add(Line("", emotion = true))
            add(Line("智能分析", label = "智能分析"))
            sections.forEach { (label, content) -> add(Line(label + content.replace(Regex("\\s+"), " "), label = label)) }
            if (mood.intentFailed && dev.jev.wechatmood.core.CachedAnalysisResult.RETRY_TEXT !in analysis)
                add(Line(dev.jev.wechatmood.core.CachedAnalysisResult.RETRY_TEXT))
            // Progress/errors remain retryable, and coverage/transcripts remain original evidence.
            analysis.filter { it.startsWith("正在") || it.startsWith("智能分析中") || it.startsWith("模型未配置") ||
                it.startsWith("分析失败：") || it.startsWith("点击") ||
                it.startsWith("分析返回不完整") || it.startsWith("智能分析失败") || it.startsWith("智能分析尚未配置") ||
                it.startsWith("前文有 ") }.forEach { add(Line(it)) }
            original.drop(split).forEach { add(Line(it)) }
        }
    }
}
