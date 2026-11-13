package dev.jev.wechatmood.hook

import dev.jev.wechatmood.analysis.JevProtocol
import dev.jev.wechatmood.core.*
import org.junit.Assert.*
import org.junit.Test

class AnalysisCardContentTest {
    private val full = Mood("智能分析", 0.0, 0, "", "${JevProtocol.header}\n情绪：平静 80%\n智能分析\n意图解析：说明安排\n可能在意：时间\n情绪倾向：平和\n仅供参考",
        emotions = mapOf("平静" to 0.8))

    @Test fun `all legacy switch combinations retain the fixed schema without modifying cached fields`() {
        val payload = CachedAnalysisResult(full, "evidence").encode()
        val restored = CachedAnalysisResult.decode(payload).mood
        for (intent in listOf(false, true)) for (concern in listOf(false, true)) for (tone in listOf(false, true)) {
            val lines = AnalysisCardContent.lines(restored, CardDisplaySettings(intent, concern, tone))
            val text = lines.joinToString("\n") { it.text }
            assertEquals(listOf("智能分析", "意图解析：说明安排", "可能在意：时间", "情绪倾向：平和"), lines.drop(1).map { it.text })
            assertTrue(lines.first().emotion)
            assertFalse(text.contains("yanwai")); assertFalse(text.contains("仅供参考"))
            assertFalse(lines.drop(1).any { it.text.isEmpty() })
            assertEquals(100, restored.distribution.sumOf { it.percent })
        }
        assertEquals(payload, CachedAnalysisResult(restored, "evidence").encode())
        assertEquals(full.detail, restored.detail)
    }

    @Test fun `missing and reordered emotion nodes cannot remove the probability row`() {
        val off = CardDisplaySettings(false, false, false)
        val onlySections = full.copy(detail = full.detail.replace("情绪：平静 80%\n", ""), emotions = emptyMap())
        assertTrue(AnalysisCardContent.lines(onlySections, off).first().emotion)
        assertEquals(100, onlySections.distribution.single { it.label == "不明确" }.percent)
        val reordered = full.copy(detail = "意图解析：说明安排\n情绪：平静 80%\n情绪倾向：平和")
        val lines = AnalysisCardContent.lines(reordered, off)
        assertEquals(5, lines.size)
        assertTrue(lines.first().emotion)
        assertEquals("意图解析：说明安排", lines[2].text)
        assertEquals("情绪倾向：平和", lines[4].text)
    }

    @Test fun `JEV lines loading failure retry and coverage are preserved`() {
        val off = CardDisplaySettings(false, false, false)
        val legacy = full.copy(detail = "意图：询问\n事件：确认时间\n建议：问清楚")
        val projected = AnalysisCardContent.lines(legacy, off)
        assertEquals("意图解析：询问", projected[2].text)
        assertEquals("可能在意：确认时间", projected[3].text)
        for (body in listOf("智能分析中…", "模型未配置或设置未连接", "正在读取对方背景；读取失败时会重试",
            "智能分析失败，已保留情绪。点击卡片重试。", "分析失败：连接失败\n点击此卡重试", "前文有 1 条语音未能转写")) {
            val mood = full.copy(detail = "${JevProtocol.header}\n$body", intentFailed = true, emotions = emptyMap())
            val lines = AnalysisCardContent.lines(mood, off)
            assertTrue(lines.first().emotion)
            assertTrue(lines.joinToString("\n") { it.text }.endsWith(body))
            assertEquals(100, mood.distribution.single { it.label == "不明确" }.percent)
        }
    }

    @Test fun `transcript lines resembling analysis labels remain original evidence`() {
        val transcript = "语音转写：请记下这些标题\n意图解析：这句是原话\n可能在意：同样是原话\n情绪倾向：也保留\n情绪：开心 100%\n（仅根据转写文字分析）"
        val mood = full.copy(detail = full.detail + "\n\n" + transcript)
        val lines = AnalysisCardContent.lines(mood, CardDisplaySettings(false, false, false))
        assertTrue(lines.joinToString("\n") { it.text }.endsWith(transcript))
        assertEquals(1, lines.count { it.emotion })
        assertTrue(lines.dropWhile { !it.text.startsWith("语音转写：") }.all { it.label == null && !it.emotion })
        val missing = Mood("智能分析", 0.0, 0, "", "智能分析\n" + transcript)
        assertEquals(100, missing.distribution.single { it.label == "不明确" }.percent)
    }

    @Test fun `old qualitative text is replaced by a normalized probability row`() {
        val mood = full.copy(emotions = emptyMap(), detail = "${JevProtocol.header}\n情绪：平静（LLM 定性，非概率）\n仅供参考")
        assertEquals(70, mood.distribution.single { it.label == "平静" }.percent)
        val lines = AnalysisCardContent.lines(mood, CardDisplaySettings())
        assertTrue(lines.first().emotion)
        assertFalse(lines.any { it.text.contains("LLM 定性") || it.text.contains("仅供参考") || it.text.contains("yanwai") })
    }
}
