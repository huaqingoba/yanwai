package dev.jev.wechatmood.ui

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** Resource contracts: choices remain accessible, keys stay out of saved view state. */
class SettingsLayoutTest {
    @Test fun analysisOptionsUsePlainModelNames() {
        val source = File("src/main/java/dev/jev/wechatmood/ui/IntentSettingsUi.kt").readText()
        assertFalse(source.contains("情绪与意图一起分析"))
        assertTrue(source.contains("决策模型（JEV）"))
        assertTrue(source.contains("智能模型（LLM）"))
    }

    @Test fun replyPageOffersRoleManagementAndKeepsOnlyUsefulInstructions() {
        val nodes = elements("activity_main")
        assertFalse(nodes.any { it.getAttribute("android:text").contains("不需要额外开启许可") })
        assertTrue(nodes.any { it.getAttribute("android:text").contains("在微信聊天中展开「＋」") })
        assertTrue(nodes.any { it.getAttribute("android:id") == "@+id/roleManager" })
    }
    @Test fun analysisPageDoesNotContainCredentialsOrConditionalModelForms() {
        val nodes = elements("intent_settings")
        assertFalse("Model credentials belong on a dedicated page", nodes.any {
            it.getAttribute("android:inputType") == "textPassword"
        })
        assertTrue(nodes.any { it.getAttribute("android:id") == "@+id/openModelSettings" })
    }

    @Test fun manualReplyDoesNotRequireAnExtraPermissionSwitch() {
        assertFalse(elements("reply_settings").any { it.getAttribute("android:id") == "@+id/replyConsent" })
    }
    private fun elements(name: String): List<Element> {
        val file = File("src/main/res/layout/$name.xml")
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("*")
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    @Test fun analysisChoicesUseNonEditableDropdowns() {
        val nodes = elements("intent_settings")
        assertFalse("Long radio lists should be replaced with dropdowns", nodes.any { it.tagName == "RadioGroup" })
        listOf("emotionSource", "configSource", "intentRoute").forEach { id ->
            val field = nodes.single { it.getAttribute("android:id") == "@+id/$id" }
            assertEquals("none", field.getAttribute("android:inputType"))
            assertEquals("false", field.getAttribute("android:saveEnabled"))
            assertEquals("56dp", field.getAttribute("android:minHeight"))
        }
    }

    @Test fun secretsNeverEnterAndroidSavedViewState() {
        listOf("activity_main", "intent_settings", "analysis_model_settings", "reply_settings").forEach { layout ->
            elements(layout).filter { it.getAttribute("android:inputType") == "textPassword" }.forEach {
                assertEquals("$layout must not persist keys in instance state", "false", it.getAttribute("android:saveEnabled"))
                assertEquals("no", it.getAttribute("android:importantForAutofill"))
            }
        }
    }

    @Test fun cardDisplayControlsHaveOneHomeOutsideModelForms() {
        val controls = listOf("showIntent", "showConcern", "showTone")
        val modelForms = elements("intent_settings") + elements("reply_settings")
        assertFalse(modelForms.any { it.getAttribute("android:id").removePrefix("@+id/") in controls })
        controls.forEach { id ->
            assertEquals(1, elements("activity_main").count { it.getAttribute("android:id") == "@+id/$id" })
        }
    }

    @Test fun opacitySliderUsesTheAnalysisCardSectionAndOnePercentSteps() {
        val nodes = elements("activity_main")
        val slider = nodes.single { it.getAttribute("android:id") == "@+id/cardOpacity" }
        assertEquals("SeekBar", slider.tagName)
        assertEquals("60", slider.getAttribute("android:min"))
        assertEquals("100", slider.getAttribute("android:max"))
        assertEquals("88", slider.getAttribute("android:progress"))
        assertEquals("false", slider.getAttribute("android:saveEnabled"))
        assertEquals("@+id/aboutPanel", (slider.parentNode as Element).getAttribute("android:id"))
        assertTrue(nodes.any { it.getAttribute("android:text") == "越高越不透，100% 为纯色" })
        assertTrue(File("src/main/java/dev/jev/wechatmood/MainActivity.kt").readText()
            .contains("binding.cardOpacity.keyProgressIncrement = 1"))
    }

    @Test fun cardPaletteUsesAnAccessibleNonEditableDropdownAndSavesImmediately() {
        val nodes = elements("activity_main")
        val field = nodes.single { it.getAttribute("android:id") == "@+id/cardPalette" }
        assertEquals("none", field.getAttribute("android:inputType"))
        assertEquals("false", field.getAttribute("android:saveEnabled"))
        assertEquals("56dp", field.getAttribute("android:minHeight"))
        assertEquals("卡片配色", (field.parentNode as Element).getAttribute("android:hint"))
        val activity = File("src/main/java/dev/jev/wechatmood/MainActivity.kt").readText()
        assertTrue(activity.contains("binding.cardPalette.setOnItemClickListener"))
        assertTrue(activity.contains("putString(CardPalette.KEY, palette.id)"))
        val provider = File("src/main/java/dev/jev/wechatmood/core/SettingsProvider.kt").readText()
        assertTrue(provider.contains("putString(CardPalette.KEY, CardPalette.load { prefs.all[it] }.id)"))
        assertTrue(File("src/main/java/dev/jev/wechatmood/core/SettingsSync.kt").readText()
            .contains("CardPalette.load { bundle.getString(it) }"))
    }
}
