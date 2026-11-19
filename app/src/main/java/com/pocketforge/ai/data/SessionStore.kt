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

    private val file = File(dir, "session.enc")
    private val maxMessages = 200

    fun save(messages: List<ChatMessage>) {
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
            CryptoBox.sealToFile(file, arr.toString().toByteArray(Charsets.UTF_8))
        } catch (t: Throwable) {
            Log.w("SessionStore", "会话加密落盘失败", t)
        }
    }

    fun load(): List<ChatMessage> {
        return try {
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

    fun clear() {
        try {
            if (file.exists()) file.delete()
        } catch (_: Throwable) {
        }
    }
}
