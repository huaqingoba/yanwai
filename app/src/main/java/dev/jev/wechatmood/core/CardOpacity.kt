package dev.jev.wechatmood.core

/** Presentation only; malformed preference types and out-of-range values use the default. */
object CardOpacity {
    const val KEY = "card_opacity"
    const val MIN = 85
    const val MAX = 100
    const val DEFAULT = 96

    fun normalize(value: Any?): Int = (value as? Int)?.takeIf { it in MIN..MAX } ?: DEFAULT
    fun load(read: (String) -> Any?): Int = normalize(read(KEY))
    fun alpha(value: Int): Int = (normalize(value) * 255 + 50) / 100
}
