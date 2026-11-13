package dev.jev.wechatmood.core

import dev.jev.wechatmood.analysis.AnalysisQueue
import dev.jev.wechatmood.reply.ReplySettings
import dev.jev.wechatmood.reply.ThinkingEffort
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class ContentAnalysisCacheTest {
    @get:Rule val folder = TemporaryFolder()
    private val input = AnalysisInput("好 👩🏽‍💻", "alice", messageId = 1, createdAt = 100,
        accountScope = AnalysisCacheKey.digest("account"))
    private val settings = RuntimeSettings(1, false,
        ApiSettings.fromInput(ApiSettings.DEFAULT_ENDPOINT, "key"), "install")
    private val mood = Mood("开心", 0.8, 0, "", "意图解析：同意\n可能在意：安排\n情绪倾向：开心")
    private fun key(request: AnalysisInput = input) = requireNotNull(AnalysisCacheKey.of(request, settings))
    private fun jdbc() = PersistentAnalysisCacheTest.Jdbc(folder.newFile())

    @Test fun `key follows exact SHA256 v4 source and preserves emoji`() {
        val request = input.copy(text = "\u3000好\t\n\u00a0👩🏽‍💻\u0085 ")
        assertEquals("好 👩🏽‍💻", AnalysisCacheKey.normalize(request.text))
        val source = "analysis-v4|${AnalysisCacheKey.conversationKey(input)}|好 👩🏽‍💻|${settings.analysisFingerprint()}"
        val expected = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, key(request).value)
        assertEquals(key(), key(request))
        assertNotEquals(key(), key(input.copy(text = "好 👩💻")))
    }

    @Test fun `same conversation text reuses result across metadata and context changes`() {
        val repeated = input.copy(messageId = 999, createdAt = 5000, speaker = "其他人", zoneId = "UTC",
            context = listOf(ContextMessage("我", "新的前文")), quoted = QuotedMessage("引用", "我", 1, "42"),
            coverage = ContextCoverage(truncated = true),
            background = dev.jev.wechatmood.reply.ContactBackground("不同背景", "r2"))
        SqliteAnalysisCache(jdbc()).use {
            it.save(key(), mood, "original evidence")
            assertEquals(mood, it.find(key(repeated))?.mood)
            assertEquals("original evidence", it.find(key(repeated))?.evidence)
        }
    }

    @Test fun `thinking effort and roles revision isolate content results`() {
        fun config(effort: ThinkingEffort, roles: Long = 0) = RuntimeSettings(1, false, settings.api, "install",
            intent = IntentSettings(IntentRoute.LLM, ReplySettings.fromInput("https://example.com/v1", "key", "model", effort)),
            rolesRevision = roles)
        assertNotEquals(AnalysisCacheKey.of(input, config(ThinkingEffort.MINIMAL)), AnalysisCacheKey.of(input, config(ThinkingEffort.HIGH)))
        assertNotEquals(AnalysisCacheKey.of(input, config(ThinkingEffort.MINIMAL)), AnalysisCacheKey.of(input, config(ThinkingEffort.MINIMAL, 1)))
    }

    @Test fun `new key wins over older results and performs no promotion`() {
        SqliteAnalysisCache(jdbc()).use { store ->
            store.save(key(), mood, "new")
            AnalysisCacheKey.legacyKeys(input, settings).forEach { store.save(it, mood.copy(detail = "old"), "old") }
            val reads = mutableListOf<AnalysisCacheKey>()
            val result = AnalysisCacheLookup.find(input, settings, { reads += it; store.find(it) }) { _, _ -> fail("Must not promote") }
            assertEquals("new", result?.evidence)
            assertEquals(listOf(key()), reads)
        }
    }

    @Test fun `all legacy formats promote without changing original evidence or timestamp`() {
        AnalysisCacheKey.legacyKeys(input, settings).forEach { legacy ->
            SqliteAnalysisCache(jdbc(), now = { 1000L }).use { store ->
                store.save(legacy, mood, "original")
                val result = AnalysisCacheLookup.find(input, settings, store::find, store::save)
                assertEquals("original", result?.evidence)
                assertEquals(1000L, result?.createdAt)
                assertEquals(result, store.find(key()))
            }
        }
    }

    @Test fun `upgrade from pre thinking fingerprint also accounts for input settings fingerprint`() {
        val oldFingerprint = requireNotNull(settings.legacyAnalysisFingerprint())
        val current = input.copy(settingsFingerprint = settings.analysisFingerprint())
        val old = current.copy(settingsFingerprint = oldFingerprint, context = emptyList(), coverage = ContextCoverage())
        val legacy = AnalysisCacheKey(AnalysisCacheKey.digest("analysis-result-v2", old.key, oldFingerprint))
        SqliteAnalysisCache(jdbc()).use { store ->
            store.save(legacy, mood, "2.6.9 evidence")
            assertEquals("2.6.9 evidence", AnalysisCacheLookup.find(current, settings, store::find, store::save)?.evidence)
        }
    }

    @Test fun `failure survives reopen and frequent reads cannot extend its five minute TTL`() {
        val file = folder.newFile()
        var clock = 1000L
        SqliteAnalysisCache(PersistentAnalysisCacheTest.Jdbc(file), now = { clock }).use {
            it.save(key(), CachedAnalysisResult.failure(), "{}")
        }
        SqliteAnalysisCache(PersistentAnalysisCacheTest.Jdbc(file), now = { clock }).use { store ->
            clock += 299_999
            val cached = requireNotNull(store.find(key()))
            assertTrue(cached.mood.intentFailed)
            assertEquals(CachedAnalysisResult.RETRY_TEXT, cached.mood.detail)
            assertEquals(1000L, cached.createdAt)
            assertNotNull(store.find(key()))
            clock++
            assertNull(store.find(key()))
        }
    }

    @Test fun `failure promotion and memory prefetch retain original expiry`() {
        var clock = 1000L
        val memory = AnalysisCacheMemory(now = { clock })
        SqliteAnalysisCache(jdbc(), now = { clock }).use { store ->
            store.save(AnalysisCacheKey.legacyKeys(input, settings).first(), CachedAnalysisResult.failure(), "{}")
            clock += 299_999
            val result = requireNotNull(AnalysisCacheLookup.find(input, settings, store::find, store::save))
            memory.save(key(), result)
            assertNotNull(memory.find(key()))
            clock++
            assertNull(memory.find(key()))
            assertNull(store.find(key()))
        }
    }

    @Test fun `success has no failure TTL while configured failure TTL is honored`() {
        var clock = 100L
        SqliteAnalysisCache(jdbc(), now = { clock }, failureTtlMillis = 50L).use { store ->
            store.save(key(), mood, "{}")
            val failureKey = key(input.copy(text = "失败"))
            store.save(failureKey, CachedAnalysisResult.failure(), "{}")
            clock += 50
            assertNotNull(store.find(key()))
            assertNull(store.find(failureKey))
        }
    }

    @Test fun `migration of old table retains payload and uses accessed for original creation`() {
        val db = jdbc()
        db.execute("CREATE TABLE analysis_results (cache_key TEXT PRIMARY KEY NOT NULL, payload TEXT NOT NULL, accessed INTEGER NOT NULL)")
        db.execute("INSERT INTO analysis_results VALUES (?, ?, ?)", listOf(key().value, CachedAnalysisResult(mood, "old").encode(), "42"))
        SqliteAnalysisCache(db, now = { 1000 }).use { store ->
            assertEquals(mood, store.find(key())?.mood)
            assertEquals(42L, store.find(key())?.createdAt)
            assertEquals("1000", db.query("SELECT accessed FROM analysis_results"))
            assertEquals("42", db.query("SELECT created_at FROM analysis_results"))
        }
    }

    @Test fun `prefetched complete results render from memory without queue or model call`() {
        MoodStore.clear()
        val memory = AnalysisCacheMemory()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            SqliteAnalysisCache(jdbc()).use { store ->
                store.save(key(), mood, "original")
                memory.save(key(), requireNotNull(store.find(key())))
            }
            val rebuilt = input.copy(messageId = 100, createdAt = 200, context = listOf(ContextMessage("我", "新增")))
            assertTrue(MoodStore.restore(rebuilt.key, requireNotNull(memory.find(key(rebuilt)))))
            val queue = AnalysisQueue(scope, { true }, { fail("Cache hit must not call model"); mood })
            queue.submit(rebuilt)
            assertEquals(mood, MoodStore.get(rebuilt.key))
            assertFalse(MoodStore.get(rebuilt.key)!!.detail.contains("分析中"))
        } finally { scope.cancel(); MoodStore.clear() }
    }

    @Test fun `explicit retry revokes only target claim and ignores stored failure`() {
        MoodStore.clear()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var calls = 0
            val queue = AnalysisQueue(scope, { true }, { calls++; mood })
            val memory = AnalysisCacheMemory()
            val failure = CachedAnalysisResult(CachedAnalysisResult.failure(), "{}", System.currentTimeMillis())
            memory.save(key(), failure)
            MoodStore.restore(input.key, failure)
            queue.submit(input)
            assertEquals(0, calls)
            queue.cancelMessage(input.key)
            MoodStore.invalidate(input.key)
            memory.remove(key())
            assertNull(memory.find(key()))
            queue.submit(input)
            assertEquals(1, calls)
            assertEquals(mood, MoodStore.get(input.key))
        } finally { scope.cancel(); MoodStore.clear() }
    }

    @Test fun `memory capacity evicts least recently used content`() {
        val memory = AnalysisCacheMemory(capacity = 2)
        val first = key(); val second = key(input.copy(text = "第二条")); val third = key(input.copy(text = "第三条"))
        memory.save(first, CachedAnalysisResult(mood, "{}"))
        memory.save(second, CachedAnalysisResult(mood, "{}"))
        assertNotNull(memory.find(first))
        memory.save(third, CachedAnalysisResult(mood, "{}"))
        assertNull(memory.find(second))
        assertNotNull(memory.find(first))
        assertNotNull(memory.find(third))
    }

    @Test fun `untranscribed voices cannot share a placeholder content key`() {
        val voice = dev.jev.wechatmood.voice.VoiceSource("alice", 1, 100, 0, "audio")
        assertNull(AnalysisCacheKey.of(input.copy(voice = voice, voiceState = dev.jev.wechatmood.voice.VoiceState.WAITING), settings))
        assertEquals(key(), key(input.copy(voice = voice, voiceState = dev.jev.wechatmood.voice.VoiceState.READY)))
    }
}
