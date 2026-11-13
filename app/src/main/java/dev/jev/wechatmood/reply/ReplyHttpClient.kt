package dev.jev.wechatmood.reply

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import dev.jev.wechatmood.analysis.AnalysisThinking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import org.json.JSONObject

class ReplyHttpClient(private val client: Call.Factory = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS)
    .followRedirects(false).followSslRedirects(false).build()) {
    suspend fun generate(settings: ReplySettings, context: ReplyContext, draft: String, direction: String,
        knowledge: String, previous: String = "", focusMessageId: Long? = null,
        relationship: ReplyRelationship = ReplyRelationship.UNSPECIFIED, customRelationship: String = ""): ReplySuggestion {
        check(settings.isConfigured) { "请先在言外的「回复建议」中保存地址、API Key 和模型名" }
        val payload = ReplyProtocol.payload(settings, context, draft, direction, knowledge, previous, focusMessageId, relationship, customRelationship)
        return request(settings, payload, ReplyProtocol::parse)
    }

    suspend fun findTopics(settings: ReplySettings, context: ReplyContext, draft: String, notes: String,
        knowledge: String, relationship: ReplyRelationship, time: TopicTimeContext,
        previous: List<TopicSuggestion> = emptyList(), customRelationship: String = ""): List<TopicSuggestion> {
        check(settings.isConfigured) { "请先配置回复模型" }
        return request(settings, TopicProtocol.payload(settings, context, draft, notes, knowledge, relationship, time, previous, customRelationship), TopicProtocol::parse)
    }

    internal suspend fun <T> request(settings: ReplySettings, payload: JSONObject, parse: (String) -> T): T {
        currentCoroutineContext().ensureActive()
        // Work on a deep copy: callers may retain the original or construct it before a rejection was cached.
        val initial = AnalysisThinking.apply(settings, JSONObject(payload.toString()))
        var response = exchange(settings, initial)
        currentCoroutineContext().ensureActive()
        val fallback = AnalysisThinking.fallbackPayload(settings, initial, response.code, response.body)
        if (fallback != null) {
            currentCoroutineContext().ensureActive()
            // Exactly one downgrade attempt; don't reapply controls or retry the second response.
            response = exchange(settings, fallback)
        }
        currentCoroutineContext().ensureActive()
        check(response.code in 200..299) { statusMessage(response.code) }
        return runCatching { parse(response.body) }.getOrElse { error ->
            if (error is IllegalStateException) throw error
            throw IllegalStateException("读取回复失败，请检查网络后重试")
        }
    }

    private data class HttpResult(val code: Int, val body: String)

    private fun statusMessage(code: Int): String = when (code) {
        401, 403 -> "API Key 或模型权限不可用，请检查模型配置"
        402 -> "模型账户额度不足"
        429 -> "请求过于频繁，请稍后重试"
        400, 404, 422 -> "接口地址、模型或上下文长度不受支持，请检查配置"
        in 300..399 -> "接口发生重定向，请填写最终地址"
        else -> "模型服务暂不可用（HTTP $code）"
    }

    private suspend fun exchange(settings: ReplySettings, payload: JSONObject): HttpResult =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(Request.Builder().url(settings.endpoint).header("Authorization", "Bearer ${settings.apiKey}")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException("连接超时或网络不可用，请重试"))
                }
                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching { response.use {
                        // Other HTTP failures keep their existing fast failure path; no downgrade is possible.
                        if (!it.isSuccessful && it.code != 400 && it.code != 422) return@use HttpResult(it.code, "")
                        val source = it.body?.source()
                        val limit = if (it.isSuccessful) 1024 * 1024L else 64 * 1024L
                        source?.request(limit + 1)
                        if (it.isSuccessful) {
                            check(source != null) { "读取回复失败，请检查网络后重试" }
                            check(source.buffer.size <= limit) { "模型响应过长，请换一个模型后重试" }
                        }
                        // Error bodies are bounded and only inspected for compatibility, never exposed in UI/logs.
                        HttpResult(it.code, source?.readUtf8(minOf(source.buffer.size, limit)).orEmpty())
                    } }.recoverCatching { error ->
                        if (error is IllegalStateException) throw error
                        throw IllegalStateException("读取回复失败，请检查网络后重试")
                    }
                    if (continuation.isActive) continuation.resumeWith(result)
                }
            })
        }
}
