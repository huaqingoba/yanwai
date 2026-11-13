package dev.jev.wechatmood.analysis

import dev.jev.wechatmood.core.*
import kotlinx.coroutines.*
import dev.jev.wechatmood.hook.NativeVoiceBridge
import dev.jev.wechatmood.voice.VoicePreparation
import java.util.concurrent.ConcurrentHashMap

object SignalAnalyzer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = JevHttpClient()
    private val llmClient = dev.jev.wechatmood.reply.ReplyHttpClient()
    private class Stage(@Volatile var text: String) {
        @Volatile var emotion: Mood? = null
    }
    private val stages = ConcurrentHashMap<String, Stage>()
    private val forced = ConcurrentHashMap.newKeySet<String>()
    private var prefetchJob: Job? = null
    private var prefetchedKey: AnalysisCacheKey? = null
    private val queue = AnalysisQueue(scope, ModulePrefs::canAnalyze, { input ->
        ModulePrefs.requestReload()
        analyze(input) {
            ModulePrefs.requestReload()
            ModulePrefs.canAnalyze(input)
        }
    }, onComplete = { mood ->
        MoodLog.i("聊天解读完成：${mood.label}；intentFailed=${mood.intentFailed}")
        ModulePrefs.report(if (mood.intentFailed) CachedAnalysisResult.RETRY_TEXT else "${ModulePrefs.analysisSettings()?.emotion?.source?.label}分析完成，已缓存 ${MoodStore.size()} 条")
        scope.launch(Dispatchers.Main) { dev.jev.wechatmood.hook.MessageSniffer.refresh() }
    }, onFailure = { error ->
        MoodLog.e("分析失败：${error.message}")
        ModulePrefs.report("分析失败：${error.message}")
    })
    fun failure(key: String): String? = queue.failure(key)
    fun retryFailure(key: String) { MoodStore.retryIntent(key); queue.retryFailure(key); NativeVoiceBridge.retryFailures() }
    fun cachedMood(input: AnalysisInput): Mood? {
        MoodStore.get(input.key)?.let { return it }
        if (input.key in forced || stages.containsKey(input.key)) return null
        val settings = ModulePrefs.analysisSettings() ?: return null
        val result = AnalysisResultCache.peek(input, settings) ?: return null
        return if (MoodStore.restore(input.key, result)) result.mood else MoodStore.get(input.key)
    }
    /** Explicit retries revoke the old owner and skip both disk and memory caches once. */
    fun reanalyze(input: AnalysisInput) {
        prefetchJob?.cancel(); prefetchedKey = null
        queue.cancelMessage(input.key)
        stages.remove(input.key)
        MoodStore.invalidate(input.key)
        ModulePrefs.analysisSettings()?.let { AnalysisResultCache.invalidate(input, it) }
        retryFailure(input.key)
        forced.add(input.key)
        submit(input)
    }
    /** Cache-only IO: entering a chat with auto analysis disabled never starts a model request. */
    fun prefetch(input: AnalysisInput) {
        val settings = ModulePrefs.analysisSettings() ?: return
        val key = AnalysisCacheKey.of(input, settings) ?: return
        if (key == prefetchedKey || input.key in forced) return
        prefetchJob?.cancel()
        prefetchedKey = key
        prefetchJob = scope.launch {
            if (!dev.jev.wechatmood.hook.ReplyDatabaseHistory.matchesAnalysisAccount(input)) return@launch
            val result = AnalysisResultCache.find(input, settings)
            ensureActive()
            if (result != null && ModulePrefs.analysisSettings()?.sameAnalysis(settings) == true)
                MoodStore.restore(input.key, result)
            withContext(Dispatchers.Main) { dev.jev.wechatmood.hook.MessageSniffer.refresh() }
        }
    }
    fun progress(key: String): String? = stages[key]?.text
    fun partialMood(key: String): Mood? = stages[key]?.emotion
    fun reconcile(visibleKeys: Set<String>, talker: String?) =
        if (talker == null) queue.cancelAll() else queue.reconcile(visibleKeys, talker)
    fun cancelConversation(talker: String) = queue.cancelConversation(talker)
    fun cancelAll() { queue.cancelAll(); prefetchJob?.cancel(); prefetchedKey = null }
    fun resetSettings() {
        cancelAll(); queue.resetSettings(); stages.clear(); forced.clear(); AnalysisResultCache.clearMemory()
    }

    fun submit(input: AnalysisInput, stillVisible: () -> Boolean = { true }): String? {
        if (!ModulePrefs.canAnalyze(input)) return null
        if (MessagePolicy.textOrNull(input.text) == null) return null
        val key = input.key
        if (cachedMood(input) != null) return key
        queue.submit(input, stillVisible)
        return key
    }

    suspend fun requestMood(text: String, context: List<ContextMessage> = emptyList(),
        speaker: String = "对方"): Mood {
        val snapshot = requireNotNull(ModulePrefs.analysisSettings()) { "设置尚未连接" }
        return AnalysisRouter.analyze(AnalysisInput(text, "sample", context, speaker = speaker), snapshot,
            { client.exchangeSuspending(it, snapshot.api) }, { settings, payload -> llmClient.request(settings, payload) { it } })
    }

    private suspend fun analyze(input: AnalysisInput, shouldContinue: () -> Boolean = { true }): Mood = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()
        // Keep both rounds on the same endpoint and credential, even if settings change mid-request.
        val snapshot = requireNotNull(ModulePrefs.analysisSettings()) { "设置尚未连接" }
        val settings = snapshot.api
        fun active(): Boolean { job.ensureActive(); return shouldContinue() && ModulePrefs.analysisSettings()?.sameAnalysis(snapshot) == true }
        check(snapshot.canAnalyze) { "请先配置所选分析模型" }
        if (!active()) throw CancellationException("分析已关闭或配置已改变")
        val verifiedAccount = dev.jev.wechatmood.hook.ReplyDatabaseHistory.matchesAnalysisAccount(input)
        val bypassCache = forced.remove(input.key)
        if (verifiedAccount && !bypassCache) {
            val cached = AnalysisResultCache.find(input, snapshot)
            if (!active()) throw CancellationException("分析已关闭或配置已改变")
            if (cached != null) {
                MoodStore.failureDeadline(input.key, cached)
                return@withContext cached.mood
            }
        } else if (!verifiedAccount) MoodLog.i("ANALYSIS_CACHE_ACCOUNT_UNVERIFIED 当前消息账号未核对，暂用内存缓存")
        val stage = Stage("正在分析…")
        stages[input.key] = stage
        try {
            val total = input.context.count { it.voice != null } + if (input.voice != null) 1 else 0
            var completed = 0
            val prepared = VoicePreparation.analysis(input) { source ->
                job.ensureActive()
                if (!active()) throw CancellationException("分析已关闭或配置已改变")
                stage.text = "正在转写语音 ${++completed}/$total…"
                NativeVoiceBridge.transcribe(source, shouldContinue)
            }
            if (input.voice != null && verifiedAccount && !bypassCache) {
                val cached = AnalysisResultCache.find(prepared, snapshot)
                if (!active()) throw CancellationException("分析已关闭或配置已改变")
                if (cached != null) {
                    MoodStore.failureDeadline(input.key, cached)
                    return@withContext cached.mood
                }
            }
            stage.text = "正在分析…"
            val mood = AnalysisRouter.analyze(prepared, snapshot,
                { client.exchangeSuspending(it, settings) },
                { config, payload -> llmClient.request(config, payload) { it } }, ::active,
                { emotion ->
                    stage.text = emotion.detail.substringAfter('\n') + "\n智能分析中…"
                    stage.emotion = emotion.copy(detail = emotion.detail + "\n智能分析中…")
                })
            val note = buildString {
                if (input.voice != null) append("\n\n语音转写：${prepared.text}\n（仅根据转写文字分析）")
                if (prepared.coverage.unavailableVoice > 0) append("\n前文有 ${prepared.coverage.unavailableVoice} 条语音未能转写，分析依据不完整。")
            }
            val result = mood.copy(detail = mood.detail + note)
            if (!active()) throw CancellationException("分析已关闭或配置已改变")
            if (verifiedAccount) {
                AnalysisResultCache.save(prepared, snapshot, result, AnalysisState.build(prepared).toString())
            }
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!active()) throw CancellationException("分析已关闭或配置已改变")
            MoodLog.e("分析失败：${e.message}")
            val result = stage.emotion?.let { it.copy(intentFailed = true,
                detail = it.detail.replace("智能分析中…", CachedAnalysisResult.RETRY_TEXT)) }
                ?: CachedAnalysisResult.failure()
            if (verifiedAccount) AnalysisResultCache.save(input, snapshot, result, AnalysisState.build(input).toString())
            result
        } finally {
            stages.remove(input.key, stage)
        }
    }

}
