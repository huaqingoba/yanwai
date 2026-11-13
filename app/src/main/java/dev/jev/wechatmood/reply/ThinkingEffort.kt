package dev.jev.wechatmood.reply

enum class ThinkingEffort(val label: String) {
    OFF("关闭"), MINIMAL("最低"), LOW("低"), MEDIUM("中"), HIGH("高");

    val budget: Int get() = when (this) {
        OFF, MINIMAL -> 0
        LOW -> 1024
        MEDIUM -> 4096
        HIGH -> 16384
    }

    companion object {
        fun resolve(value: String?): ThinkingEffort =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: MINIMAL
    }
}
