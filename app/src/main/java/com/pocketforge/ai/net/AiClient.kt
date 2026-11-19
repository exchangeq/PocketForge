package com.pocketforge.ai.net

import com.pocketforge.ai.core.StreamCipher
import com.pocketforge.ai.data.AppConfig
import com.pocketforge.ai.data.ChatMessage
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

sealed class AiEvent {
    data class Delta(val text: String) : AiEvent()
    data class ToolDelta(
        val index: Int,
        val id: String? = null,
        val name: String? = null,
        val args: String? = null,
    ) : AiEvent()

    data class Failed(val message: String) : AiEvent()
    data object Done : AiEvent()
}

/**
 * OpenAI 兼容客户端（/v1/chat/completions），支持 SSE 流式与 function calling。
 * 若配置了流密钥且上游返回加密帧（PF1.…），会先解密再解析，实现“数据流解密 → 转化操作”。
 */
class AiClient {

    fun chat(
        config: AppConfig,
        messages: List<ChatMessage>,
        tools: JSONArray?,
        onEvent: (AiEvent) -> Unit,
    ) {
        var connection: HttpURLConnection? = null
        try {
            val body = JSONObject().apply {
                put("model", config.model)
                put(
                    "messages",
                    JSONArray().also { arr -> messages.forEach { arr.put(it.toApiJson()) } }
                )
                put("temperature", config.temperature)
                put("stream", config.stream)
                put("max_tokens", config.maxTokens)
                if (tools != null && tools.length() > 0) {
                    put("tools", tools)
                    put("tool_choice", "auto")
                }
            }

            val conn = URL(config.endpoint()).openConnection() as HttpURLConnection
            connection = conn
            conn.requestMethod = "POST"
            conn.connectTimeout = 20_000
            conn.readTimeout = 180_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Accept", if (config.stream) "text/event-stream" else "application/json")
            conn.setRequestProperty("Authorization", "Bearer ${config.apiKey.trim()}")
            conn.setRequestProperty("User-Agent", "PocketForge/1.0 (Android)")
            conn.setRequestProperty("X-PocketForge-Client", "android")
            conn.outputStream.use { out ->
                out.write(body.toString().toByteArray(Charsets.UTF_8))
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                val errorText = runCatching {
                    conn.errorStream?.bufferedReader()?.use { it.readText() }
                }.getOrNull().orEmpty()
                onEvent(AiEvent.Failed("HTTP $code：${errorText.take(600).ifEmpty { "无响应体" }}"))
                return
            }

            if (!config.stream) {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                parseComplete(text, onEvent)
                return
            }

            conn.inputStream.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    val trimmed = line.trim()
                    if (!trimmed.startsWith("data:")) continue
                    val raw = trimmed.removePrefix("data:").trim()
                    if (raw.isEmpty() || raw == "[DONE]") continue
                    val payload = if (config.streamCipherKey.isNotEmpty() && StreamCipher.isFrame(raw)) {
                        runCatching { StreamCipher.openFrame(raw, config.streamCipherKey) }
                            .getOrElse {
                                onEvent(AiEvent.Failed("流解密失败：${it.message}"))
                                return
                            }
                    } else {
                        raw
                    }
                    parseChunk(payload, onEvent)
                }
            }
            onEvent(AiEvent.Done)
        } catch (t: Throwable) {
            onEvent(AiEvent.Failed(t.message ?: t.javaClass.simpleName))
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    /** 连通性自检：发一条极短请求，确认 Key / 模型 / 网关可用 */
    fun ping(config: AppConfig): String {
        var connection: HttpURLConnection? = null
        return try {
            val body = JSONObject()
                .put("model", config.model)
                .put(
                    "messages",
                    JSONArray().put(JSONObject().put("role", "user").put("content", "ping"))
                )
                .put("max_tokens", 8)
                .put("stream", false)
            val conn = URL(config.endpoint()).openConnection() as HttpURLConnection
            connection = conn
            conn.requestMethod = "POST"
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Authorization", "Bearer ${config.apiKey.trim()}")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code in 200..299) {
                "连接成功：HTTP $code，模型 ${config.model}"
            } else {
                val err = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }
                    .getOrNull().orEmpty()
                "连接失败：HTTP $code ${err.take(300)}"
            }
        } catch (t: Throwable) {
            "连接失败：${t.message ?: t.javaClass.simpleName}"
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    private fun parseComplete(text: String, onEvent: (AiEvent) -> Unit) {
        val obj = runCatching { JSONObject(text) }.getOrElse {
            onEvent(AiEvent.Failed("响应不是合法 JSON：${text.take(200)}"))
            return
        }
        val choice = obj.optJSONArray("choices")?.optJSONObject(0)
        if (choice == null) {
            onEvent(AiEvent.Failed("响应缺少 choices：${text.take(200)}"))
            return
        }
        val message = choice.optJSONObject("message")
        if (message != null) {
            val content = message.optString("content", "")
            if (content.isNotEmpty()) onEvent(AiEvent.Delta(content))
            val calls = message.optJSONArray("tool_calls")
            if (calls != null) {
                for (i in 0 until calls.length()) {
                    val tc = calls.optJSONObject(i) ?: continue
                    val fn = tc.optJSONObject("function")
                    onEvent(
                        AiEvent.ToolDelta(
                            index = tc.optInt("index", i),
                            id = tc.optString("id", "").ifEmpty { null },
                            name = fn?.optString("name", "")?.ifEmpty { null },
                            args = fn?.optString("arguments", "")?.ifEmpty { null },
                        )
                    )
                }
            }
        }
        onEvent(AiEvent.Done)
    }

    private fun parseChunk(payload: String, onEvent: (AiEvent) -> Unit) {
        val obj = runCatching { JSONObject(payload) }.getOrNull() ?: return
        val choice = obj.optJSONArray("choices")?.optJSONObject(0) ?: return
        val delta = choice.optJSONObject("delta") ?: return
        val content = delta.optString("content", "")
        if (content.isNotEmpty()) onEvent(AiEvent.Delta(content))
        val calls = delta.optJSONArray("tool_calls") ?: return
        for (i in 0 until calls.length()) {
            val tc = calls.optJSONObject(i) ?: continue
            val fn = tc.optJSONObject("function")
            onEvent(
                AiEvent.ToolDelta(
                    index = tc.optInt("index", i),
                    id = tc.optString("id", "").ifEmpty { null },
                    name = fn?.optString("name", "")?.ifEmpty { null },
                    args = fn?.optString("arguments", "")?.ifEmpty { null },
                )
            )
        }
    }
}
