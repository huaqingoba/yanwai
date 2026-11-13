package dev.jev.wechatmood.hook

import android.annotation.SuppressLint
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.*
import android.text.Spanned
import android.view.Choreographer
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import java.util.WeakHashMap
import dev.jev.wechatmood.core.CardPalette
import dev.jev.wechatmood.core.MoodLog
import dev.jev.wechatmood.core.ModulePrefs

/** Static blurred backdrop with a dense tint; content fades independently of the surface. */
// Injected into WeChat with its host context; module AppCompat themes/resources are not available.
@SuppressLint("AppCompatCustomView")
class FrostedAnalysisView(context: Context) : TextView(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bounds = RectF()
    private val clip = Path()
    private val snapshotMatrix = Matrix()
    private val surface = AnalysisCardSurfaceState()
    private val snapshotState = surface.snapshotState
    private val dark get() = surface.palette.isDark(
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
    private var snapshot: Snapshot? = null
    private var backdrop: Backdrop? = null
    private var attachedRoot: View? = null
    private val radius get() = 12f * resources.displayMetrics.density
    private var contentAlpha = 1f
    private var contentAnimator: ValueAnimator? = null
    private var heightAnimator: ValueAnimator? = null
    private var desired: AnalysisCardMotion.Content? = null
    private var rendered: AnalysisCardMotion.Content? = null
    private var displayedHeight = 0
    private var targetHeight = 0
    private var loadingHeight = 0
    private var measuredCardWidth = 0
    private var smoothNextMeasure = false
    private var entrancePending = false

    init { setTextColor(if (dark) 0xFFF0F1F5.toInt() else 0xFF202229.toInt()) }

    fun updateOpacity(opacity: Int, palette: CardPalette = surface.palette) {
        val opacityChanged = surface.updateOpacity(opacity)
        val paletteChanged = surface.updatePalette(palette)
        if (paletteChanged) updateTextPalette()
        if (opacityChanged || paletteChanged) invalidate()
    }

    private fun updateTextPalette() {
        setTextColor(if (dark) 0xFFF0F1F5.toInt() else 0xFF202229.toInt())
        (text as? Spanned)?.getSpans(0, text.length, AnalysisCardLabelSpan::class.java)
            ?.forEach { it.dark = dark }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateTextPalette()
        invalidate()
    }

    private fun curve() = when (AnalysisCardMotion.curve) {
        AnalysisCardMotion.Curve.DECELERATE -> DecelerateInterpolator()
    }

    fun render(content: AnalysisCardMotion.Content, change: AnalysisCardMotion.Change) {
        if (change == AnalysisCardMotion.Change.NONE) return
        snapshotState.render(change)
        if (snapshotState.needsCapture) backdrop?.request(this)
        desired = content
        if (change == AnalysisCardMotion.Change.RINGS && rendered?.lines == content.lines &&
            rendered?.emotions?.map { it.label } == content.emotions.map { it.label }) {
            val spans = (text as? Spanned)?.getSpans(0, text.length, EmotionRingSpan::class.java)
            if (spans?.size == content.emotions.size) {
                spans.zip(content.emotions).forEach { (span, emotion) -> span.update(emotion) }
                rendered = content
                updateDescription(content)
                invalidate()
                return
            }
        }
        cancelContentAnimation()
        val animated = isAttachedToWindow && isShown && ValueAnimator.areAnimatorsEnabled()
        if (change == AnalysisCardMotion.Change.ENTER || change == AnalysisCardMotion.Change.DIRECT || !animated) {
            applyContent(content, false)
            contentAlpha = 1f
            if (change == AnalysisCardMotion.Change.ENTER && ValueAnimator.areAnimatorsEnabled()) {
                alpha = 0f
                scaleX = AnalysisCardMotion.ENTER_SCALE
                scaleY = AnalysisCardMotion.ENTER_SCALE
                translationY = AnalysisCardMotion.ENTER_TRANSLATION_DP * resources.displayMetrics.density
                entrancePending = true // Start only after the first nonzero content measurement.
            }
            invalidate()
        } else {
            fadeContent(0f, AnalysisCardMotion.CONTENT_OUT_MS) {
                applyContent(requireNotNull(desired), true)
                fadeContent(1f, AnalysisCardMotion.CONTENT_IN_MS)
            }
        }
    }

    private fun applyContent(content: AnalysisCardMotion.Content, smooth: Boolean) {
        loadingHeight = if (content.loading) maxOf(loadingHeight, displayedHeight) else 0
        smoothNextMeasure = smooth
        text = AnalysisCardText.format(content, resources.displayMetrics.density,
            (layoutParams?.width ?: measuredWidth) - paddingLeft - paddingRight, dark)
        rendered = content
        updateDescription(content)
    }

    private fun updateDescription(content: AnalysisCardMotion.Content) {
        contentDescription = content.lines.joinToString("\n") { line ->
            if (line.emotion) content.emotions.joinToString("，") { "${it.label} ${it.percent}" }
            else line.text
        }
    }

    private fun cancelContentAnimation() {
        contentAnimator?.removeAllListeners()
        contentAnimator?.cancel()
        contentAnimator = null
    }

    private fun fadeContent(to: Float, durationMs: Long, after: (() -> Unit)? = null) {
        val animator = ValueAnimator.ofFloat(contentAlpha, to).apply {
            duration = durationMs
            interpolator = curve()
            addUpdateListener { contentAlpha = it.animatedValue as Float; invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { contentAnimator = null; after?.invoke() }
            })
        }
        contentAnimator = animator
        animator.start()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val natural = maxOf(measuredHeight, loadingHeight)
        val cardWidth = measuredWidth
        if (displayedHeight == 0 || measuredCardWidth != cardWidth || !isAttachedToWindow || !ValueAnimator.areAnimatorsEnabled()) {
            heightAnimator?.cancel(); heightAnimator = null
            displayedHeight = natural
            targetHeight = natural
        } else if (natural != targetHeight) {
            heightAnimator?.cancel(); heightAnimator = null
            targetHeight = natural
            if (smoothNextMeasure) {
                val startHeight = displayedHeight
                heightAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = AnalysisCardMotion.HEIGHT_MS
                    interpolator = curve()
                    addUpdateListener {
                        displayedHeight = AnalysisCardMotion.heightAt(startHeight, natural, it.animatedValue as Float)
                        requestLayout()
                    }
                    start()
                }
            } else displayedHeight = natural
        }
        measuredCardWidth = cardWidth
        smoothNextMeasure = false
        setMeasuredDimension(cardWidth, displayedHeight)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (entrancePending && isAttachedToWindow) {
            entrancePending = false
            animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                .setDuration(AnalysisCardMotion.ENTER_MS).setInterpolator(curve()).start()
        }
    }

    /** Fade just the text/rings: the frosted card surface remains fully drawn. */
    override fun onDraw(canvas: Canvas) {
        if (contentAlpha >= 1f) { super.onDraw(canvas); return }
        val checkpoint = canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (contentAlpha * 255).toInt())
        super.onDraw(canvas)
        canvas.restoreToCount(checkpoint)
    }

    fun finishAnimations() {
        entrancePending = false
        animate().withEndAction(null).cancel()
        cancelContentAnimation()
        heightAnimator?.cancel(); heightAnimator = null
        val settledHeight = targetHeight
        val needsLayout = displayedHeight != settledHeight
        displayedHeight = settledHeight
        desired?.takeIf { it != rendered }?.let { applyContent(it, false) }
        alpha = 1f; scaleX = 1f; scaleY = 1f; translationY = 0f; contentAlpha = 1f
        if (needsLayout) requestLayout()
        invalidate()
    }

    fun close(after: () -> Unit) {
        // Closing during entry must fade from the current frame, never snap back to alpha/scale 1.
        entrancePending = false
        animate().withEndAction(null).cancel()
        cancelContentAnimation()
        heightAnimator?.cancel(); heightAnimator = null
        if (!isAttachedToWindow || !isShown || !ValueAnimator.areAnimatorsEnabled()) { after(); return }
        animate().alpha(0f).scaleX(AnalysisCardMotion.CLOSE_SCALE).scaleY(AnalysisCardMotion.CLOSE_SCALE)
            .setDuration(AnalysisCardMotion.CLOSE_MS).setInterpolator(curve()).withEndAction(after).start()
    }

    /** Explicit reanalysis can take one new snapshot; routine content/scroll refreshes cannot. */
    fun refreshBackdrop() {
        snapshotState.refresh()
        if (snapshotState.needsCapture) backdrop?.request(this)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        snapshotState.attach()
        val root = rootView
        attachedRoot = root
        backdrop = backdrops.getOrPut(root) { Backdrop(root) }.also { it.users++ }
        if (snapshotState.needsCapture) backdrop?.request(this)
    }

    override fun onDetachedFromWindow() {
        finishAnimations() // A recycled/detached card cannot return half faded or at an intermediate height.
        snapshotState.detach()
        val root = attachedRoot
        backdrop?.let {
            it.cancel(this)
            if (--it.users == 0) { it.close(); if (root != null) backdrops.remove(root) }
        }
        backdrop = null; attachedRoot = null
        super.onDetachedFromWindow()
    }

    override fun draw(canvas: Canvas) {
        if (capturing) return // Exclude all analysis surfaces AND their text from the host snapshot.
        if (surface.readSettings(ModulePrefs.analysisSettings())) updateTextPalette()
        val checkpoint = canvas.save()
        bounds.set(0f, 0f, width.toFloat(), height.toFloat().coerceAtLeast(1f))
        clip.reset(); clip.addRoundRect(bounds, radius, radius, Path.Direction.CW)
        canvas.clipPath(clip)
        paint.style = Paint.Style.FILL
        paint.alpha = 255 // Reset the translucent stroke alpha left by the previous frame.
        paint.shader = null
        val image = snapshot?.image?.takeUnless { it.isRecycled }
        val crop = image?.let { AnalysisCardBackdrop.centerCrop(it.width, it.height, bounds.width(), bounds.height()) }
        if (AnalysisCardBackdrop.drawsBackdrop(hasSnapshot = crop != null, opacity = surface.opacity)) {
            val transform = requireNotNull(crop)
            snapshotMatrix.setScale(transform.scale, transform.scale)
            snapshotMatrix.postTranslate(transform.translateX, transform.translateY)
            paint.color = Color.WHITE
            canvas.drawBitmap(requireNotNull(image), snapshotMatrix, paint)
        }
        // One full-card gradient: missing snapshots and 100% opacity use the opaque palette.
        val (top, bottom) = AnalysisCardBackdrop.colors(hasSnapshot = crop != null, opacity = surface.opacity, dark = dark)
        paint.shader = LinearGradient(0f, 0f, 0f, bounds.height(),
            top, bottom, Shader.TileMode.CLAMP)
        canvas.drawRect(bounds, paint)
        paint.shader = null
        paint.color = if (dark) 0x32E3E8F0 else 0x322B2D34; paint.style = Paint.Style.STROKE
        paint.strokeWidth = resources.displayMetrics.density
        bounds.inset(paint.strokeWidth / 2, paint.strokeWidth / 2)
        canvas.drawRoundRect(bounds, radius, radius, paint)
        paint.style = Paint.Style.FILL
        canvas.restoreToCount(checkpoint)
        super.draw(canvas)
    }

    private fun acceptSnapshot(value: Snapshot?) {
        if (!snapshotState.consume()) return
        snapshot = value
        invalidate()
    }

    private data class Snapshot(val image: Bitmap, val rootWidth: Int, val rootHeight: Int)

    private class Backdrop(private val root: View) : ViewTreeObserver.OnPreDrawListener, Choreographer.FrameCallback {
        var users = 0
        private val pending = mutableSetOf<FrostedAnalysisView>()
        private val frames = AnalysisCardFrameCache<Snapshot>()
        private var observer: ViewTreeObserver? = null
        private val choreographer = Choreographer.getInstance()
        private var frameTimeNanos: Long? = null

        fun request(card: FrostedAnalysisView) {
            pending.add(card)
            if (observer?.isAlive != true) {
                choreographer.removeFrameCallback(this)
                frameTimeNanos = null
                observer = root.viewTreeObserver.also { it.addOnPreDrawListener(this) }
                choreographer.postFrameCallback(this)
            }
            root.invalidate()
        }

        override fun doFrame(frameTimeNanos: Long) {
            this.frameTimeNanos = frameTimeNanos
            if (pending.isNotEmpty()) {
                choreographer.postFrameCallback(this)
                root.invalidate() // Keep a not-yet-measured first display pending until layout is ready.
            }
        }

        fun cancel(card: FrostedAnalysisView) {
            pending.remove(card)
            if (pending.isEmpty()) stopListening()
        }

        override fun onPreDraw(): Boolean {
            if (capturing || root.width == 0 || root.height == 0) return true
            val ready = pending.filter { it.snapshotState.needsCapture && it.width > 0 && it.height > 0 }
            if (ready.isEmpty()) return true
            val frame = frameTimeNanos ?: return true
            val value = frames.capture(frame) { capture() }
            ready.forEach { it.acceptSnapshot(value); pending.remove(it) }
            if (pending.isEmpty()) stopListening() // No capture listener remains while chatting/scrolling.
            return true
        }

        private fun capture(): Snapshot? {
            var image: Bitmap? = null
            return try {
                val scale = minOf(1f / AnalysisCardBackdrop.DOWNSAMPLE,
                    AnalysisCardBackdrop.MAX_WIDTH.toFloat() / root.width,
                    AnalysisCardBackdrop.MAX_HEIGHT.toFloat() / root.height)
                val w = (root.width * scale).toInt().coerceAtLeast(1)
                val h = (root.height * scale).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                image = bitmap
                bitmap.eraseColor(AnalysisCardBackdrop.opaqueTop)
                val canvas = Canvas(bitmap)
                canvas.scale(w.toFloat() / root.width, h.toFloat() / root.height)
                capturing = true
                root.draw(canvas)
                val pixels = IntArray(w * h)
                bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
                AnalysisCardBackdrop.soften(pixels, w, h)
                bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
                Snapshot(bitmap, root.width, root.height)
            } catch (error: Exception) {
                image?.recycle()
                MoodLog.e("CARD_SNAPSHOT_FAILED 使用配色回退", error)
                null // Settle once onto the 92% palette gradient; no content-area message.
            } finally { capturing = false }
        }

        private fun stopListening() {
            choreographer.removeFrameCallback(this)
            observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
            observer = null
            frameTimeNanos = null
        }

        fun close() {
            stopListening()
            pending.clear()
            // Published images are immutable and may still be held by detached cards; GC owns them.
        }
    }

    companion object {
        private var capturing = false
        private val backdrops = WeakHashMap<View, Backdrop>()
    }
}
