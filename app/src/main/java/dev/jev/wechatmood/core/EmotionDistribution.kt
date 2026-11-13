package dev.jev.wechatmood.core

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.floor

/** Integer display weights, not a claim that qualitative model output has been calibrated. */
data class EmotionScore(val label: String, val percent: Int)

/** The only backend-to-display normalization boundary. Always returns all labels, totaling 100. */
object EmotionDistribution {
    val labels = linkedMapOf(
        "happy" to "开心", "calm" to "平静", "sad" to "失落", "hurt" to "委屈",
        "annoyed" to "生气", "relieved" to "缓和", "anxious" to "焦虑", "confused" to "困惑",
        "tired" to "疲惫", "unknown" to "不明确",
    )
    private val aliases = labels + mapOf("angry" to "生气", "neutral" to "平静", "unclear" to "不明确")

    fun canonicalLabel(value: String?): String? = value?.trim()?.let {
        if (it in labels.values) it else aliases[it.lowercase()]
    }

    fun normalize(probabilities: Map<String, *> = emptyMap<String, Double>(), label: String? = null,
        text: String? = null): List<EmotionScore> {
        val content = text?.let(::responseText)
        val json = content?.let(::extractObject)
        val source = probabilities.ifEmpty { weights(json?.opt("probabilities")) }
        // A backend may mix numeric fractions and explicit percent strings. Bare numbers share a unit.
        val numericScale = if (source.values.any { bareNumber(it)?.let { number -> number > 1.0 } == true }) 100.0 else 1.0
        val amounts = labels.values.associateWith { 0.0 }.toMutableMap()
        source.forEach { (key, value) ->
            val number = amount(value, numericScale)
            if (number != null && number.isFinite() && number > 0) {
                val name = canonicalLabel(key) ?: "不明确"
                amounts[name] = amounts.getValue(name) + number
            }
        }
        val largest = amounts.values.maxOrNull() ?: 0.0
        if (largest > 0 && largest.isFinite()) {
            // Scale before summing, avoiding overflow even for very large finite backend weights.
            val scaled = amounts.mapValues { it.value / largest }
            val total = scaled.values.sum()
            val exact = scaled.mapValues { it.value / total * 100 }
            val integers = exact.mapValues { floor(it.value).toInt() }.toMutableMap()
            val remaining = 100 - integers.values.sum()
            exact.keys.sortedByDescending { exact.getValue(it) - integers.getValue(it) }
                .take(remaining).forEach { integers[it] = integers.getValue(it) + 1 }
            return labels.values.map { EmotionScore(it, integers.getValue(it)) }
        }
        val dominant = canonicalLabel(label) ?: canonicalLabel(json?.opt("emotion") as? String)
            ?: canonicalLabel(json?.opt("choice") as? String) ?: qualitativeLabel(content)
        if (dominant == null || dominant == "不明确") return labels.values.map {
            EmotionScore(it, if (it == "不明确") 100 else 0)
        }
        // Explainable prior for legacy qualitative output: 70 dominant, 30 equally over the other nine.
        return normalize(labels.values.associateWith { if (it == dominant) 70.0 else 30.0 / 9 })
    }

    private fun bareNumber(value: Any?): Double? = when (value) {
        is Number -> value.toDouble().takeIf(Double::isFinite)
        is String -> if (value.trim().endsWith('%')) null else value.trim().toDoubleOrNull()?.takeIf(Double::isFinite)
        else -> null
    }

    private fun amount(value: Any?, numericScale: Double): Double? = when (value) {
        is String -> if (value.trim().endsWith('%')) value.trim().dropLast(1).trim().toDoubleOrNull()?.div(100)
            else value.trim().toDoubleOrNull()?.div(numericScale)
        is Number -> value.toDouble() / numericScale
        else -> null
    }

    fun hasEmotionResult(body: String): Boolean {
        val content = responseText(body) ?: return false
        val json = extractObject(content)
        return weights(json?.opt("probabilities")).values.any { amount(it, 1.0)?.let { number -> number.isFinite() && number > 0 } == true } ||
            canonicalLabel(json?.opt("emotion") as? String) != null || canonicalLabel(json?.opt("choice") as? String) != null ||
            qualitativeLabel(content) != null
    }

    private fun weights(value: Any?): Map<String, Any?> = when (value) {
        is JSONObject -> value.keys().asSequence().associateWith(value::opt)
        is JSONArray -> buildMap {
            for (i in 0 until value.length()) {
                val item = value.optJSONObject(i) ?: continue
                val label = item.opt("label") as? String ?: continue
                put(label, item.opt("percent") ?: item.opt("probability"))
            }
        }
        else -> emptyMap()
    }

    private fun qualitativeLabel(text: String?): String? {
        if (text == null) return null
        canonicalLabel(text)?.let { return it }
        // Only explicit result lines; an arbitrary sentence mentioning anger is not an anger verdict.
        val result = Regex("(?im)^\\s*(?:情绪|emotion|sentiment)\\s*[:：]\\s*([\\p{L}]+)").find(text)
        return canonicalLabel(result?.groupValues?.get(1))
    }

    /** Accept bare JSON, markdown fences, surrounding prose, and Chat Completions envelopes. */
    fun responseObject(body: String): JSONObject? {
        return responseText(body)?.let(::extractObject)
    }

    private fun responseText(body: String): String? {
        if (body.length > 256 * 1024) return null
        val json = extractObject(body)
        if (json?.has("choices") != true) return body
        val choice = json.optJSONArray("choices")?.optJSONObject(0) ?: return null
        if (choice.optString("finish_reason") in setOf("length", "content_filter")) return null
        return choice.optJSONObject("message")?.opt("content") as? String
    }

    private fun extractObject(text: String): JSONObject? {
        var start = -1
        var depth = 0
        var quoted = false
        var escaped = false
        var first: JSONObject? = null
        text.forEachIndexed { index, char ->
            if (start < 0) {
                if (char == '{') { start = index; depth = 1; quoted = false; escaped = false }
            } else if (quoted) {
                if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{' -> depth++
                '}' -> if (--depth == 0) {
                    val candidate = runCatching { JSONObject(text.substring(start, index + 1)) }.getOrNull()
                    if (candidate != null) {
                        if (listOf("choices", "probabilities", "emotion", "choice", "intent", "concern", "tone").any(candidate::has)) return candidate
                        if (first == null) first = candidate
                    }
                    start = -1
                }
            }
        }
        return first
    }
}
