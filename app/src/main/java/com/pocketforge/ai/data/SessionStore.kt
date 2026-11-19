package com.pocketforge.ai.data

import android.util.Log
import com.pocketforge.ai.core.CryptoBox
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 会话轨迹存储：写盘前用设备密钥加密，防止手机被拿走时聊天与文件路径泄露。
 */
class SessionStore(private val dir: File) {

    private val maxMessages = 200
    private val titles = File(dir, "session-titles.json")

    private fun fileOf(key: String): File {
        val safe = key.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return File(dir, "session-$safe.enc")
    }

    /** 会话标题（明文小文件，只存名字不存聊天内容） */
    fun title(key: String, fallback: String): String = runCatching {
        if (!titles.exists()) return@runCatching fallback
        org.json.JSONObject(titles.readText()).optString(key, fallback)
    }.getOrDefault(fallback)

    fun setTitle(key: String, title: String) {
        runCatching {
            val obj = if (titles.exists()) org.json.JSONObject(titles.readText()) else org.json.JSONObject()
            obj.put(key, title)
            dir.mkdirs()
            titles.writeText(obj.toString())
        }
    }

    fun keys(): List<String> = runCatching {
        dir.listFiles()?.filter { it.name.startsWith("session-") && it.name.endsWith(".enc") }
            ?.map { it.name.removePrefix("session-").removeSuffix(".enc") }
            ?.sortedByDescending { key -> fileOf(key).lastModified() }
            .orEmpty()
    }.getOrDefault(emptyList())

    fun exists(key: String): Boolean = fileOf(key).exists()

    fun save(key: String, messages: List<ChatMessage>) {
        try {
            val arr = JSONArray()
            messages.takeLast(maxMessages).forEach { msg ->
                arr.put(
                    JSONObject().apply {
                        put("role", msg.role)
                        put("content", msg.content ?: "")
                        put("toolCallId", msg.toolCallId ?: "")
                        if (msg.toolCalls.isNotEmpty()) {
                            put(
                                "toolCalls",
                                JSONArray().also { calls ->
                                    msg.toolCalls.forEach { call ->
                                        calls.put(
                                            JSONObject()
                                                .put("id", call.id)
                                                .put("name", call.name)
                                                .put("arguments", call.arguments)
                                        )
                                    }
                                }
                            )
                        }
                    }
                )
            }
            CryptoBox.sealToFile(fileOf(key), arr.toString().toByteArray(Charsets.UTF_8))
        } catch (t: Throwable) {
            Log.w("SessionStore", "会话加密落盘失败", t)
        }
    }

    fun load(key: String): List<ChatMessage> {
        return try {
            val file = fileOf(key)
            if (!file.exists()) return emptyList()
            val arr = JSONArray(CryptoBox.openText(file.readText()))
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val calls = obj.optJSONArray("toolCalls")
                val toolCalls = if (calls == null) emptyList() else (0 until calls.length()).mapNotNull { j ->
                    calls.optJSONObject(j)?.let {
                        ToolCall(it.optString("id"), it.optString("name"), it.optString("arguments"))
                    }
                }
                ChatMessage(
                    role = obj.optString("role", "user"),
                    content = obj.optString("content", ""),
                    toolCalls = toolCalls,
                    toolCallId = obj.optString("toolCallId", "").ifEmpty { null },
                )
            }
        } catch (t: Throwable) {
            Log.w("SessionStore", "会话解密失败，忽略历史", t)
            emptyList()
        }
    }

    fun clear(key: String) {
        try {
            val file = fileOf(key)
            if (file.exists()) file.delete()
        } catch (_: Throwable) {
        }
    }
}
