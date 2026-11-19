package com.pocketforge.ai.data

import org.json.JSONObject

/** 应用配置：整份 JSON 加密后落盘（API Key 不会以明文出现在磁盘上） */
data class AppConfig(
    val baseUrl: String = "https://api.openai.com/v1",
    val apiKey: String = "",
    val model: String = "gpt-4o-mini",
    val temperature: Double = 0.4,
    val maxTokens: Int = 8192,
    // 推理强度：off / low / medium / high / max，透传给支持 reasoning_effort 的上游
    val reasoningEffort: String = "medium",
    val stream: Boolean = true,
    val workspaceRoot: String = "",
    val skillsGate: Boolean = true,
    val streamCipherKey: String = "",
    val maxSteps: Int = 40,
    val systemExtra: String = "",
    // 技能源：默认指向本仓库的 skills 目录，可换成自己的私有地址
    val skillRepoUrl: String = "https://raw.githubusercontent.com/exchangeq/PocketForge/main/app/src/main/assets/skills",
    val skillRepoToken: String = "",
    // Termux 桥接（SSH over 127.0.0.1:8022）
    val allowShell: Boolean = false,
    val termuxHost: String = "127.0.0.1",
    val termuxPort: Int = 8022,
    val termuxUser: String = "",
    val termuxAuth: String = "password",
    val termuxPassword: String = "",
    val termuxKey: String = "",
    val termuxPassphrase: String = "",
    val termuxHostKey: String = "",
    // 发行版远程更新 + GitHub 访问
    val updateRepo: String = "exchangeq/PocketForge",
    val githubToken: String = "",
) {

    fun toJson(): JSONObject = JSONObject().apply {
        put("baseUrl", baseUrl)
        put("apiKey", apiKey)
        put("model", model)
        put("temperature", temperature)
        put("maxTokens", maxTokens)
        put("reasoningEffort", reasoningEffort)
        put("stream", stream)
        put("workspaceRoot", workspaceRoot)
        put("skillsGate", skillsGate)
        put("streamCipherKey", streamCipherKey)
        put("maxSteps", maxSteps)
        put("systemExtra", systemExtra)
        put("skillRepoUrl", skillRepoUrl)
        put("skillRepoToken", skillRepoToken)
        put("allowShell", allowShell)
        put("termuxHost", termuxHost)
        put("termuxPort", termuxPort)
        put("termuxUser", termuxUser)
        put("termuxAuth", termuxAuth)
        put("termuxPassword", termuxPassword)
        put("termuxKey", termuxKey)
        put("termuxPassphrase", termuxPassphrase)
        put("termuxHostKey", termuxHostKey)
        put("updateRepo", updateRepo)
        put("githubToken", githubToken)
    }

    fun endpoint(): String {
        var base = baseUrl.trim().ifEmpty { "https://api.openai.com/v1" }.trimEnd('/')
        if (base.endsWith("/chat/completions")) return base
        val path = base.substringAfter("://", base)
        return if (path.contains("/v1")) "$base/chat/completions"
        else "$base/v1/chat/completions"
    }

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
            maxTokens = raw.optInt("maxTokens", 8192),
            reasoningEffort = raw.optString("reasoningEffort", "medium"),
            stream = raw.optBoolean("stream", true),
            workspaceRoot = raw.optString("workspaceRoot", ""),
            skillsGate = raw.optBoolean("skillsGate", true),
            streamCipherKey = raw.optString("streamCipherKey", ""),
            maxSteps = raw.optInt("maxSteps", 40),
            systemExtra = raw.optString("systemExtra", ""),
            skillRepoUrl = raw.optString(
                "skillRepoUrl",
                "https://raw.githubusercontent.com/exchangeq/PocketForge/main/app/src/main/assets/skills"
            ),
            skillRepoToken = raw.optString("skillRepoToken", ""),
            allowShell = raw.optBoolean("allowShell", false),
            termuxHost = raw.optString("termuxHost", "127.0.0.1"),
            termuxPort = raw.optInt("termuxPort", 8022),
            termuxUser = raw.optString("termuxUser", ""),
            termuxAuth = raw.optString("termuxAuth", "password"),
            termuxPassword = raw.optString("termuxPassword", ""),
            termuxKey = raw.optString("termuxKey", ""),
            termuxPassphrase = raw.optString("termuxPassphrase", ""),
            termuxHostKey = raw.optString("termuxHostKey", ""),
            updateRepo = raw.optString("updateRepo", "exchangeq/PocketForge"),
            githubToken = raw.optString("githubToken", ""),
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

/** 会话全文搜索命中 */
data class SessionHit(val key: String, val title: String, val snippet: String)

data class UiState(
    val messages: List<MsgUi> = emptyList(),
    val busy: Boolean = false,
    val projectName: String = "未命名项目",
    val skillsLoaded: Boolean = false,
    val plan: List<PlanItem> = emptyList(),
    val lastError: String? = null,
    val sessionTitle: String = "主对话",
)
