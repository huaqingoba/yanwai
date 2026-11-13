package dev.jev.wechatmood.analysis

import dev.jev.wechatmood.reply.ReplySettings
import dev.jev.wechatmood.reply.ThinkingEffort
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Provider-shaped controls; custom gateways get a single, cached compatibility fallback. */
object AnalysisThinking {
    private enum class Backend { OPENAI, DASHSCOPE, ANTHROPIC, GEMINI, DOMESTIC, GATEWAY, NO_REASONING }
    // Full normalized endpoint (including path), shared by all clients in this process; no credentials.
    private val unsupportedEndpoints = ConcurrentHashMap.newKeySet<String>()
    private val openAiModel = Regex("^(gpt-[56](?:[.-].*)?|o[0-9]+(?:-.*)?)$")
    private val rejectedField = Regex("reasoning|thinking|unknown[ _-]+field", RegexOption.IGNORE_CASE)

    private fun backend(settings: ReplySettings): Backend {
        val url = settings.endpoint.toHttpUrlOrNull() ?: return Backend.NO_REASONING
        val host = url.host
        fun domain(name: String) = host == name || host.endsWith(".$name")
        val model = settings.model.lowercase(Locale.ROOT).substringAfterLast('/')
        return when {
            domain("dashscope.aliyuncs.com") || domain("dashscope-intl.aliyuncs.com") ||
                domain("maas.aliyuncs.com") -> Backend.DASHSCOPE
            host == "api.anthropic.com" || url.encodedPath.trimEnd('/').endsWith("/v1/messages") -> Backend.ANTHROPIC
            host == "generativelanguage.googleapis.com" -> Backend.GEMINI
            (host.startsWith("ark.") && domain("volces.com")) || domain("deepseek.com") ||
                domain("moonshot.cn") || domain("moonshot.ai") || domain("bigmodel.cn") ||
                domain("z.ai") || domain("xiaomimimo.com") -> Backend.DOMESTIC
            openAiModel.matches(model) -> Backend.OPENAI
            host == "api.openai.com" -> Backend.NO_REASONING
            else -> Backend.GATEWAY
        }
    }

    private fun cacheKey(settings: ReplySettings) = settings.endpoint.toHttpUrlOrNull()?.toString() ?: settings.endpoint

    private fun canFallback(settings: ReplySettings): Boolean = when (backend(settings)) {
        Backend.GATEWAY -> true
        Backend.OPENAI -> settings.endpoint.toHttpUrlOrNull()?.host != "api.openai.com"
        else -> false
    }

    fun apply(settings: ReplySettings, payload: JSONObject): JSONObject {
        removeThinkingFields(payload)
        if (unsupportedEndpoints.contains(cacheKey(settings))) return payload
        val effort = settings.effort
        val minimum = effort == ThinkingEffort.OFF || effort == ThinkingEffort.MINIMAL
        when (backend(settings)) {
            Backend.OPENAI -> payload.put("reasoning_effort", when (effort) {
                ThinkingEffort.OFF -> "none"
                ThinkingEffort.MINIMAL -> "minimal"
                else -> effort.name.lowercase(Locale.ROOT)
            })
            Backend.DASHSCOPE -> payload.put("enable_thinking", !minimum)
                .put("thinking_budget", if (minimum) 1 else effort.budget)
            Backend.ANTHROPIC -> payload.put("thinking", if (minimum) disabled() else
                JSONObject().put("type", "enabled").put("budget_tokens", effort.budget))
            Backend.GEMINI -> {
                val config = payload.optJSONObject("generationConfig") ?: JSONObject().also { payload.put("generationConfig", it) }
                config.put("thinkingConfig", JSONObject().put("thinkingBudget", effort.budget))
            }
            Backend.DOMESTIC -> if (minimum) payload.put("thinking", disabled())
            Backend.GATEWAY -> {
                payload.put("reasoning_effort", if (minimum) "minimal" else effort.name.lowercase(Locale.ROOT))
                if (minimum) payload.put("thinking", disabled())
            }
            Backend.NO_REASONING -> Unit
        }
        return payload
    }

    private fun disabled() = JSONObject().put("type", "disabled")

    private fun removeThinkingFields(payload: JSONObject) {
        listOf("reasoning_effort", "thinking", "enable_thinking", "thinking_budget").forEach { payload.remove(it) }
        payload.optJSONObject("generationConfig")?.let {
            it.remove("thinkingConfig")
            if (it.length() == 0) payload.remove("generationConfig")
        }
    }

    /** Only an explicit parameter rejection on a custom endpoint can trigger one retry. */
    internal fun fallbackPayload(settings: ReplySettings, payload: JSONObject, code: Int, errorBody: String): JSONObject? {
        if ((code != 400 && code != 422) || !canFallback(settings) ||
            (!payload.has("reasoning_effort") && !payload.has("thinking"))) return null
        if (!rejectedField.containsMatchIn(errorBody)) return null
        unsupportedEndpoints.add(cacheKey(settings))
        return JSONObject(payload.toString()).also(::removeThinkingFields)
    }

    fun description(settings: ReplySettings): String {
        val prefix = "思考强度：${settings.effort.label}"
        if (unsupportedEndpoints.contains(cacheKey(settings))) return "$prefix（该端点已拒绝思考参数，后续请求不下发）"
        val payload = apply(settings, JSONObject())
        val parameters = when {
            payload.has("enable_thinking") -> "enable_thinking=${payload.getBoolean("enable_thinking")}, thinking_budget=${payload.getInt("thinking_budget")}"
            payload.has("generationConfig") -> "generationConfig.thinkingConfig.thinkingBudget=${settings.effort.budget}"
            payload.has("thinking") -> payload.getJSONObject("thinking").let {
                "thinking=${it.getString("type")}" + if (it.has("budget_tokens")) ", budget_tokens=${it.getInt("budget_tokens")}" else ""
            }
            else -> ""
        }
        val reasoning = if (payload.has("reasoning_effort")) "reasoning_effort=${payload.getString("reasoning_effort")}" else ""
        val detail = listOf(reasoning, parameters).filter { it.isNotEmpty() }.joinToString(", ")
        return "$prefix（${detail.ifEmpty { if (backend(settings) == Backend.DOMESTIC) "不下发 thinking，使用服务默认预算" else "该模型无可配置的思考参数" }}）"
    }
}
