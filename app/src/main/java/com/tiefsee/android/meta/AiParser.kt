package com.tiefsee.android.meta

import org.json.JSONArray
import org.json.JSONObject

/** Поле с промптом/параметром генерации. */
class AiField(val title: String, val text: String, val long: Boolean = false)

class AiInfo(val source: String, val fields: List<AiField>)

/**
 * Разбор метаданных AI-генераторов: A1111 / Forge, NovelAI, ComfyUI, InvokeAI.
 * Логика повторяет AiParsingUtility из Tiefsee.
 */
object AiParser {

    /**
     * @param texts пары «ключ → текст» из PNG-чанков, EXIF UserComment и т.п.
     */
    fun parse(texts: List<Pair<String, String>>): AiInfo? {
        val map = LinkedHashMap<String, String>()
        for ((k, v) in texts) map.putIfAbsent(k, v)

        // ComfyUI
        val comfyPrompt = map["prompt"]?.takeIf { it.trimStart().startsWith("{") }
        val comfyWorkflow = map["workflow"]?.takeIf { it.trimStart().startsWith("{") }
        if (comfyPrompt != null || comfyWorkflow != null) {
            val fields = ArrayList<AiField>()
            if (comfyPrompt != null) fields.addAll(comfy(comfyPrompt))
            if (fields.isEmpty() && comfyWorkflow != null) fields.addAll(comfyWorkflowTexts(comfyWorkflow))
            comfyPrompt?.let { fields.add(AiField("prompt (JSON)", pretty(it), true)) }
            comfyWorkflow?.let { fields.add(AiField("workflow (JSON)", pretty(it), true)) }
            return AiInfo("ComfyUI", fields)
        }

        // NovelAI
        val comment = map["Comment"]
        if (comment != null && comment.trimStart().startsWith("{") && (map["Software"]?.contains("NovelAI") == true || comment.contains("\"uc\""))) {
            val fields = ArrayList<AiField>()
            map["Description"]?.let { fields.add(AiField("Description", it, true)) }
            fields.addAll(novelai(comment))
            return AiInfo("NovelAI", fields)
        }

        // InvokeAI
        for (k in listOf("invokeai_metadata", "sd-metadata", "Dream", "invokeai_graph")) {
            val v = map[k] ?: continue
            if (v.trimStart().startsWith("{")) return AiInfo("InvokeAI", invokeai(v))
            return AiInfo("InvokeAI", listOf(AiField(k, v, true)))
        }

        // A1111 / Forge / Fooocus (parameters)
        val params = map["parameters"] ?: map["UserComment"]?.takeIf { it.contains("Steps: ") }
        if (params != null) {
            val t = params.trim()
            if (t.startsWith("{")) return AiInfo("Stable Diffusion", jsonFields(t))
            return AiInfo("Stable Diffusion WebUI", a1111(t))
        }
        return null
    }

    // ---------- A1111 ----------

    fun a1111(text: String): List<AiField> {
        val out = ArrayList<AiField>()
        val lines = text.replace("\r\n", "\n").split("\n")
        var settingsIdx = -1
        for (i in lines.indices.reversed()) {
            if (lines[i].trimStart().startsWith("Steps: ")) { settingsIdx = i; break }
        }
        val body = if (settingsIdx >= 0) lines.subList(0, settingsIdx) else lines
        val sb = StringBuilder()
        val neg = StringBuilder()
        var inNeg = false
        for (l in body) {
            if (l.startsWith("Negative prompt:")) {
                inNeg = true
                neg.append(l.removePrefix("Negative prompt:").trim())
                continue
            }
            if (inNeg) neg.append('\n').append(l) else { if (sb.isNotEmpty()) sb.append('\n'); sb.append(l) }
        }
        if (sb.isNotBlank()) out.add(AiField("Prompt", sb.toString().trim(), true))
        if (neg.isNotBlank()) out.add(AiField("Negative prompt", neg.toString().trim(), true))
        if (settingsIdx >= 0) {
            val settings = lines.subList(settingsIdx, lines.size).joinToString("\n")
            for ((k, v) in splitSettings(settings)) out.add(AiField(k, v, v.length > 60))
        }
        return out
    }

    /** "Steps: 20, Sampler: Euler a, Lora hashes: "a: 1, b: 2"" → пары, кавычки учитываются. */
    fun splitSettings(s: String): List<Pair<String, String>> {
        val parts = ArrayList<String>()
        val cur = StringBuilder()
        var quote = false
        var depth = 0
        for (c in s) {
            when {
                c == '"' -> { quote = !quote; cur.append(c) }
                !quote && (c == '{' || c == '[') -> { depth++; cur.append(c) }
                !quote && (c == '}' || c == ']') -> { depth--; cur.append(c) }
                c == ',' && !quote && depth <= 0 -> { parts.add(cur.toString()); cur.setLength(0) }
                else -> cur.append(c)
            }
        }
        if (cur.isNotBlank()) parts.add(cur.toString())
        val out = ArrayList<Pair<String, String>>()
        for (p in parts) {
            val i = p.indexOf(':')
            if (i <= 0) {
                if (out.isNotEmpty()) {
                    val last = out.removeAt(out.size - 1)
                    out.add(last.first to last.second + "," + p)
                }
                continue
            }
            var v = p.substring(i + 1).trim()
            if (v.length >= 2 && v.startsWith('"') && v.endsWith('"')) v = v.substring(1, v.length - 1)
            out.add(p.substring(0, i).trim() to v)
        }
        return out
    }

    // ---------- NovelAI ----------

    private fun novelai(json: String): List<AiField> {
        val o = try { JSONObject(json) } catch (_: Exception) { return listOf(AiField("Comment", json, true)) }
        val out = ArrayList<AiField>()
        val order = listOf("prompt", "uc", "steps", "sampler", "scale", "cfg_rescale", "seed", "width", "height", "noise_schedule", "request_type")
        val used = HashSet<String>()
        for (k in order) if (o.has(k)) { out.add(field(k, o.opt(k))); used.add(k) }
        // NovelAI v4: промпты внутри v4_prompt.caption.base_caption
        o.optJSONObject("v4_prompt")?.optJSONObject("caption")?.optString("base_caption")?.takeIf { it.isNotEmpty() }?.let {
            if (!o.has("prompt")) out.add(0, AiField("prompt", it, true))
        }
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k !in used) out.add(field(k, o.opt(k)))
        }
        return out.filter { it.text.isNotBlank() }
    }

    // ---------- InvokeAI ----------

    private fun invokeai(json: String): List<AiField> {
        val o = try { JSONObject(json) } catch (_: Exception) { return listOf(AiField("metadata", json, true)) }
        val out = ArrayList<AiField>()
        val order = listOf("positive_prompt", "negative_prompt", "model", "seed", "steps", "cfg_scale", "scheduler", "width", "height")
        val used = HashSet<String>()
        for (k in order) if (o.has(k)) {
            val v = o.opt(k)
            out.add(if (v is JSONObject && v.has("name")) AiField(k, v.optString("name")) else field(k, v))
            used.add(k)
        }
        // Старый формат (sd-metadata): image.prompt[0].prompt
        val img = o.optJSONObject("image") ?: o.optJSONArray("images")?.optJSONObject(0)
        if (img != null) {
            img.optJSONArray("prompt")?.optJSONObject(0)?.optString("prompt")?.let { out.add(AiField("Prompt", it, true)) }
            for (k in listOf("seed", "steps", "cfg_scale", "sampler")) if (img.has(k)) out.add(field(k, img.opt(k)))
            used.add("image"); used.add("images")
        }
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k !in used) out.add(field(k, o.opt(k)))
        }
        return out.filter { it.text.isNotBlank() }
    }

    // ---------- ComfyUI ----------

    private fun comfy(json: String): List<AiField> {
        val nodes = try { JSONObject(json) } catch (_: Exception) { return emptyList() }
        val out = ArrayList<AiField>()

        fun node(id: String): JSONObject? = nodes.optJSONObject(id)

        /** Значение входа: строка/число либо ссылка [id, slot] на другой узел. */
        fun resolveText(v: Any?, depth: Int = 0): String? {
            if (depth > 6) return null
            return when (v) {
                is String -> v
                is Number -> v.toString()
                is JSONArray -> {
                    val target = node(v.optString(0)) ?: return null
                    val inp = target.optJSONObject("inputs") ?: return null
                    for (k in listOf("text", "string", "value", "text_g", "prompt", "populated_text", "wildcard_text", "text_positive", "text_negative")) {
                        if (inp.has(k)) {
                            val r = resolveText(inp.opt(k), depth + 1)
                            if (!r.isNullOrBlank()) return r
                        }
                    }
                    // Узлы-объединители строк
                    val parts = ArrayList<String>()
                    val keys = inp.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        if (k.startsWith("string") || k.startsWith("text")) resolveText(inp.opt(k), depth + 1)?.let { parts.add(it) }
                    }
                    parts.joinToString(", ").ifBlank { null }
                }
                else -> null
            }
        }

        /** Текст промпта, к которому ведёт conditioning-вход (через промежуточные узлы). */
        fun conditioningText(link: Any?, depth: Int = 0): String? {
            if (link !is JSONArray || depth > 8) return null
            val n = node(link.optString(0)) ?: return null
            val inp = n.optJSONObject("inputs") ?: return null
            val type = n.optString("class_type")
            if (type.contains("TextEncode", true) || inp.has("text") || inp.has("text_g")) {
                val t = resolveText(inp.opt("text") ?: inp.opt("text_g"))
                if (t != null) return t
            }
            for (k in listOf("conditioning", "conditioning_1", "conditioning_to", "positive", "base_positive")) {
                conditioningText(inp.opt(k), depth + 1)?.let { return it }
            }
            return null
        }

        val keys = nodes.keys().asSequence().toList().sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }
        val seen = HashSet<String>()
        for (id in keys) {
            val n = node(id) ?: continue
            val type = n.optString("class_type")
            val inp = n.optJSONObject("inputs") ?: continue
            if (type.contains("Sampler", true) && (inp.has("positive") || inp.has("seed") || inp.has("noise_seed"))) {
                conditioningText(inp.opt("positive"))?.let { if (seen.add("p$it")) out.add(AiField("Prompt", it.trim(), true)) }
                conditioningText(inp.opt("negative"))?.let { if (seen.add("n$it")) out.add(AiField("Negative prompt", it.trim(), true)) }
                for (k in listOf("seed", "noise_seed", "steps", "cfg", "sampler_name", "scheduler", "denoise")) {
                    val v = inp.opt(k)
                    if (v is String || v is Number) out.add(AiField(k, v.toString()))
                }
            }
        }
        // Модели и LoRA
        for (id in keys) {
            val n = node(id) ?: continue
            val inp = n.optJSONObject("inputs") ?: continue
            for (k in listOf("ckpt_name", "unet_name", "lora_name", "vae_name")) {
                val v = inp.opt(k)
                if (v is String) {
                    val title = when (k) { "lora_name" -> "LoRA"; "vae_name" -> "VAE"; else -> "Model" }
                    val strength = if (k == "lora_name") inp.opt("strength_model")?.let { " ($it)" } ?: "" else ""
                    out.add(AiField(title, v + strength))
                }
            }
            if (n.optString("class_type").contains("EmptyLatent", true) && inp.has("width")) {
                out.add(AiField("Size", "${inp.opt("width")} × ${inp.opt("height")}"))
            }
        }
        // Если сэмплер не найден — просто все тексты из TextEncode-узлов.
        if (out.none { it.title == "Prompt" }) {
            for (id in keys) {
                val n = node(id) ?: continue
                if (n.optString("class_type").contains("TextEncode", true)) {
                    resolveText(n.optJSONObject("inputs")?.opt("text"))?.let { out.add(AiField("Text #$id", it, true)) }
                }
            }
        }
        return out
    }

    private fun comfyWorkflowTexts(json: String): List<AiField> {
        val o = try { JSONObject(json) } catch (_: Exception) { return emptyList() }
        val nodes = o.optJSONArray("nodes") ?: return emptyList()
        val out = ArrayList<AiField>()
        for (i in 0 until nodes.length()) {
            val n = nodes.optJSONObject(i) ?: continue
            if (!n.optString("type").contains("TextEncode", true)) continue
            val w = n.optJSONArray("widgets_values") ?: continue
            val t = w.optString(0)
            if (t.isNotBlank()) out.add(AiField(n.optString("title").ifBlank { "Text #${n.optInt("id")}" }, t, true))
        }
        return out
    }

    // ---------- общее ----------

    fun jsonFields(json: String): List<AiField> {
        val o = try { JSONObject(json) } catch (_: Exception) { return listOf(AiField("JSON", json, true)) }
        val out = ArrayList<AiField>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out.add(field(k, o.opt(k)))
        }
        return out
    }

    private fun field(k: String, v: Any?): AiField {
        val s = when (v) {
            is JSONObject -> v.toString(2)
            is JSONArray -> v.toString(2)
            null, JSONObject.NULL -> ""
            else -> v.toString()
        }
        return AiField(k, s, s.length > 60 || v is JSONObject || v is JSONArray)
    }

    fun pretty(json: String): String = try {
        val t = json.trim()
        if (t.startsWith("[")) JSONArray(t).toString(2) else JSONObject(t).toString(2)
    } catch (_: Exception) {
        json
    }
}
