package dev.jev.wechatmood.analysis

import dev.jev.wechatmood.core.*
import dev.jev.wechatmood.reply.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class AnalysisThinkingTest {
    private fun settings(endpoint: String, model: String = "test-model", effort: ThinkingEffort = ThinkingEffort.MINIMAL) =
        ReplySettings.fromInput(endpoint, "fake-key", model, effort)

    private fun payload(endpoint: String, model: String = "test-model", effort: ThinkingEffort = ThinkingEffort.MINIMAL) =
        AnalysisThinking.apply(settings(endpoint, model, effort), JSONObject())

    @Test fun `missing and invalid preferences and existing constructors default to MINIMAL`() {
        assertEquals(ThinkingEffort.MINIMAL, ReplySettings.empty().effort)
        assertEquals(ThinkingEffort.MINIMAL, settings("https://example.com").effort)
        assertEquals(ThinkingEffort.MINIMAL, ReplySettings.readEffort { null })
        assertEquals(ThinkingEffort.MINIMAL, ReplySettings.readEffort { "invalid" })
        assertEquals(ThinkingEffort.MINIMAL, IntentSettings.load { null }.llm.effort)
        assertEquals(ThinkingEffort.HIGH, ReplySettings.empty(ThinkingEffort.HIGH).effort)
        val values = mutableMapOf<String, String>()
        ReplySettings.saveEffort(ThinkingEffort.HIGH) { key, value -> values[key] = value }
        assertEquals(ThinkingEffort.HIGH, ReplySettings.readEffort(values::get))
        assertEquals(ThinkingEffort.HIGH, IntentSettings.load(values::get).llm.effort)
        assertEquals(ThinkingEffort.HIGH, ReplyProfiles.load(ReplyProvider.DEEPSEEK, values::get).effort)
        assertEquals(ThinkingEffort.HIGH, IntentProfiles.load(ReplyProvider.DEEPSEEK, values::get).effort)
        val updates = IntentProfiles.valuesToSave(ReplyProvider.DEEPSEEK,
            settings(ReplyProvider.DEEPSEEK.endpoint, effort = ThinkingEffort.OFF), values::get)
        assertEquals("OFF", updates[ReplySettings.KEY_THINKING_EFFORT])
        assertFalse(updates.containsKey("intent_thinking_effort"))
        assertFalse(updates.containsKey(ReplySettings.KEY_API_KEY))
    }

    @Test fun `OpenAI families on official and compatible endpoints use all five effort values`() {
        val mapping = mapOf(ThinkingEffort.OFF to "none", ThinkingEffort.MINIMAL to "minimal",
            ThinkingEffort.LOW to "low", ThinkingEffort.MEDIUM to "medium", ThinkingEffort.HIGH to "high")
        for (endpoint in listOf("https://api.openai.com/v1", "https://custom.example/v1", "https://openrouter.ai/api/v1")) {
            for (model in listOf("gpt-5", "gpt-5.4-2026-03-05", "gpt-6-astra", "o1", "o3-mini", "openai/o4-mini")) {
                mapping.forEach { (effort, expected) ->
                    val actual = payload(endpoint, model, effort)
                    assertEquals("$endpoint $model $effort", expected, actual.getString("reasoning_effort"))
                    assertFalse(actual.has("thinking"))
                }
            }
        }
        assertEquals(0, payload("https://api.openai.com/v1", "gpt-4o").length())
    }

    @Test fun `DashScope and Maas hosts set enable switch and token budgets`() {
        for (endpoint in listOf("https://dashscope.aliyuncs.com/compatible-mode/v1",
            "https://dashscope-intl.aliyuncs.com/compatible-mode/v1", "https://cn-beijing.maas.aliyuncs.com/v1")) {
            ThinkingEffort.entries.forEach { effort ->
                val actual = payload(endpoint, "qwen3", effort)
                assertEquals(effort.budget > 0, actual.getBoolean("enable_thinking"))
                assertEquals(if (effort.budget == 0) 1 else effort.budget, actual.getInt("thinking_budget"))
                assertFalse(actual.has("thinking")); assertFalse(actual.has("reasoning_effort"))
            }
        }
    }

    @Test fun `Anthropic host or messages path controls thinking and enabled budget`() {
        for (endpoint in listOf("https://api.anthropic.com/v1/messages", "https://proxy.example/v1/messages/")) {
            assertTrue(settings(endpoint).endpoint.endsWith("/v1/messages"))
            ThinkingEffort.entries.forEach { effort ->
                val actual = payload(endpoint, "claude-test", effort).getJSONObject("thinking")
                assertEquals(if (effort.budget == 0) "disabled" else "enabled", actual.getString("type"))
                if (effort.budget > 0) assertEquals(effort.budget, actual.getInt("budget_tokens"))
                else assertFalse(actual.has("budget_tokens"))
            }
        }
    }

    @Test fun `Gemini uses nested thinking budget and preserves other generation config`() {
        val body = JSONObject().put("generationConfig", JSONObject().put("temperature", 0.2))
        ThinkingEffort.entries.forEach { effort ->
            val actual = AnalysisThinking.apply(settings("https://generativelanguage.googleapis.com/v1beta", effort = effort), body)
            val config = actual.getJSONObject("generationConfig")
            assertEquals(effort.budget, config.getJSONObject("thinkingConfig").getInt("thinkingBudget"))
            assertEquals(0.2, config.getDouble("temperature"), 0.0)
            assertFalse(actual.has("thinking"))
        }
    }

    @Test fun `domestic hosts disable without model whitelist and HIGH removes stale switches`() {
        for (endpoint in listOf("https://ark.cn-beijing.volces.com/api/v3", "https://ark.cn-shanghai.volces.com/api/v3",
            "https://api.deepseek.com/v1", "https://api.moonshot.cn/v1", "https://api.moonshot.ai/v1",
            "https://open.bigmodel.cn/api/paas/v4", "https://api.z.ai/api/paas/v4", "https://api.xiaomimimo.com/v1")) {
            val body = payload(endpoint, "ep-unlisted", ThinkingEffort.OFF)
            assertEquals("disabled", body.getJSONObject("thinking").getString("type"))
            assertEquals("disabled", payload(endpoint).getJSONObject("thinking").getString("type"))
            AnalysisThinking.apply(settings(endpoint, effort = ThinkingEffort.HIGH), body)
            assertEquals(0, body.length())
        }
    }

    @Test fun `unknown gateway requests minimum and rejection strips only thinking from a copy and caches endpoint`() {
        val endpoint = "https://${UUID.randomUUID()}.example/v1"
        val cfg = settings(endpoint, effort = ThinkingEffort.OFF)
        val body = AnalysisThinking.apply(cfg, JSONObject().put("model", "m").put("messages", org.json.JSONArray()))
        assertEquals("minimal", body.getString("reasoning_effort"))
        assertEquals("disabled", body.getJSONObject("thinking").getString("type"))
        val fallback = requireNotNull(AnalysisThinking.fallbackPayload(cfg, body, 422, "Unknown field: thinking"))
        assertFalse(fallback.has("reasoning_effort")); assertFalse(fallback.has("thinking"))
        assertTrue(body.has("thinking")); assertEquals("m", fallback.getString("model"))
        fallback.getJSONArray("messages").put("changed")
        assertEquals(0, body.getJSONArray("messages").length())
        assertEquals(0, payload(endpoint, effort = ThinkingEffort.HIGH).length())
        assertTrue(AnalysisThinking.description(cfg).contains("已拒绝思考参数"))
        assertTrue(payload(endpoint + "/other").has("thinking"))
    }

    @Test fun `fallback requires custom endpoint parameter rejection and 400 or 422`() {
        for ((code, error) in listOf(401 to "thinking", 429 to "reasoning", 500 to "unknown field",
            400 to "invalid model", 422 to "context too long")) {
            val cfg = settings("https://${UUID.randomUUID()}.example/v1")
            val body = AnalysisThinking.apply(cfg, JSONObject())
            assertNull(AnalysisThinking.fallbackPayload(cfg, body, code, error))
            assertTrue(AnalysisThinking.apply(cfg, JSONObject()).has("thinking"))
        }
        val official = settings("https://api.openai.com/v1", "gpt-5")
        assertNull(AnalysisThinking.fallbackPayload(official, AnalysisThinking.apply(official, JSONObject()), 400, "reasoning unsupported"))
        assertTrue(payload("https://api.deepseek.com.evil.example/v1").has("reasoning_effort"))
        val unknown = payload("https://custom.example/v1", effort = ThinkingEffort.HIGH)
        assertEquals("high", unknown.getString("reasoning_effort")); assertFalse(unknown.has("thinking"))
    }

    @Test fun `effort changes invalidate analysis cache and diagnostics show actual mapping`() {
        val api = ApiSettings.fromInput(ApiSettings.DEFAULT_ENDPOINT, "")
        fun runtime(effort: ThinkingEffort) = RuntimeSettings(1, false, api, "install",
            intent = IntentSettings(IntentRoute.LLM, settings("https://api.openai.com/v1", "gpt-5", effort)),
            emotion = EmotionSettings(EmotionSource.LLM))
        assertFalse(runtime(ThinkingEffort.OFF).sameAnalysis(runtime(ThinkingEffort.HIGH)))
        assertFalse(runtime(ThinkingEffort.OFF).intent.sameAs(runtime(ThinkingEffort.HIGH).intent))
        assertEquals("思考强度：最低（reasoning_effort=minimal）", AnalysisThinking.description(settings("https://api.openai.com/v1", "gpt-5")))
        assertEquals("思考强度：关闭（thinking=disabled）", AnalysisThinking.description(settings("https://api.deepseek.com/v1", effort = ThinkingEffort.OFF)))
    }

    @Test fun `all four LLM protocols apply the configured effort`() {
        val cfg = settings("https://api.openai.com/v1", "gpt-5", ThinkingEffort.HIGH)
        val input = AnalysisInput("示例", "sample")
        val context = ReplyContext("sample", listOf(ReplyMessage(1, "我", 0, "示例")))
        val time = TopicTimeContext(java.time.ZonedDateTime.parse("2026-11-12T12:00:00Z"))
        listOf(LlmEmotionProtocol.payload(input, cfg), IntentProtocol.payload(input, cfg),
            ReplyProtocol.payload(cfg, context, "", "", ""),
            TopicProtocol.payload(cfg, context, "", "", "", ReplyRelationship.UNSPECIFIED, time)).forEach {
            assertEquals("high", it.getString("reasoning_effort"))
        }
    }
}
