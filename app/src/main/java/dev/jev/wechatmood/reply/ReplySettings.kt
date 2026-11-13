package dev.jev.wechatmood.reply

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Separate from Jev configuration; never include credentials in toString. */
class ReplySettings private constructor(val endpoint: String, val apiKey: String, val model: String,
    val effort: ThinkingEffort = ThinkingEffort.MINIMAL) {
    val isConfigured get() = endpoint.isNotBlank() && model.isNotBlank() && apiKey.isNotBlank()
    companion object {
        const val KEY_ENDPOINT = "reply_endpoint"
        const val KEY_API_KEY = "reply_api_key"
        const val KEY_MODEL = "reply_model"
        const val KEY_CONSENT = "reply_consent"
        // Shared by independent analysis, reused reply models and manual generation.
        const val KEY_THINKING_EFFORT = "reply_thinking_effort"
        fun readEffort(read: (String) -> String?) = ThinkingEffort.resolve(read(KEY_THINKING_EFFORT))
        fun saveEffort(effort: ThinkingEffort, write: (String, String) -> Unit) = write(KEY_THINKING_EFFORT, effort.name)
        fun empty(effort: ThinkingEffort = ThinkingEffort.MINIMAL) = ReplySettings("", "", "", effort)
        fun fromInput(endpoint: String, apiKey: String, model: String,
            effort: ThinkingEffort = ThinkingEffort.MINIMAL): ReplySettings {
            if (endpoint.isBlank() && apiKey.isBlank() && model.isBlank()) return empty(effort)
            val url = endpoint.trim().toHttpUrlOrNull()
            require(url != null && url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
                "请填写 HTTP(S) 地址，不要包含账号密码、查询参数或锚点"
            }
            val key = apiKey.trim()
            require(key.all { it.code in 33..126 }) { "API Key 不能包含空格、换行或中文字符" }
            val name = model.trim()
            require(name.none { it.isWhitespace() || it.isISOControl() }) { "模型名不能包含空格或换行" }
            val base = url.toString().trimEnd('/')
            val normalized = when {
                url.encodedPath.trimEnd('/') == "" -> "$base/v1/chat/completions"
                url.encodedPath.trimEnd('/').endsWith("/chat/completions") -> base
                url.encodedPath.trimEnd('/').endsWith("/v1/messages") -> base
                else -> "$base/chat/completions"
            }
            return ReplySettings(normalized, key, name, effort)
        }
    }
}
