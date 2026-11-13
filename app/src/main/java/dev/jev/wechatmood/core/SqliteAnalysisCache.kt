package dev.jev.wechatmood.core

import org.json.JSONObject
import java.io.Closeable
import java.security.MessageDigest

interface AnalysisCacheDatabase : Closeable {
    fun execute(sql: String, args: List<String> = emptyList())
    fun query(sql: String, args: List<String> = emptyList()): String?
}

/** No credentials or account paths are stored: conversation and settings are hashes. */
data class AnalysisCacheKey(val value: String) {
    companion object {
        private val whitespace = Regex("[\\s\\p{Z}\\u0085]+")
        fun normalize(text: String): String = text.replace(whitespace, " ").trim()
        fun conversationKey(input: AnalysisInput): String = digest(input.accountScope, input.talker)
        fun digest(vararg values: String): String {
            val source = values.joinToString("") { "${it.length}:$it" }
            return MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
        fun of(input: AnalysisInput, settings: RuntimeSettings): AnalysisCacheKey? {
            // A waiting voice marker is shared by every voice; only actual transcript text is content.
            if (input.voice != null && input.voiceState != dev.jev.wechatmood.voice.VoiceState.READY) return null
            if (!input.accountScope.matches(Regex("[0-9a-f]{64}")) || input.talker.isBlank() || normalize(input.text).isEmpty()) return null
            val source = "analysis-v4|${conversationKey(input)}|${normalize(input.text)}|${settings.analysisFingerprint()}"
            return AnalysisCacheKey(MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) })
        }
        internal fun legacyKeys(input: AnalysisInput, settings: RuntimeSettings): List<AnalysisCacheKey> {
            if (of(input, settings) == null || input.messageId <= 0 || input.createdAt <= 0) return emptyList()
            return listOfNotNull(settings.analysisFingerprint(), settings.legacyAnalysisFingerprint()).distinct().flatMap { fingerprint ->
                val oldInput = if (input.settingsFingerprint.isBlank()) input else input.copy(settingsFingerprint = fingerprint)
                val identity = oldInput.copy(context = emptyList(), coverage = ContextCoverage()).key
                listOf(
                    AnalysisCacheKey(digest("analysis-result-v3-emotion-distribution", identity, fingerprint)),
                    AnalysisCacheKey(digest("analysis-result-v2", identity, fingerprint)),
                    AnalysisCacheKey(digest("analysis-result-v2", oldInput.key, fingerprint)),
                )
            }.distinct()
        }
    }
}

/** Upgrade an exact old-format hit without discarding its original evidence or rerunning models. */
object AnalysisCacheLookup {
    fun find(input: AnalysisInput, settings: RuntimeSettings,
        read: (AnalysisCacheKey) -> CachedAnalysisResult?,
        promote: (AnalysisCacheKey, CachedAnalysisResult) -> Unit): CachedAnalysisResult? {
        val key = AnalysisCacheKey.of(input, settings) ?: return null
        read(key)?.let { return it }
        val result = AnalysisCacheKey.legacyKeys(input, settings).firstNotNullOfOrNull(read) ?: return null
        // A failed best-effort promotion must not turn a usable cached result into a model call.
        runCatching { promote(key, result) }
        return result
    }
}

data class CachedAnalysisResult(val mood: Mood, val evidence: String, val createdAt: Long = 0) {
    fun isExpired(now: Long, failureTtlMillis: Long = FAILURE_TTL_MILLIS): Boolean =
        mood.intentFailed && now - createdAt >= failureTtlMillis
    private val hasHeader get() = mood.detail.lineSequence().firstOrNull()?.matches(Regex("yanwai \\S+")) == true
    fun encode(): String = JSONObject().put("format", 1).put("label", mood.label).put("score", mood.score)
        .put("risk", mood.risk).put("detail", if (hasHeader) mood.detail.substringAfter('\n', "") else mood.detail)
        .put("version_header", hasHeader).put("emotions", JSONObject(mood.emotions))
        .put("intent_failed", mood.intentFailed).put("created_at", createdAt)
        .put("evidence", evidence).toString()
    companion object {
        const val FAILURE_TTL_MILLIS = 5 * 60 * 1000L
        const val RETRY_TEXT = "分析返回不完整，点击重试"
        fun failure(): Mood = Mood("不明确", 0.0, 0, "", RETRY_TEXT, intentFailed = true)
        fun decode(payload: String): CachedAnalysisResult {
            require(payload.toByteArray(Charsets.UTF_8).size <= 256 * 1024)
            val json = JSONObject(payload)
            require(json.getInt("format") == 1)
            val emotions = json.getJSONObject("emotions")
            val detail = (if (json.optBoolean("version_header")) dev.jev.wechatmood.analysis.JevProtocol.header + "\n" else "") + json.getString("detail")
            val mood = Mood(json.getString("label"), json.getDouble("score"), json.getInt("risk"), "",
                detail, intentFailed = json.optBoolean("intent_failed"),
                emotions = emotions.keys().asSequence().associateWith(emotions::getDouble))
            require(mood.score.isFinite() && mood.emotions.values.all { it.isFinite() && it in 0.0..1.0 })
            return CachedAnalysisResult(mood, json.getString("evidence"), json.optLong("created_at"))
        }
    }
}

/** Disk access is restricted to the analysis IO worker. Reads and pruning are serialized. */
class SqliteAnalysisCache(private val db: AnalysisCacheDatabase,
    private val capacity: Int = 5000, private val maxBytes: Long = 32L * 1024 * 1024,
    private val now: () -> Long = System::currentTimeMillis,
    private val failureTtlMillis: Long = CachedAnalysisResult.FAILURE_TTL_MILLIS) : Closeable {
    init {
        require(capacity > 0 && maxBytes > 0 && failureTtlMillis > 0)
        db.execute("CREATE TABLE IF NOT EXISTS analysis_results (cache_key TEXT PRIMARY KEY NOT NULL, payload TEXT NOT NULL, accessed INTEGER NOT NULL)")
        db.execute("CREATE INDEX IF NOT EXISTS analysis_accessed ON analysis_results(accessed)")
        if (db.query("SELECT name FROM pragma_table_info('analysis_results') WHERE name = 'created_at'") == null) {
            db.execute("ALTER TABLE analysis_results ADD COLUMN created_at INTEGER NOT NULL DEFAULT 0")
            db.execute("UPDATE analysis_results SET created_at = accessed")
        }
    }

    @Synchronized fun find(key: AnalysisCacheKey): CachedAnalysisResult? {
        val payload = db.query("SELECT payload FROM analysis_results WHERE cache_key = ?", listOf(key.value)) ?: return null
        val result = runCatching { CachedAnalysisResult.decode(payload).copy(createdAt =
            db.query("SELECT created_at FROM analysis_results WHERE cache_key = ?", listOf(key.value))!!.toLong()) }
            .getOrNull()?.takeUnless { it.isExpired(now(), failureTtlMillis) }
        if (result == null) db.execute("DELETE FROM analysis_results WHERE cache_key = ?", listOf(key.value))
        else db.execute("UPDATE analysis_results SET accessed = ? WHERE cache_key = ?", listOf(now().toString(), key.value))
        return result
    }

    @Synchronized fun save(key: AnalysisCacheKey, mood: Mood, evidence: String) {
        save(key, CachedAnalysisResult(mood, evidence, now()))
    }

    @Synchronized fun save(key: AnalysisCacheKey, result: CachedAnalysisResult) {
        if (result.isExpired(now(), failureTtlMillis)) return
        val payload = result.encode()
        if (payload.toByteArray(Charsets.UTF_8).size > minOf(256L * 1024, maxBytes)) return
        db.execute("BEGIN IMMEDIATE")
        try {
            db.execute("INSERT OR REPLACE INTO analysis_results(cache_key, payload, accessed, created_at) VALUES(?, ?, ?, ?)",
                listOf(key.value, payload, now().toString(), result.createdAt.toString()))
            db.execute("DELETE FROM analysis_results WHERE cache_key IN (SELECT cache_key FROM analysis_results ORDER BY accessed DESC, rowid DESC LIMIT -1 OFFSET $capacity)")
            while ((db.query("SELECT COALESCE(SUM(length(CAST(payload AS BLOB))), 0) FROM analysis_results")?.toLong() ?: 0) > maxBytes) {
                db.execute("DELETE FROM analysis_results WHERE cache_key IN (SELECT cache_key FROM analysis_results ORDER BY accessed ASC, rowid ASC LIMIT 1)")
            }
            db.execute("COMMIT")
        } catch (error: Exception) {
            runCatching { db.execute("ROLLBACK") }
            throw error
        }
    }

    @Synchronized override fun close() = db.close()
}
