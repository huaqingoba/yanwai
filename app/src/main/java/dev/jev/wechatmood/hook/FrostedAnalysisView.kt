package dev.jev.wechatmood.hook

import android.annotation.SuppressLint
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.text.Spanned
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import java.util.WeakHashMap

/** Backdrop only is blurred. Text stays opaque; unsupported host rendering falls back to tint. */
// Injected into WeChat with its host context; module AppCompat themes/resources are not available.
@SuppressLint("AppCompatCustomView")
class FrostedAnalysisView(context: Context) : TextView(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bounds = RectF()
    private val clip = Path()
    private val position = IntArray(2)
    private val rootPosition = IntArray(2)
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

    private fun curve() = when (AnalysisCardMotion.curve) {
        AnalysisCardMotion.Curve.DECELERATE -> DecelerateInterpolator()
    }

    fun render(content: AnalysisCardMotion.Content, change: AnalysisCardMotion.Change) {
        if (change == AnalysisCardMotion.Change.NONE) return
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
            (layoutParams?.width ?: measuredWidth) - paddingLeft - paddingRight)
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

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val root = rootView
        attachedRoot = root
        backdrop = backdrops.getOrPut(root) { Backdrop(root) }.also { it.users++ }
    }

    override fun onDetachedFromWindow() {
        finishAnimations() // A recycled/detached card cannot return half faded or at an intermediate height.
        val root = attachedRoot
        backdrop?.let { if (--it.users == 0) { it.close(); if (root != null) backdrops.remove(root) } }
        backdrop = null; attachedRoot = null
        super.onDetachedFromWindow()
    }

    override fun draw(canvas: Canvas) {
        if (capturing) return // Exclude every analysis card from the shared backdrop, including its text.
        val checkpoint = canvas.save()
        bounds.set(0f, 0f, width.toFloat(), height.toFloat())
        clip.reset(); clip.addRoundRect(bounds, radius, radius, Path.Direction.CW)
        canvas.clipPath(clip)
        backdrop?.bitmap?.takeUnless { it.isRecycled }?.let { image ->
            val root = attachedRoot ?: return@let
            getLocationOnScreen(position); root.getLocationOnScreen(rootPosition)
            val left = (rootPosition[0] - position[0]).toFloat()
            val top = (rootPosition[1] - position[1]).toFloat()
            paint.color = Color.WHITE; paint.style = Paint.Style.FILL
            canvas.drawBitmap(image, null, RectF(left, top, left + root.width, top + root.height), paint)
        }
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(0f, 0f, 0f, height.toFloat().coerceAtLeast(1f),
            0xC02B2D34.toInt(), 0xB8202229.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(bounds, paint)
        paint.shader = null
        paint.color = 0x32E3E8F0; paint.style = Paint.Style.STROKE
        paint.strokeWidth = resources.displayMetrics.density
        bounds.inset(paint.strokeWidth / 2, paint.strokeWidth / 2)
        canvas.drawRoundRect(bounds, radius, radius, paint)
        paint.style = Paint.Style.FILL
        canvas.restoreToCount(checkpoint)
        super.draw(canvas)
    }

    private class Backdrop(private val root: View) : ViewTreeObserver.OnPreDrawListener {
        var users = 0
        var bitmap: Bitmap? = null
        private var lastCapture = -200L
        private var disabled = false
        private val observer = root.viewTreeObserver
        init { observer.addOnPreDrawListener(this) }

        override fun onPreDraw(): Boolean {
            val now = SystemClock.uptimeMillis()
            if (disabled || capturing || users == 0 || now - lastCapture < 180 || root.width == 0 || root.height == 0) return true
            lastCapture = now
            try {
                // One low-resolution capture per host window, shared by all visible cards.
                val scale = minOf(1f / 12, 160f / root.width, 280f / root.height)
                val w = (root.width * scale).toInt().coerceAtLeast(1)
                val h = (root.height * scale).toInt().coerceAtLeast(1)
                if (bitmap?.width != w || bitmap?.height != h) {
                    bitmap?.recycle(); bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                }
                val image = requireNotNull(bitmap)
                image.eraseColor(Color.TRANSPARENT)
                val capture = Canvas(image)
                capture.scale(w.toFloat() / root.width, h.toFloat() / root.height)
                capturing = true
                root.draw(capture)
                soften(image)
            } catch (_: Exception) {
                disabled = true
                bitmap?.recycle(); bitmap = null
            } finally { capturing = false }
            return true
        }

        private fun soften(image: Bitmap) {
            val w = image.width; val h = image.height
            val pixels = IntArray(w * h)
            val output = IntArray(pixels.size)
            image.getPixels(pixels, 0, w, 0, 0, w, h)
            // Separable five-tap blur on the downsampled image, no RenderEffect on the text/view.
            for (y in 0 until h) for (x in 0 until w) {
                var a = 0; var r = 0; var g = 0; var b = 0
                for (dx in -2..2) {
                    val c = pixels[y * w + (x + dx).coerceIn(0, w - 1)]
                    a += Color.alpha(c); r += Color.red(c); g += Color.green(c); b += Color.blue(c)
                }
                output[y * w + x] = Color.argb(a / 5, r / 5, g / 5, b / 5)
            }
            for (y in 0 until h) for (x in 0 until w) {
                var a = 0; var r = 0; var g = 0; var b = 0
                for (dy in -2..2) {
                    val c = output[(y + dy).coerceIn(0, h - 1) * w + x]
                    a += Color.alpha(c); r += Color.red(c); g += Color.green(c); b += Color.blue(c)
                }
                pixels[y * w + x] = Color.argb(a / 5, r / 5, g / 5, b / 5)
            }
            image.setPixels(pixels, 0, w, 0, 0, w, h)
        }

        fun close() {
            if (observer.isAlive) observer.removeOnPreDrawListener(this)
            bitmap?.recycle(); bitmap = null
        }
    }

    companion object {
        private var capturing = false
        private val backdrops = WeakHashMap<View, Backdrop>()
    }
}
