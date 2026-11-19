package com.pocketforge.ai.data

import org.json.JSONObject

/** 应用配置：整份 JSON 加密后落盘（API Key 不会以明文出现在磁盘上） */
data class AppConfig(
    val baseUrl: String = "https://api.openai.com/v1",
    val apiKey: String = "",
    val model: String = "gpt-4o-mini",
    val temperature: Double = 0.4,
    val maxTokens: Int = 2048,
    val stream: Boolean = true,
    val workspaceRoot: String = "",
    val skillsGate: Boolean = true,
    val streamCipherKey: String = "",
    val maxSteps: Int = 24,
    val systemExtra: String = "",
) {

    fun toJson(): JSONObject = JSONObject().apply {
        put("baseUrl", baseUrl)
        put("apiKey", apiKey)
        put("model", model)
        put("temperature", temperature)
        put("maxTokens", maxTokens)
        put("stream", stream)
        put("workspaceRoot", workspaceRoot)
        put("skillsGate", skillsGate)
        put("streamCipherKey", streamCipherKey)
        put("maxSteps", maxSteps)
        put("systemExtra", systemExtra)
    }

    fun endpoint(): String = baseUrl.trim().trimEnd('/') + "/chat/completions"

    fun maskedKey(): String = when {
        apiKey.isEmpty() -> "未配置"
        apiKey.length <= 8 -> "••••"
        else -> apiKey.take(4) + "••••" + apiKey.takeLast(4)
    }

    companion object {
        fun fromJson(raw: JSONObject): AppConfig = AppConfig(
            baseUrl = raw.optString("baseUrl", "https://api.openai.com/v1"),
            apiKey = raw.optString("apiKey", ""),
            model = raw.optString("model", "gpt-4o-mini"),
            temperature = raw.optDouble("temperature", 0.4),
            maxTokens = raw.optInt("maxTokens", 2048),
            stream = raw.optBoolean("stream", true),
            workspaceRoot = raw.optString("workspaceRoot", ""),
            skillsGate = raw.optBoolean("skillsGate", true),
            streamCipherKey = raw.optString("streamCipherKey", ""),
            maxSteps = raw.optInt("maxSteps", 24),
            systemExtra = raw.optString("systemExtra", ""),
        )
    }
}

data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

/** 送给模型的一条消息 */
data class ChatMessage(
    val role: String, // system | user | assistant | tool
    val content: String? = null,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
) {

    fun toApiJson(): JSONObject = JSONObject().apply {
        put("role", role)
        when {
            role == "tool" -> {
                put("tool_call_id", toolCallId ?: "")
                put("content", content ?: "")
            }
            toolCalls.isNotEmpty() -> {
                put("content", content ?: "")
                put("tool_calls", org.json.JSONArray().also { arr ->
                    toolCalls.forEach { call ->
                        arr.put(
                            JSONObject()
                                .put("id", call.id)
                                .put("type", "function")
                                .put(
                                    "function",
                                    JSONObject().put("name", call.name).put("arguments", call.arguments)
                                )
                        )
                    }
                })
            }
            else -> put("content", content ?: "")
        }
    }
}

data class ToolResult(
    val ok: Boolean,
    val summary: String,
    val payload: String = "",
) {
    fun forModel(): String = JSONObject()
        .put("ok", ok)
        .put("summary", summary)
        .put("data", payload.take(200_000))
        .toString()
}

// ---------- UI 状态模型 ----------

enum class StepStatus { RUNNING, OK, FAILED }

/** AI 的单个操作步骤，UI 里折叠展示 */
data class StepUi(
    val id: String,
    val title: String,
    val args: String,
    val status: StepStatus = StepStatus.RUNNING,
    val output: String = "",
)

data class MsgUi(
    val id: String,
    val role: String,
    val text: String = "",
    val steps: List<StepUi> = emptyList(),
    val streaming: Boolean = false,
    val error: String? = null,
)

data class PlanItem(val text: String, val done: Boolean = false)

data class UiState(
    val messages: List<MsgUi> = emptyList(),
    val busy: Boolean = false,
    val projectName: String = "未命名项目",
    val skillsLoaded: Boolean = false,
    val plan: List<PlanItem> = emptyList(),
    val lastError: String? = null,
)
