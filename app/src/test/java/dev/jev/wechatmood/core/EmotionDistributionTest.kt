package dev.jev.wechatmood.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EmotionDistributionTest {
    private fun assertDistribution(scores: List<EmotionScore>): Map<String, Int> {
        assertEquals(EmotionDistribution.labels.values.toList(), scores.map { it.label })
        assertTrue(scores.all { it.percent in 0..100 })
        assertEquals(100, scores.sumOf { it.percent })
        return scores.associate { it.label to it.percent }
    }

    @Test fun `single qualitative label receives 70 with an explicit 30 prior`() {
        val values = assertDistribution(EmotionDistribution.normalize(label = "平静"))
        assertEquals(70, values.getValue("平静"))
        assertEquals(30, values.filterKeys { it != "平静" }.values.sum())
        assertTrue(values.filterKeys { it != "平静" }.values.all { it in 3..4 })
        assertEquals(values, assertDistribution(EmotionDistribution.normalize(text = "情绪：平静")))
        assertEquals(values, assertDistribution(EmotionDistribution.normalize(text = """{"choice":"calm"}""")))
        val envelope = JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop")
            .put("message", JSONObject().put("content", "平静")))).toString()
        assertEquals(values, assertDistribution(EmotionDistribution.normalize(text = envelope)))
    }

    @Test fun `complete fractional distribution preserves the requested percentages`() {
        val source = mapOf("平静" to .49, "委屈" to .03, "生气" to .33, "缓和" to .01, "不明确" to .14)
        val values = assertDistribution(EmotionDistribution.normalize(source))
        source.forEach { (label, probability) -> assertEquals((probability * 100).toInt(), values.getValue(label)) }
        assertEquals(values, assertDistribution(EmotionDistribution.normalize(source.entries.reversed().associate { it.toPair() })))
    }

    @Test fun `dirty JSON handles prose fences decimal numbers and percent strings`() {
        val content = """
            下面是分析结果：
            ```json
            {"probabilities":{"calm":0.49,"hurt":"3%","angry":0.33,"relieved":"1%","unknown":"14%"}}
            ```
            分析结束。
        """.trimIndent()
        val envelope = JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop")
            .put("message", JSONObject().put("content", content)))).toString()
        val values = assertDistribution(EmotionDistribution.normalize(text = envelope))
        assertEquals(49, values.getValue("平静")); assertEquals(3, values.getValue("委屈"))
        assertEquals(33, values.getValue("生气")); assertEquals(1, values.getValue("缓和"))
        assertEquals(14, values.getValue("不明确"))
        assertEquals(values, assertDistribution(EmotionDistribution.normalize(text = content)))
        assertEquals(100, assertDistribution(EmotionDistribution.normalize(text = envelope.replace("stop", "length"))).getValue("不明确"))
    }

    @Test fun `empty invalid and unclassifiable inputs always show unknown 100`() {
        for (body in listOf("", "{}", "还无法确定这句话的意思", "不能认定生气", "```json\n{", """{"emotion":"98%"}""")) {
            assertEquals(100, assertDistribution(EmotionDistribution.normalize(text = body)).getValue("不明确"))
        }
        assertEquals(100, assertDistribution(EmotionDistribution.normalize()).getValue("不明确"))
        assertEquals(100, assertDistribution(EmotionDistribution.normalize(mapOf("平静" to Double.NaN, "生气" to -1.0))).getValue("不明确"))
    }

    @Test fun `largest remainder rounds thirds and repaired weights to exactly 100`() {
        val values = assertDistribution(EmotionDistribution.normalize(mapOf("happy" to 1.0, "calm" to 1.0, "hurt" to 1.0)))
        assertEquals(listOf(34, 33, 33), listOf("开心", "平静", "委屈").map(values::getValue))
        assertEquals(100, assertDistribution(EmotionDistribution.normalize(mapOf("平静" to .8))).getValue("平静"))
        assertEquals(100, assertDistribution(EmotionDistribution.normalize(mapOf("新情绪" to 1.0))).getValue("不明确"))
    }
}
