package dev.jev.wechatmood.analysis

import dev.jev.wechatmood.reply.ReplyHttpClient
import dev.jev.wechatmood.reply.ReplySettings
import dev.jev.wechatmood.reply.ThinkingEffort
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.TimeUnit

/** JVM-only transport coverage; no Android context, credentials or external API calls. */
class ThinkingFallbackTransportTest {
    private fun settings(server: MockWebServer, model: String = "custom-model") = ReplySettings.fromInput(
        server.url("/${UUID.randomUUID()}/v1").toString(), "fake-key", model)

    private fun body(server: MockWebServer): JSONObject = JSONObject(
        requireNotNull(server.takeRequest(2, TimeUnit.SECONDS)).body.readUtf8())

    @Test fun `400 and 422 each retry once and cached endpoint omits parameters even from an old payload`() = runBlocking {
        for (code in listOf(400, 422)) MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(code).setBody("""{"error":{"message":"Unknown field: thinking / reasoning_effort"}}"""))
            server.enqueue(MockResponse().setBody("fallback-ok"))
            server.enqueue(MockResponse().setBody("cached-ok"))
            val cfg = settings(server)
            val original = AnalysisThinking.apply(cfg, JSONObject().put("model", cfg.model)
                .put("messages", org.json.JSONArray().put(JSONObject().put("role", "user").put("content", "sample"))))
            val snapshot = original.toString()
            assertEquals("fallback-ok", ReplyHttpClient().request(cfg, original) { it })
            // A new client reuses the process-wide capability cache, and transport strips a stale payload.
            assertEquals("cached-ok", ReplyHttpClient().request(cfg, original) { it })
            assertEquals(snapshot, original.toString())
            assertEquals(3, server.requestCount)
            val first = body(server)
            assertEquals("minimal", first.getString("reasoning_effort"))
            assertEquals("disabled", first.getJSONObject("thinking").getString("type"))
            repeat(2) {
                val next = body(server)
                assertFalse(next.has("reasoning_effort")); assertFalse(next.has("thinking"))
                assertEquals(first.getJSONArray("messages").toString(), next.getJSONArray("messages").toString())
                assertEquals(cfg.model, next.getString("model"))
            }
            assertEquals(0, AnalysisThinking.apply(cfg, JSONObject()).length())
        }
    }

    @Test fun `compatible OpenAI model also falls back when a custom endpoint rejects reasoning`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(400).setBody("unsupported reasoning_effort"))
            server.enqueue(MockResponse().setBody("ok"))
            val cfg = settings(server, "gpt-5.4")
            assertEquals("ok", ReplyHttpClient().request(cfg, JSONObject().put("model", cfg.model)) { it })
            assertEquals("minimal", body(server).getString("reasoning_effort"))
            assertFalse(body(server).has("reasoning_effort"))
            val high = ReplySettings.fromInput(cfg.endpoint, cfg.apiKey, cfg.model, ThinkingEffort.HIGH)
            assertFalse(AnalysisThinking.apply(high, JSONObject()).has("reasoning_effort"))
        }
    }

    @Test fun `second rejection stops after one retry and preserves the existing HTTP error message`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            repeat(2) { server.enqueue(MockResponse().setResponseCode(422).setBody("unknown field thinking")) }
            // A spare response avoids hanging if a regression accidentally makes a third attempt.
            server.enqueue(MockResponse().setBody("unexpected third attempt"))
            try {
                ReplyHttpClient().request(settings(server), JSONObject()) { it }
                fail("Second rejection must fail")
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!.contains("接口地址、模型或上下文长度"))
            }
            assertEquals(2, server.requestCount)
            assertTrue(body(server).has("thinking")); assertFalse(body(server).has("thinking"))
        }
    }

    @Test fun `401 429 5xx and unrelated 400 or 422 never retry or cache unsupported capability`() = runBlocking {
        for ((code, error) in listOf(401 to "thinking", 429 to "reasoning", 500 to "unknown field",
            400 to "invalid model", 422 to "context too long")) MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(code).setBody(error))
            server.enqueue(MockResponse().setBody("unexpected retry"))
            val cfg = settings(server)
            try {
                ReplyHttpClient().request(cfg, JSONObject()) { it }
                fail("HTTP $code must fail")
            } catch (_: IllegalStateException) { }
            assertEquals(1, server.requestCount)
            assertTrue(AnalysisThinking.apply(cfg, JSONObject()).has("thinking"))
        }
    }

    @Test fun `cancellation during fallback cancels its network call`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(400).setBody("unknown field thinking"))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val job = launch {
                ReplyHttpClient().request(settings(server), JSONObject()) { it }
                fail("Canceled fallback must not complete")
            }
            try {
                withTimeout(2000) { while (server.requestCount < 2) delay(10) }
                job.cancelAndJoin()
                assertTrue(job.isCancelled)
            } finally { job.cancelAndJoin() }
        }
    }
}
