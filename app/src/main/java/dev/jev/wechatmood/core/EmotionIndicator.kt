package dev.jev.wechatmood.core

/** Stable colors across routes and messages; labels remain available without color perception. */
data class EmotionIndicator(val label: String, val value: Int, val color: Int) {
    val percent: String get() = "$value%"
    val probability: Double get() = value / 100.0

    companion object {
        val colors = linkedMapOf(
            "开心" to 0xFFF0CD68.toInt(), "平静" to 0xFF78CDB4.toInt(),
            "失落" to 0xFF9F97BB.toInt(), "委屈" to 0xFFD99BCB.toInt(),
            "生气" to 0xFFF28B82.toInt(), "缓和" to 0xFF75C9D2.toInt(),
            "焦虑" to 0xFFEFB17D.toInt(), "困惑" to 0xFFB69FE3.toInt(),
            "疲惫" to 0xFFE5BE6B.toInt(), "不明确" to 0xFFBDC3CA.toInt(),
        )

        fun from(distribution: List<EmotionScore>): List<EmotionIndicator> =
            distribution.filter { it.percent > 0 }.map {
                EmotionIndicator(it.label, it.percent, colors[it.label] ?: colors.getValue("不明确"))
            }
    }
}
