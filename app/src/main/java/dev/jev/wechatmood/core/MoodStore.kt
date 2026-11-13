package dev.jev.wechatmood.core

import java.util.concurrent.ConcurrentHashMap

/**
 * 分析结果。
 *
 * [label] 是给界面看的短标签（比如「开心」「生气」「敷衍」），
 * [score] 是情绪强度 -1.0（负面）到 1.0（正面），[raw] 留着排查模型返回。
 */
data class Mood(
    val label: String,
    val score: Double,
    val risk: Int,
    val raw: String,
    val detail: String = label,
    val intentFailed: Boolean = false,
    val emotions: Map<String, Double> = emptyMap(),
) {
    // Recomputed on construction/copy, including legacy cached results and rule-only labels.
    val distribution: List<EmotionScore> = EmotionDistribution.normalize(emotions, label, detail.substringBefore("语音转写："))
}

/**
 * 分析结果缓存。
 *
 * 界面层仍按完整输入绑定卡片；内容缓存负责跨消息与页面复用。
 * 先恢复缓存，未命中才显示 loading 并请求模型。失败五分钟后过期。
 */
object MoodStore {

    private val cache = ConcurrentHashMap<String, Mood>()
    private val failureExpires = ConcurrentHashMap<String, Long>()
    private val pending = mutableMapOf<String, Claim>()

    class Claim internal constructor(val key: String)

    /** Length-prefix every field so different contexts or message identities never share a result. */
    fun keyOf(text: String, talker: String?, context: List<ContextMessage> = emptyList(),
        messageId: Long = 0, speaker: String = "对方", createdAt: Long = 0,
        coverage: ContextCoverage = ContextCoverage(), zoneId: String = java.util.TimeZone.getDefault().id,
        quoted: QuotedMessage? = null, accountScope: String = ""): String {
        val source = buildString {
            fun field(value: String) { append(value.length).append(':').append(value) }
            fun quote(value: QuotedMessage?) {
                field(if (value == null) "no-quote" else "quoted-v1")
                if (value != null) {
                    field(value.text.orEmpty()); field(value.displayName.orEmpty()); field(value.type.toString())
                    field(value.serverId.orEmpty()); field(value.unavailableReason.orEmpty())
                }
            }
            field(talker.orEmpty())
            field(accountScope)
            field(messageId.toString())
            field(speaker)
            field(text)
            field("temporal-intent-v1")
            field(createdAt.toString())
            field(zoneId)
            field(coverage.toString())
            quote(quoted)
            context.forEach {
                field(it.speaker); field(it.text); field(it.createdAt.toString()); field(it.messageId.toString())
                it.voice?.let { source -> field(source.key); field(it.voiceState.name) }
                quote(it.quoted)
            }
        }
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    @Synchronized fun get(key: String): Mood? {
        if (failureExpires[key]?.let { System.currentTimeMillis() >= it } == true) invalidate(key)
        return cache[key]
    }

    @Synchronized fun restore(key: String, result: CachedAnalysisResult): Boolean {
        if (pending.containsKey(key) || result.isExpired(System.currentTimeMillis())) return false
        cache[key] = result.mood
        if (result.mood.intentFailed) failureExpires[key] = result.createdAt + CachedAnalysisResult.FAILURE_TTL_MILLIS
        else failureExpires.remove(key)
        return true
    }

    @Synchronized fun invalidate(key: String) { cache.remove(key); failureExpires.remove(key) }

    @Synchronized fun failureDeadline(key: String, result: CachedAnalysisResult) {
        if (result.mood.intentFailed) failureExpires[key] = result.createdAt + CachedAnalysisResult.FAILURE_TTL_MILLIS
    }

    @Synchronized fun retryIntent(key: String) { if (get(key)?.intentFailed == true) invalidate(key) }

    /** 尝试认领一次分析任务；已经在跑或已完成返回 null。 */
    @Synchronized fun acquire(key: String): Claim? {
        if (get(key) != null || pending.containsKey(key)) return null
        return Claim(key).also { pending[key] = it }
    }

    @Synchronized fun complete(claim: Claim, mood: Mood): Boolean {
        if (pending[claim.key] !== claim) return false
        cache[claim.key] = mood
        if (mood.intentFailed) failureExpires.putIfAbsent(claim.key, System.currentTimeMillis() + CachedAnalysisResult.FAILURE_TTL_MILLIS)
        else failureExpires.remove(claim.key)
        pending.remove(claim.key)
        return true
    }

    /** 失败也要释放认领，否则这条消息永远不会重试。 */
    @Synchronized fun release(claim: Claim): Boolean {
        if (pending[claim.key] !== claim) return false
        pending.remove(claim.key)
        return true
    }

    fun size(): Int = cache.size

    @Synchronized fun clear() {
        cache.clear()
        failureExpires.clear()
        pending.clear()
    }
}
