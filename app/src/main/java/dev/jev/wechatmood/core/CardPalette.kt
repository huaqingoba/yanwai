package dev.jev.wechatmood.core

/** Presentation only; AUTO resolves against the host's current uiMode. */
enum class CardPalette(val id: String, val label: String) {
    DARK("dark", "深色"), LIGHT("light", "浅色"), AUTO("auto", "跟随系统");

    fun isDark(systemDark: Boolean): Boolean = when (this) {
        DARK -> true
        LIGHT -> false
        AUTO -> systemDark
    }

    companion object {
        const val KEY = "card_palette"
        val DEFAULT = DARK
        fun parse(value: Any?): CardPalette = entries.firstOrNull { it.id == value } ?: DEFAULT
        fun load(read: (String) -> Any?): CardPalette = parse(read(KEY))
    }
}
