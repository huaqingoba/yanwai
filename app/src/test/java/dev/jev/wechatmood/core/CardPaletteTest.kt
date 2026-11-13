package dev.jev.wechatmood.core

import dev.jev.wechatmood.hook.AnalysisCardSurfaceState
import org.junit.Assert.*
import org.junit.Test

class CardPaletteTest {
    @Test fun `missing malformed and invalid palette defaults to dark`() {
        assertEquals("card_palette", CardPalette.KEY)
        assertEquals(CardPalette.DARK, CardPalette.DEFAULT)
        for (value in listOf(null, "", "unknown", "DARK", " light ", 0, true)) {
            assertEquals(CardPalette.DARK, CardPalette.load { value })
        }
        CardPalette.entries.forEach { assertEquals(it, CardPalette.parse(it.id)) }
        val api = ApiSettings.fromInput(ApiSettings.DEFAULT_ENDPOINT, "key")
        assertEquals(CardPalette.DARK, RuntimeSettings(1, false, api, "install").cardPalette)
    }

    @Test fun `explicit palettes override uiMode and auto follows it`() {
        for (systemDark in listOf(false, true)) {
            assertTrue(CardPalette.DARK.isDark(systemDark))
            assertFalse(CardPalette.LIGHT.isDark(systemDark))
            assertEquals(systemDark, CardPalette.AUTO.isDark(systemDark))
        }
    }

    @Test fun `accepted slider and palette pushes update mounted surfaces through the session callback`() {
        val api = ApiSettings.fromInput(ApiSettings.DEFAULT_ENDPOINT, "key")
        var resets = 0
        var updates = 0
        val card = AnalysisCardSurfaceState()
        card.snapshotState.attach()
        assertTrue(card.snapshotState.consume())
        val session = SettingsSession { resets++ }
        session.onCardOpacityChanged = {
            val current = requireNotNull(session.current)
            card.updateOpacity(current.cardOpacity)
            card.updatePalette(current.cardPalette)
            updates++
        }
        val before = RuntimeSettings(1, false, api, "install")
        session.accept(before)
        val bridge = SettingsBridge(session, { it() }, { 0L }, { before }, {})
        for ((index, appearance) in listOf(60 to CardPalette.DARK, 75 to CardPalette.LIGHT,
            100 to CardPalette.LIGHT, 100 to CardPalette.AUTO).withIndex()) {
            val next = RuntimeSettings(index + 2L, false, api, "install",
                cardOpacity = appearance.first, cardPalette = appearance.second)
            bridge.receive(next)
            assertEquals(appearance.first, card.opacity)
            assertEquals(appearance.second, card.palette)
            assertEquals(before.analysisFingerprint(), next.analysisFingerprint())
            assertFalse(card.snapshotState.needsCapture)
        }
        assertEquals(5, updates)
        assertEquals(1, resets)
        bridge.receive(RuntimeSettings(2, false, api, "install")) // stale
        bridge.receive(RuntimeSettings(6, false, api, "install", cardOpacity = 100, cardPalette = CardPalette.AUTO))
        assertEquals(5, updates)
    }
}
