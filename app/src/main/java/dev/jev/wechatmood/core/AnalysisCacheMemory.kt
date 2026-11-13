package dev.jev.wechatmood.core

/** Content-keyed prefetch results; failure expiry remains anchored to the original write. */
class AnalysisCacheMemory(private val capacity: Int = 512,
    private val now: () -> Long = System::currentTimeMillis) {
    private val results = LinkedHashMap<AnalysisCacheKey, CachedAnalysisResult>(16, 0.75f, true)
    init { require(capacity > 0) }
    @Synchronized fun find(key: AnalysisCacheKey): CachedAnalysisResult? {
        val result = results[key] ?: return null
        if (result.isExpired(now())) { results.remove(key); return null }
        return result
    }
    @Synchronized fun save(key: AnalysisCacheKey, result: CachedAnalysisResult) {
        if (result.isExpired(now())) return
        results[key] = result
        while (results.size > capacity) results.remove(results.keys.first())
    }
    @Synchronized fun remove(key: AnalysisCacheKey) { results.remove(key) }
    @Synchronized fun clear() { results.clear() }
}
