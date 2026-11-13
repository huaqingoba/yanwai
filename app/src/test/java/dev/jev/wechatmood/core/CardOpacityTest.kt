package dev.jev.wechatmood.core

import org.junit.Assert.*
import org.junit.Test

class CardOpacityTest {
    @Test fun `percentage rounds to the requested alpha byte`() {
        assertEquals(0xD9, CardOpacity.alpha(85))
        assertEquals(0xF5, CardOpacity.alpha(96))
        assertEquals(0xFF, CardOpacity.alpha(100))
        val alphas = (85..100).map(CardOpacity::alpha)
        assertTrue(alphas.zipWithNext().all { (before, after) -> after > before })
    }

    @Test fun `missing malformed and out of range values fall back to 96`() {
        for (value in listOf(null, "96", "bad", 96L, 96.0f, true, Int.MIN_VALUE, 0, 84, 101, Int.MAX_VALUE)) {
            assertEquals("Value: $value", 96, CardOpacity.load { value })
        }
        for (value in 85..100) assertEquals(value, CardOpacity.normalize(value))
        assertEquals("card_opacity", CardOpacity.KEY)
        assertEquals(0xF5, CardOpacity.alpha(101))
    }

    @Test fun `accepted opacity changes preview without revoking cached results or active requests`() {
        MoodStore.clear()
        var resets = 0
        var previews = 0
        val session = SettingsSession { resets++ }.apply { onCardOpacityChanged = { previews++ } }
        val api = ApiSettings.fromInput(ApiSettings.DEFAULT_ENDPOINT, "key")
        val before = RuntimeSettings(1, false, api, "install")
        session.accept(before)
        val mood = Mood("cached", 0.0, 0, "")
        MoodStore.complete(requireNotNull(MoodStore.acquire("cached")), mood)
        val pending = requireNotNull(MoodStore.acquire("pending"))
        val input = AnalysisInput("好", "alice", messageId = 1, createdAt = 1, accountScope = "a".repeat(64))
        val key = AnalysisCacheKey.of(input, before)
        assertNotNull(key)
        for ((index, opacity) in listOf(85, 100, 96).withIndex()) {
            val next = RuntimeSettings(index + 2L, false, api, "install", cardOpacity = opacity)
            session.accept(next, fromProvider = false)
            assertEquals(opacity, session.current!!.cardOpacity)
            assertEquals(key, AnalysisCacheKey.of(input, next))
            assertSame(mood, MoodStore.get("cached"))
        }
        assertEquals(1, resets)
        assertEquals(4, previews)
        session.accept(RuntimeSettings(2, false, api, "install", cardOpacity = 85), fromProvider = false)
        session.accept(RuntimeSettings(5, false, api, "install", cardOpacity = 96), fromProvider = false)
        assertEquals(4, previews) // Old broadcasts and unchanged alpha do not redraw.
        assertEquals(96, RuntimeSettings(6, false, api, "install", cardOpacity = 101).cardOpacity)
        assertTrue(MoodStore.complete(pending, mood))
        MoodStore.clear()
    }
}
