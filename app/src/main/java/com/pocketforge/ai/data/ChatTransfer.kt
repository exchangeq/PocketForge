package com.pocketforge.ai.data

import org.json.JSONArray
import org.json.JSONObject

/** 跨 App 通用会话格式：PersonaChat / PocketForge 都能导入，也兼容 SillyTavern jsonl。 */
data class ImportedChat(
    val title: String,
    val name: String,
    val role: String,
    val style: String,
    val quirks: String,
    val taboo: String,
    val raw: String,
    val messages: List<ChatMessage>,
    val createdAt: Long = System.currentTimeMillis(),
)

object ChatTransfer {
    fun encode(
        title: String,
        persona: PersonaStore.Persona?,
        messages: List<ChatMessage>,
        model: String,
    ): String = JSONObject().apply {
        put("format", "pocketforge-chat-v1")
        put("exportedAt", System.currentTimeMillis())
        put("model", model)
        put("session", JSONObject().put("title", title).put("createdAt", System.currentTimeMillis()))
        persona?.let {
            put("persona_snapshot", JSONObject().apply {
                put("name", it.name)
                put("position", it.role)
                put("fullText", it.raw)
                put("style", it.style)
                put("quirks", it.quirks)
                put("taboo", it.taboo)
            })
        }
        put("messages", JSONArray().apply {
            messages.filter { it.role == "user" || it.role == "assistant" }.forEach {
                put(JSONObject().put("role", it.role).put("content", it.content.orEmpty()))
            }
        })
    }.toString(2)

    fun decode(raw: String): ImportedChat {
        val text = raw.trim()
        var name = ""; var role = ""; var style = ""; var quirks = ""; var taboo = ""; var full = ""; var title = ""
        var created = System.currentTimeMillis()
        val msgs = mutableListOf<ChatMessage>()
        if (text.startsWith("{")) {
            val o = JSONObject(text)
            val ps = o.optJSONObject("persona_snapshot") ?: o.optJSONObject("persona")
            name = ps?.optString("name").orEmpty().ifBlank { o.optString("personaName") }
            role = ps?.optString("position").orEmpty().ifBlank { ps?.optString("role").orEmpty() }
            style = ps?.optString("style").orEmpty()
            quirks = ps?.optString("quirks").orEmpty()
            taboo = ps?.optString("taboo").orEmpty()
            full = ps?.optString("fullText").orEmpty().ifBlank { ps?.optString("raw").orEmpty() }
            title = o.optJSONObject("session")?.optString("title").orEmpty().ifBlank { o.optString("title") }
            created = o.optJSONObject("session")?.optLong("createdAt") ?: System.currentTimeMillis()
            o.optJSONArray("messages")?.let { arr -> parseArray(arr, msgs) }
        } else if (text.startsWith("[")) {
            parseArray(JSONArray(text), msgs)
        } else {
            text.lineSequence().forEach { line ->
                val t = line.trim()
                if (t.startsWith("{")) runCatching {
                    val o = JSONObject(t)
                    val c = o.optString("mes").ifBlank { o.optString("content") }
                    if (c.isNotBlank()) {
                        msgs.add(ChatMessage(if (o.optBoolean("is_user")) "user" else "assistant", c))
                        if (name.isBlank()) name = o.optString("name")
                    }
                }
            }
        }
        require(msgs.isNotEmpty()) { "没有解析到消息；支持 JSON / JSONL" }
        return ImportedChat(
            title = title.ifBlank { "导入 · " + name.ifBlank { "角色" } },
            name = name.ifBlank { "导入角色" },
            role = role, style = style, quirks = quirks, taboo = taboo,
            raw = full.ifBlank { "你正在扮演「${name.ifBlank { "导入角色" }}」。保持角色口吻与边界。" },
            messages = msgs, createdAt = created,
        )
    }

    private fun parseArray(arr: JSONArray, out: MutableList<ChatMessage>) {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val content = o.optString("content").ifBlank { o.optString("mes") }
            if (content.isBlank()) continue
            val role = when {
                o.optBoolean("is_user") -> "user"
                o.optString("role") == "user" -> "user"
                o.optString("role") == "system" || o.optString("role") == "tool" -> continue
                else -> "assistant"
            }
            out.add(ChatMessage(role, content))
        }
    }
}
