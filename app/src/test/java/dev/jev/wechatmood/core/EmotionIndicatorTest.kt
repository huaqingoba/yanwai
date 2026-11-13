package dev.jev.wechatmood.core

import dev.jev.wechatmood.analysis.JevProtocol
import org.junit.Assert.*
import org.junit.Test

class EmotionIndicatorTest {
    @Test fun `nonzero emotions retain stable colors and zero probabilities take no space`() {
        val values = JevProtocol.emotions.values.associateWith { 0.1 }
        val indicators = EmotionIndicator.from(EmotionDistribution.normalize(values))
        assertEquals(values.keys.toSet(), indicators.map { it.label }.toSet())
        assertEquals(indicators.size, indicators.map { it.color }.toSet().size)
        assertEquals(indicators, EmotionIndicator.from(EmotionDistribution.normalize(values.entries.reversed().associate { it.toPair() })))
        val single = EmotionIndicator.from(EmotionDistribution.normalize(values.mapValues { if (it.key == "开心") 1.0 else 0.0 }))
        assertEquals(listOf("开心"), single.map { it.label })
        assertEquals("100%", single.single().percent)
        val fallback = EmotionIndicator.from(EmotionDistribution.normalize(values.mapValues { 0.0 }))
        assertEquals("不明确", fallback.single().label)
        assertEquals("100%", fallback.single().percent)
    }

    @Test fun `display percentages are integers and arcs use those same values`() {
        val indicators = EmotionIndicator.from(EmotionDistribution.normalize(mapOf("开心" to 0.333, "平静" to 0.667)))
        assertEquals(listOf("33%", "67%"), indicators.map { it.percent })
        assertEquals(listOf(0.33, 0.67), indicators.map { it.probability })
        assertEquals(100, indicators.sumOf { it.value })
    }

    @Test fun `unexpected values cannot create invalid arcs and unknown labels remain readable`() {
        val indicators = EmotionIndicator.from(EmotionDistribution.normalize(mapOf("开心" to Double.NaN, "平静" to -0.1,
            "生气" to 2.0, "新情绪" to 0.4)))
        assertFalse(indicators.any { it.label == "开心" })
        assertFalse(indicators.any { it.label == "平静" })
        assertEquals(0.83, indicators.single { it.label == "生气" }.probability, 0.0)
        assertEquals("17%", indicators.single { it.label == "不明确" }.percent)
    }
}
