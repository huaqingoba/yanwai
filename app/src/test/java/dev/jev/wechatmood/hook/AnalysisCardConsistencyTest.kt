package dev.jev.wechatmood.hook

import dev.jev.wechatmood.core.ApiSettings
import dev.jev.wechatmood.core.CardPalette
import dev.jev.wechatmood.core.RuntimeSettings
import dev.jev.wechatmood.core.SettingsSession
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AnalysisCardConsistencyTest {
    private val api = ApiSettings.fromInput(ApiSettings.DEFAULT_ENDPOINT, "")
    private fun settings(revision: Long, opacity: Int, palette: CardPalette) =
        RuntimeSettings(revision, false, api, "install", cardOpacity = opacity, cardPalette = palette)

    @Test fun `two cards read identical global appearance before and after queued invalidation`() {
        val session = SettingsSession()
        val cards = listOf(AnalysisCardSurfaceState(), AnalysisCardSurfaceState())
        val invalidations = IntArray(cards.size)
        val posted = mutableListOf<() -> Unit>()
        session.onCardOpacityChanged = {
            // Model the main-thread broadcast callback; drawing can read new settings before it runs.
            posted.add {
                val current = requireNotNull(session.current)
                cards.forEachIndexed { i, card ->
                    card.updateOpacity(current.cardOpacity)
                    card.updatePalette(current.cardPalette)
                    invalidations[i]++
                }
            }
        }
        cards[0].updateOpacity(60)
        cards[1].updateOpacity(100)
        cards[1].updatePalette(CardPalette.LIGHT)
        cards.forEach { it.snapshotState.attach(); assertTrue(it.snapshotState.consume()) }
        val changes = listOf(75 to CardPalette.DARK, 75 to CardPalette.LIGHT,
            100 to CardPalette.LIGHT, 60 to CardPalette.AUTO)
        changes.forEachIndexed { index, (opacity, palette) ->
            session.accept(settings(index + 1L, opacity, palette), fromProvider = false)
            cards.forEach { card ->
                card.readSettings(session.current)
                assertEquals(opacity, card.opacity)
                assertEquals(palette, card.palette)
                assertFalse(card.snapshotState.needsCapture)
            }
            posted.toList().forEach { it() }; posted.clear()
            assertArrayEquals(intArrayOf(index + 1, index + 1), invalidations)
        }
    }

    @Test fun `unavailable global settings retain the last appearance and compatibility updates`() {
        val card = AnalysisCardSurfaceState()
        card.updateOpacity(67); card.updatePalette(CardPalette.LIGHT)
        assertFalse(card.readSettings(null))
        assertEquals(67, card.opacity)
        assertEquals(CardPalette.LIGHT, card.palette)
        assertTrue(card.readSettings(settings(1, 81, CardPalette.DARK)))
        assertFalse(card.readSettings(null))
        assertEquals(81, card.opacity)
        assertEquals(CardPalette.DARK, card.palette)
    }

    @Test fun `renderer reads the shared settings once before any palette or fill is used`() {
        val view = File("src/main/java/dev/jev/wechatmood/hook/FrostedAnalysisView.kt").readText()
        val draw = view.substringAfter("override fun draw(canvas: Canvas)").substringBefore("private fun acceptSnapshot(")
        val read = draw.indexOf("surface.readSettings(ModulePrefs.analysisSettings())")
        assertTrue(read >= 0)
        assertTrue(draw.indexOf("updateTextPalette()") > read)
        assertTrue(draw.indexOf("canvas.clipPath(clip)") > read)
        assertTrue(draw.indexOf("AnalysisCardBackdrop.colors(") > read)
        assertEquals(1, Regex("ModulePrefs\\.analysisSettings\\(\\)").findAll(draw).count())
    }
}
