package dev.jev.wechatmood.hook

import org.junit.Assert.*
import org.junit.Test

class AnalysisCardSnapshotTest {
    @Test fun `attach and first ENTER coalesce into one capture`() {
        val state = AnalysisCardSnapshotState()
        assertFalse(state.needsCapture)
        state.render(AnalysisCardMotion.Change.ENTER)
        state.attach()
        assertTrue(state.consume())
        assertFalse(state.consume())
    }

    @Test fun `cached DIRECT card also takes its first snapshot`() {
        val state = AnalysisCardSnapshotState()
        state.attach()
        state.render(AnalysisCardMotion.Change.DIRECT)
        assertTrue(state.consume())
        state.render(AnalysisCardMotion.Change.DIRECT)
        assertFalse(state.consume())
    }

    @Test fun `scrolling and content updates over many frames never recapture`() {
        val state = AnalysisCardSnapshotState()
        val frames = AnalysisCardFrameCache<Any>()
        var captures = 0
        fun preDraw(frame: Long) {
            if (state.consume()) frames.capture(frame) { captures++; Any() }
        }
        state.attach()
        state.render(AnalysisCardMotion.Change.ENTER)
        preDraw(1)
        repeat(1000) { frame ->
            // Scrolling generates host pre-draws and message-row rebinds, including temporary detach.
            state.detach(); state.attach()
            listOf(AnalysisCardMotion.Change.NONE, AnalysisCardMotion.Change.CONTENT,
                AnalysisCardMotion.Change.RINGS, AnalysisCardMotion.Change.DIRECT).forEach { state.render(it) }
            preDraw((frame + 2).toLong())
        }
        assertEquals(1, captures)
    }

    @Test fun `new ENTER or explicit refresh permits one new capture`() {
        val state = AnalysisCardSnapshotState()
        state.attach(); assertTrue(state.consume())
        state.render(AnalysisCardMotion.Change.ENTER)
        assertTrue(state.consume()); assertFalse(state.consume())
        state.refresh(); state.refresh()
        assertTrue(state.consume()); assertFalse(state.consume())
    }

    @Test fun `refresh while detached waits until attached`() {
        val state = AnalysisCardSnapshotState()
        state.attach(); state.consume(); state.detach(); state.refresh()
        assertFalse(state.consume())
        state.attach()
        assertTrue(state.consume()); assertFalse(state.consume())
    }

    @Test fun `same frame requests share one image without changing prior snapshots`() {
        val frames = AnalysisCardFrameCache<IntArray>()
        var captures = 0
        val first = frames.capture(42) { captures++; intArrayOf(1) }
        assertSame(first, frames.capture(42) { captures++; intArrayOf(2) })
        val next = frames.capture(43) { captures++; intArrayOf(3) }
        assertNotSame(first, next)
        assertArrayEquals(intArrayOf(1), first)
        assertEquals(2, captures)
    }

    @Test fun `capture failure settles until explicit refresh and is shared within the frame`() {
        val state = AnalysisCardSnapshotState()
        val frames = AnalysisCardFrameCache<Any>()
        var attempts = 0
        state.attach()
        assertTrue(state.consume())
        assertNull(frames.capture(1) { attempts++; null })
        assertNull(frames.capture(1) { attempts++; Any() })
        repeat(100) { assertFalse(state.consume()) }
        assertEquals(1, attempts)
        state.refresh()
        assertTrue(state.consume())
        assertNotNull(frames.capture(2) { attempts++; Any() })
        assertEquals(2, attempts)
    }
}
