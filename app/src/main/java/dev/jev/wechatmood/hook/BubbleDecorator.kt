package dev.jev.wechatmood.hook

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RelativeLayout
import dev.jev.wechatmood.analysis.SignalAnalyzer
import dev.jev.wechatmood.core.ModulePrefs
import dev.jev.wechatmood.core.MoodLog
import dev.jev.wechatmood.core.MoodStore
import dev.jev.wechatmood.core.AnalysisInput
import dev.jev.wechatmood.core.Mood
import dev.jev.wechatmood.core.CardPalette
import java.util.IdentityHashMap

/** Append a sibling below the real text bubble, without replacing a host row or ViewHolder. */
object BubbleDecorator {
    private data class Card(
        val key: String, val view: FrostedAnalysisView, val parent: ViewGroup,
        val anchor: View, val assignedId: Int?, val detach: View.OnAttachStateChangeListener,
        var message: AnalysisInput,
        val presentation: AnalysisCardMotion.Presentation = AnalysisCardMotion.Presentation(),
        var closing: Boolean = false, var rebinding: Boolean = false,
    )
    private val cards = IdentityHashMap<View, Card>()
    private val unsupported = mutableSetOf<String>()

    fun show(row: View, message: AnalysisInput?): Boolean {
        if (message == null || !ModulePrefs.shouldDisplay(message)) {
            if (cards[row]?.rebinding == true) clear(row) else dismiss(row)
            return false
        }
        val key = message.key
        val cached = SignalAnalyzer.cachedMood(message)
        val mood = cached ?: SignalAnalyzer.partialMood(key)
        // Hold off on creating a card during cache IO. Only a miss starts a loading card.
        if (mood == null && SignalAnalyzer.progress(key) == null && SignalAnalyzer.failure(key) == null &&
            ModulePrefs.canAnalyze(message)) {
            if (cards[row]?.key != key) clear(row)
            else restoreVisibility(requireNotNull(cards[row]))
            return true
        }
        val display = ModulePrefs.analysisSettings()?.cardDisplay ?: dev.jev.wechatmood.core.CardDisplaySettings()
        var state = cards[row]
        if (state != null && (state.key != key || state.view.parent !== state.parent || !isInside(state.parent, row))) {
            clear(row)
            state = null
        }
        if (state == null) {
            state = attach(row, message) ?: return false
            cards[row] = state
        }
        restoreVisibility(state)
        val settings = ModulePrefs.analysisSettings()
        state.view.updateOpacity(settings?.cardOpacity ?: dev.jev.wechatmood.core.CardOpacity.DEFAULT,
            settings?.cardPalette ?: CardPalette.DEFAULT)
        state.message = message
        val status = SignalAnalyzer.failure(key)?.let { "分析失败：$it\n点击此卡重试" }
            ?: if (ModulePrefs.canAnalyze(message))
            SignalAnalyzer.progress(key) ?: if (message.voice != null || message.context.any { it.voice != null }) "正在准备语音…" else "正在分析…"
            else if (!message.backgroundReady) "正在读取对方背景；读取失败时会重试"
            else "模型未配置或设置未连接"
        val loading = SignalAnalyzer.failure(key) == null && cached == null && ModulePrefs.canAnalyze(message)
        val content = AnalysisCardText.content(mood ?: Mood("不明确", 0.0, 0, "", status), display, loading)
        state.view.render(content, state.presentation.bind(content, cacheHit = cached != null))
        return true
    }

    private fun attach(row: View, message: AnalysisInput): Card? {
        val key = message.key
        val root = row as? ViewGroup ?: return null
        val anchor = findBubble(root) ?: return null
        val rowPos = IntArray(2).also { root.getLocationOnScreen(it) }
        val anchorPos = IntArray(2).also { anchor.getLocationOnScreen(it) }
        val left = (anchorPos[0] - rowPos[0]).coerceAtLeast(0)
        val width = minOf(dp(row, AnalysisCardMotion.WIDTH_DP), root.width - left - dp(row, 16))
        if (width < dp(row, 100)) return null
        val card = FrostedAnalysisView(row.context).apply {
            id = View.generateViewId()
            textSize = 13f
            minWidth = width // 300dp when space permits; never exceed the host row on narrow screens.
            setPadding(dp(row, AnalysisCardMotion.HORIZONTAL_PADDING_DP), dp(row, 7),
                dp(row, AnalysisCardMotion.HORIZONTAL_PADDING_DP), dp(row, 7))
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            setLineSpacing(dp(row, 1).toFloat(), 1f)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            setOnClickListener {
                val message = cards[row]?.takeIf { it.key == key }?.message ?: return@setOnClickListener
                if (SignalAnalyzer.failure(key) != null || MoodStore.get(key)?.intentFailed == true) {
                    refreshBackdrop()
                    SignalAnalyzer.reanalyze(message)
                    MessageSniffer.refresh()
                }
            }
        }
        var branch: View = anchor
        var parent = branch.parent as? ViewGroup
        var target: ViewGroup? = null
        var assignedId: Int? = null
        while (parent != null && isInside(parent, root)) {
            if (parent is LinearLayout && parent.orientation == LinearLayout.VERTICAL &&
                parent.layoutParams?.height == ViewGroup.LayoutParams.WRAP_CONTENT) {
                val parentPos = IntArray(2).also { parent.getLocationOnScreen(it) }
                val lp = LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    leftMargin = (anchorPos[0] - parentPos[0] - parent.paddingLeft).coerceAtLeast(0)
                    topMargin = dp(row, 3)
                    bottomMargin = dp(row, 6)
                }
                // Append only: do not shift the indexes of the host's original children.
                parent.addView(card, lp)
                target = parent
                break
            }
            if (parent === root) break
            branch = parent
            parent = branch.parent as? ViewGroup
        }
        if (target == null && root is RelativeLayout && branch.parent === root &&
            root.layoutParams?.height == ViewGroup.LayoutParams.WRAP_CONTENT) {
            val branchParams = branch.layoutParams as? RelativeLayout.LayoutParams ?: return null
            if (branchParams.getRule(RelativeLayout.ALIGN_PARENT_BOTTOM) != 0) return null
            if (branch.id == View.NO_ID) {
                assignedId = View.generateViewId()
                branch.id = assignedId
            }
            val lp = RelativeLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                addRule(RelativeLayout.BELOW, branch.id)
                addRule(RelativeLayout.ALIGN_PARENT_LEFT)
                leftMargin = left
                topMargin = dp(row, 3)
                bottomMargin = dp(row, 6)
            }
            root.addView(card, lp)
            target = root
        }
        if (target == null) {
            val signature = "${root.javaClass.name}/${anchor.parent?.javaClass?.name}"
            if (unsupported.add(signature)) MoodLog.w("暂不绘制未知气泡布局：$signature")
            return null
        }
        val detach = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { MessageSniffer.restoreBoundCard(v) }
            // RecyclerView temporarily detaches rows while scrolling. Keep their measured content.
            override fun onViewDetachedFromWindow(v: View) {
                // Older ListView adapters have no verified bind hook to clear recycled content.
                if (cards[v]?.closing == true || !MessageSniffer.hasBoundMessage(v)) clear(v)
            }
        }
        row.addOnAttachStateChangeListener(detach)
        return Card(key, card, target, branch, assignedId, detach, message)
    }

    private fun findBubble(root: ViewGroup): View? {
        val holder = root.tag
        if (holder != null) {
            val method = generateSequence(holder.javaClass as Class<*>) { it.superclass }
                .flatMap { it.declaredMethods.asSequence() }
                .firstOrNull { it.name == "getMainContainerView" && it.parameterCount == 0 }
            val main = runCatching { method?.isAccessible = true; method?.invoke(holder) as? View }.getOrNull()
            if (main != null && main !== root && main.isShown && isInside(main, root)) return main
        }
        fun find(view: View, depth: Int): View? {
            if (depth > 24 || view.visibility != View.VISIBLE) return null
            if (view.javaClass.name.endsWith(".MMNeat7extView")) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i), depth + 1)?.let { return it }
            return null
        }
        return find(root, 0)
    }

    private fun isInside(view: View, root: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === root) return true
            current = current.parent as? View
        }
        return false
    }

    /** Used only by an explicit reanalysis action, never by ordinary message-row refreshes. */
    fun refreshBackdrop(key: String) {
        cards.values.filter { it.key == key }.forEach { it.view.refreshBackdrop() }
    }

    /** Settings pushes redraw attached cards immediately, without binding content or taking a frame. */
    fun updateOpacity(opacity: Int, palette: CardPalette = CardPalette.DEFAULT) {
        cards.values.forEach { it.view.updateOpacity(opacity, palette) }
    }

    /** Hide stale data only for the synchronous host bind; same-message binding retains its root. */
    fun prepareForBind(row: View) {
        cards[row]?.let {
            it.view.finishAnimations()
            it.closing = false
            it.rebinding = true
            it.view.alpha = 0f
        }
    }

    private fun restoreVisibility(state: Card) {
        if (!state.closing && !state.rebinding) return
        state.view.finishAnimations()
        state.closing = false
        state.rebinding = false
    }

    /** Immediate cleanup is reserved for recycling, invalid bindings and detached-row pruning. */
    fun clear(row: View) {
        val state = cards.remove(row) ?: return
        state.view.finishAnimations()
        row.removeOnAttachStateChangeListener(state.detach)
        (state.view.parent as? ViewGroup)?.removeView(state.view)
        if (state.assignedId != null && state.anchor.id == state.assignedId) state.anchor.id = View.NO_ID
    }
    private fun dismiss(row: View) {
        val state = cards[row] ?: return
        if (state.closing) return
        state.closing = true
        state.view.close { if (cards[row] === state && state.closing) clear(row) }
    }
    fun clearAll() { cards.keys.toList().forEach(::dismiss) }
    fun prune() {
        // Bound retention for recycled rows; leaving the chat still clears every card.
        cards.keys.filter { !it.isAttachedToWindow }.drop(32).forEach(::clear)
    }
    private fun dp(view: View, n: Int) = (n * view.resources.displayMetrics.density).toInt()
}
