package dev.jev.wechatmood.hook

/** Android-free surface and snapshot policy, shared by the host renderer and offline tests. */
internal object AnalysisCardBackdrop {
    val frostedTop = 0xE62B2D34.toInt()
    val frostedBottom = 0xE6202229.toInt()
    val opaqueTop = 0xFF2B2D34.toInt()
    val opaqueBottom = 0xFF202229.toInt()
    const val BLUR_RADIUS = 3
    const val BLUR_PASSES = 2
    const val DOWNSAMPLE = 12
    const val MAX_WIDTH = 160
    const val MAX_HEIGHT = 280

    fun colors(hasSnapshot: Boolean): Pair<Int, Int> =
        if (hasSnapshot) frostedTop to frostedBottom else opaqueTop to opaqueBottom

    /** Two horizontal/vertical box-blur rounds on an already downsampled ARGB image. */
    fun soften(pixels: IntArray, width: Int, height: Int) {
        require(width > 0 && height > 0 && pixels.size == width * height)
        val output = IntArray(pixels.size)
        val taps = BLUR_RADIUS * 2 + 1
        repeat(BLUR_PASSES) {
            for (vertical in listOf(false, true)) {
                val input = if (vertical) output else pixels
                val target = if (vertical) pixels else output
                for (y in 0 until height) for (x in 0 until width) {
                    var a = 0; var r = 0; var g = 0; var b = 0
                    for (offset in -BLUR_RADIUS..BLUR_RADIUS) {
                        val xx = if (vertical) x else (x + offset).coerceIn(0, width - 1)
                        val yy = if (vertical) (y + offset).coerceIn(0, height - 1) else y
                        val color = input[yy * width + xx]
                        a += color ushr 24; r += (color ushr 16) and 255
                        g += (color ushr 8) and 255; b += color and 255
                    }
                    target[y * width + x] = ((a / taps) shl 24) or ((r / taps) shl 16) or
                        ((g / taps) shl 8) or (b / taps)
                }
            }
        }
    }
}

/** Attach and the first ENTER coalesce. Detaching/rebinding during scrolling keeps the snapshot. */
internal class AnalysisCardSnapshotState {
    private var attached = false
    private var pending = true
    val needsCapture get() = attached && pending

    fun attach() { attached = true }
    fun detach() { attached = false }
    fun render(change: AnalysisCardMotion.Change) {
        if (change == AnalysisCardMotion.Change.ENTER) refresh()
    }
    fun refresh() { pending = true }
    fun consume(): Boolean {
        if (!needsCapture) return false
        pending = false // Failures settle too; only a new ENTER/explicit refresh may retry.
        return true
    }
}

/** Frame identity is only for sharing explicit requests, never an elapsed-time refresh trigger. */
internal class AnalysisCardFrameCache<T> {
    private var lastCapture: Long? = null
    private var cached: T? = null

    fun capture(frame: Long, capture: () -> T?): T? {
        if (lastCapture != frame) {
            cached = capture()
            lastCapture = frame
        }
        return cached
    }
}
