package com.pocketforge.ai.agent

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.pocketforge.ai.core.Sandbox
import com.pocketforge.ai.data.AppConfig
import com.pocketforge.ai.data.ChatMessage
import com.pocketforge.ai.data.ConfigRepository
import com.pocketforge.ai.data.MsgUi
import com.pocketforge.ai.data.SessionStore
import com.pocketforge.ai.data.StepStatus
import com.pocketforge.ai.data.StepUi
import com.pocketforge.ai.data.ToolCall
import com.pocketforge.ai.data.ToolResult
import com.pocketforge.ai.data.UiState
import com.pocketforge.ai.net.AiClient
import com.pocketforge.ai.net.AiEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 代理循环：一条用户消息 → N 轮「模型输出 / 工具执行」，每步折叠成一张卡片。
 * 技能闸门（skillsGate）在这里强制生效：没读完技能就写文件会被工具层拒绝。
 */
class AgentEngine(context: Context, private val configRepo: ConfigRepository) {

    companion object {
        private const val ROUND_TIMEOUT_MS = 150_000L
    }

    private val appContext = context.applicationContext
    private val sandbox = Sandbox(appContext) { configRepo.config.value }
    private val skills = SkillLibrary(appContext)
    private val client = AiClient()
    private val sessionStore = SessionStore(File(appContext.filesDir, "sessions"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var job: Job? = null
    private var lastTextEmit = 0L
    private val history = mutableListOf<ChatMessage>()
    private var indexRead = false
    private val readSkills = linkedSetOf<String>()

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val tools = ToolRegistry(
        context = appContext,
        sandbox = sandbox,
        skills = skills,
        configProvider = { configRepo.config.value },
        isUnlocked = { indexRead && readSkills.isNotEmpty() },
        onIndexRead = {
            indexRead = true
            _ui.update { it.copy(skillsLoaded = true) }
        },
        onSkillRead = { name -> readSkills.add(name) },
        onPlan = { plan -> _ui.update { it.copy(plan = plan) } },
        onProjectOpened = { name -> _ui.update { it.copy(projectName = name) } },
        onHostKeyLearned = { fingerprint ->
            runCatching { configRepo.save(configRepo.config.value.copy(termuxHostKey = fingerprint)) }
        },
    )

    init {
        restoreSession()
    }

    // ---------------- 对外 API ----------------

    fun send(text: String) {
        val input = text.trim()
        if (input.isEmpty() || _ui.value.busy) return
        history.add(ChatMessage(role = "user", content = input))
        _ui.update {
            it.copy(messages = it.messages + MsgUi(newId(), "user", input), busy = true, lastError = null)
        }

        val config = configRepo.config.value
        if (config.apiKey.isBlank()) {
            val reply = buildString {
                appendLine("还没配置 API Key，我暂时没法联网回答。")
                appendLine()
                appendLine("去底部「设置」：")
                appendLine("1. Base URL 填到 /v1（例：https://api.openai.com/v1）")
                appendLine("2. 填 API Key，点「保存配置」")
                appendLine("3. 点「测试连接」，看到「连接成功」就回来发这句")
                append("4. 想读写手机目录的话，顺手把「所有文件访问」授权了")
            }
            history.add(ChatMessage(role = "assistant", content = reply))
            _ui.update {
                it.copy(
                    messages = it.messages + MsgUi(newId(), "assistant", reply),
                    busy = false,
                    lastError = "未配置 API Key",
                )
            }
            sessionStore.save(history)
            return
        }

        job = scope.launch {
            try {
                runLoop()
            } catch (ce: CancellationException) {
                _ui.update { st ->
                    st.copy(busy = false, messages = st.messages.map { m -> if (m.streaming) m.copy(streaming = false) else m })
                }
                throw ce
            } catch (t: Throwable) {
                Log.e("AgentEngine", "回合执行失败", t)
                _ui.update { st ->
                    st.copy(
                        busy = false,
                        lastError = t.message ?: t.javaClass.simpleName,
                        messages = st.messages.map { m ->
                            when {
                                m.streaming && m.text.isBlank() -> m.copy(
                                    text = "这轮没拿到有效回复，可能网络中断或超时。再发一次，或到设置点「测试连接」检查通道。",
                                    streaming = false,
                                )
                                m.streaming -> m.copy(streaming = false)
                                else -> m
                            }
                        },
                    )
                }
            } finally {
                _ui.update { st ->
                    st.copy(
                        busy = false,
                        messages = st.messages.map { m -> if (m.streaming) m.copy(streaming = false) else m },
                    )
                }
                sessionStore.save(history)
            }
        }
    }

    fun stop() {
        client.cancel()
        job?.cancel()
    }

    fun clearSession() {
        job?.cancel()
        history.clear()
        readSkills.clear()
        indexRead = false
        sessionStore.clear()
        _ui.value = UiState()
    }

    fun workspacePath(): String = runCatching { sandbox.workspace().absolutePath }.getOrElse { "不可用" }

    fun hasAllFilesAccess(): Boolean = sandbox.hasAllFilesAccess()

    fun keyFingerprint(): String = configRepo.keyFingerprint()

    fun encryptedAtRest(): Boolean = configRepo.encryptedAtRest()

    fun saveConfig(config: AppConfig): Boolean = configRepo.save(config)

    fun currentConfig(): AppConfig = configRepo.config.value

    /** 权限与运行环境体检（给设置页展示，逐项列出可读/可写状态） */
    fun permissionReport(): String = buildString {
        val sdk = Build.VERSION.SDK_INT
        appendLine("系统：Android $sdk（${Build.MANUFACTURER} ${Build.MODEL}）")
        appendLine("所有文件访问：${if (sandbox.hasAllFilesAccess()) "已授权 ✓" else "未授权 ✗（只能写应用私有目录）"}")
        val readGranted = ContextCompat.checkSelfPermission(
            appContext, android.Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
        appendLine("读存储权限：${if (readGranted) "已授权 ✓" else "未授权 ✗"}")
        appendLine("网络：INTERNET 已声明（无需运行时授权）")
        appendLine("工作区：${runCatching { sandbox.workspace().absolutePath }.getOrElse { "不可用 ✗" }}")
        val root = File("/storage/emulated/0")
        appendLine("手机根目录：${if (root.canRead()) "可读 ✓" else "不可读 ✗"}")
    }

    /** 真写一次再删掉，用来验证工作区是否真的可写 */
    fun storageSelfCheck(): String {
        val ws = runCatching { sandbox.workspace() }.getOrElse {
            return "写入自检失败：工作区不可用（${it.message}）"
        }
        return try {
            val probe = File(ws, ".pocketforge-write-test")
            probe.writeText("ok-${System.currentTimeMillis()}")
            val size = probe.length()
            probe.delete()
            "写入自检通过 ✓ ${ws.absolutePath}（探测 ${size}B，已清理）"
        } catch (t: Throwable) {
            "写入自检失败 ✗ ${ws.absolutePath}：${t.message}"
        }
    }

    fun testConnection(): String = client.ping(configRepo.config.value)

    fun projectNames(): List<String> = runCatching {
        sandbox.projectsDir().listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted() ?: emptyList()
    }.getOrElse { emptyList() }

    fun installSkills(): String = runCatching {
        val count = skills.installTo(sandbox.workspace(), force = false)
        "已同步 $count 个内置技能 → ${skills.skillsDir(sandbox.workspace()).absolutePath}"
    }.getOrElse { "安装失败：${it.message}" }

    fun skillEntries(): List<Pair<String, String>> = SkillLibrary.ENTRIES

    fun skillText(name: String): String = skills.read(name, runCatching { sandbox.workspace() }.getOrNull())

    /** 技能中心数据：文件名、说明、是否已落在当前工作区 */
    fun skillStatus(): List<Triple<String, String, Boolean>> {
        val ws = runCatching { sandbox.workspace() }.getOrNull()
        val dir = ws?.let { skills.skillsDir(it) }
        val locals = dir?.listFiles { f -> f.isFile && f.name.endsWith(".md") }?.map { it.name }?.toSet()
            ?: emptySet()
        val extras = locals.filter { name -> SkillLibrary.ENTRIES.none { it.first == name } }
        return SkillLibrary.ENTRIES.map { (name, desc) -> Triple(name, desc, locals.contains(name)) } +
            extras.map { Triple(it, "工作区自定义技能", true) }
    }

    fun forceInjected(): Boolean = runCatching { skills.isForced(sandbox.workspace()) }.getOrDefault(false)

    fun installRemoteSkills(url: String, token: String, force: Boolean): String {
        val ws = runCatching { sandbox.workspace() }.getOrNull()
            ?: return "工作区不可用，无法注入"
        val result = skills.remoteInstall(url, token, ws, force)
        if (result.ok) {
            indexRead = true
            readSkills.add("SKILLS.md")
            _ui.update { it.copy(skillsLoaded = true) }
        }
        return result.message
    }

    fun forceInjectFromBundle(): String = runCatching {
        val count = skills.forceInject(sandbox.workspace())
        indexRead = true
        _ui.update { it.copy(skillsLoaded = true) }
        "强制注入完成：$count 个文件，仅当前工作区生效（${skills.skillsDir(sandbox.workspace()).absolutePath}）"
    }.getOrElse { "强制注入失败：${it.message}" }

    fun disableForceInject(): String = runCatching {
        skills.clearForce(sandbox.workspace())
        "已关闭强制注入，之后缺失的技能会回退到内置基线"
    }.getOrElse { "取消失败：${it.message}" }

    fun termuxTest(): String {
        val outcome = com.pocketforge.ai.termux.SshClient.test(configRepo.config.value)
        if (outcome.hostKeyLearned && outcome.fingerprint.isNotBlank()) {
            configRepo.save(configRepo.config.value.copy(termuxHostKey = outcome.fingerprint))
        }
        return outcome.message
    }

    fun trustNewHostKey(): String {
        configRepo.save(configRepo.config.value.copy(termuxHostKey = ""))
        return "已清除记录的主机指纹，下次连接会重新学习"
    }

    fun createProject(name: String, type: String, engine: String): ToolResult {
        val args = JSONObject()
            .put("name", name)
            .put("type", type)
            .put("engine", engine)
            .toString()
        return tools.dispatch("project_new", args)
    }

    // ---------------- 主循环 ----------------

    private suspend fun runLoop() {
        val config = configRepo.config.value
        val maxRounds = config.maxSteps.coerceIn(1, 60)
        var round = 0
        while (round < maxRounds) {
            round++
            val messageId = newId()
            _ui.update { it.copy(messages = it.messages + MsgUi(messageId, "assistant", streaming = true)) }

            val buffer = StringBuilder()
            val pending = sortedMapOf<Int, PendingCall>()
            var failure: String? = null

            // 看门狗：单轮最多 150s，超时直接掐断连接，避免 UI 永远停在「生成中」
            val timedOut = AtomicBoolean(false)
            val watchdog = scope.launch {
                delay(ROUND_TIMEOUT_MS)
                timedOut.set(true)
                runCatching { client.cancel() }
            }
            client.chat(config, buildMessages(config), tools.schemas) { event ->
                when (event) {
                    is AiEvent.Delta -> {
                        buffer.append(event.text)
                        updateText(messageId, buffer.toString())
                    }
                    is AiEvent.ToolDelta -> {
                        val call = pending.getOrPut(event.index) { PendingCall() }
                        event.id?.takeIf { it.isNotBlank() }?.let { call.id = it }
                        event.name?.takeIf { it.isNotBlank() }?.let { call.name = it }
                        event.args?.takeIf { it.isNotEmpty() }?.let { call.args.append(it) }
                    }
                    is AiEvent.Failed -> failure = event.message
                    AiEvent.Done -> Unit
                }
            }
            watchdog.cancel()
            if (timedOut.get()) {
                failure = "本轮请求超过 ${ROUND_TIMEOUT_MS / 1000}s 没有响应，已中止。可拆小目标后重试。"
            }

            val content = buffer.toString()
            val toolCalls = pending.values
                .filter { it.name.isNotBlank() }
                .map {
                    ToolCall(
                        id = it.id.ifBlank { newId() },
                        name = it.name,
                        arguments = it.args.toString().ifBlank { "{}" },
                    )
                }

            if (toolCalls.isEmpty() && content.isBlank()) {
                throw IllegalStateException(
                    failure ?: "模型这轮没有返回任何内容。可以把目标拆小一点，或到设置里点「测试连接」看通道是否正常。"
                )
            }

            history.add(ChatMessage(role = "assistant", content = content, toolCalls = toolCalls))
            finishMessage(messageId, content, failure, toolCalls.size)

            if (toolCalls.isEmpty()) break

            toolCalls.forEach { call ->
                val stepId = call.id
                val label = ToolRegistry.label(call.name)
                val shortArgs = prettyArgs(call.arguments)
                upsertStep(messageId, StepUi(stepId, label, shortArgs))

                val result = tools.dispatch(call.name, call.arguments)

                upsertStep(
                    messageId,
                    StepUi(
                        id = stepId,
                        title = label,
                        args = shortArgs,
                        status = if (result.ok) StepStatus.OK else StepStatus.FAILED,
                        output = buildString {
                            appendLine(result.summary)
                            if (result.payload.isNotBlank()) append(result.payload.take(3000))
                        }.trim(),
                    )
                )
                history.add(
                    ChatMessage(role = "tool", toolCallId = call.id, content = result.forModel())
                )
                sessionStore.save(history)
            }
        }
    }

    private fun buildMessages(config: AppConfig): List<ChatMessage> {
        trimHistory()
        val system = ChatMessage(role = "system", content = systemPrompt(config))
        return listOf(system) + history
    }

    /**
     * 上下文裁剪：上游对 tool 消息有严格顺序要求，
     * 窗口起点绝不能落在 tool 消息上，否则会 400。
     */
    private fun trimHistory(limit: Int = 60) {
        if (history.isEmpty()) return
        var start = if (history.size <= limit) 0 else history.size - limit
        while (start < history.size && history[start].role == "tool") start++
        if (start >= history.size) {
            history.clear()
            return
        }
        if (start > 0) history.subList(0, start).clear()
    }

    private fun systemPrompt(config: AppConfig): String = buildString {
        appendLine("你是 PocketForge —— 运行在 Android 手机上的项目代理，把用户的想法落成真实文件与可交付项目。")
        appendLine("工作区：${workspacePath()}（写入只允许在这里）")
        appendLine("可读范围：工作区 + 手机公共存储根目录（/storage/emulated/0），系统分区与其他应用数据一律不可访问。")
        appendLine("可用技能：${SkillLibrary.ENTRIES.joinToString("、") { it.first }}")
        appendLine()
        appendLine("## 硬性规则（违反即视为失败）")
        appendLine("1. 任何新项目 / 新目录 / 新文件之前，必须先调用 skills_index 读总索引，再按项目类型调用 skill_read（game-dev.md / writing.md / app-dev.md），并读 boundaries.md。")
        appendLine("2. 只允许写工作区；读取可以覆盖公共存储根目录，但绝不修改。")
        appendLine("3. 不要臆造文件、目录、命令或 API；不确定就先 fs_list / fs_read 确认。")
        appendLine("4. 开工前用 plan_set 汇报分步计划；一次只推进一个可验证的小步骤，写完后立刻自检。")
        appendLine("5. 删除一律用 fs_delete（默认进 .trash 回收站）。")
        appendLine("6. 回复用中文：先给结论，再列「改了哪些文件」，最后给「下一步」。不要输出 API Key 或任何密钥。")
        appendLine("7. 游戏项目只做纯前端/纯代码能跑通的方案（如 HTML5/Godot 工程文件/纯 Kotlin 逻辑），不要声称能在这里编译 APK。")
        appendLine("8. 回答要完整：结论 → 关键点（分条，必要时用表格）→ 文件清单 → 下一步。禁止半截话、禁止把过程塞成一大段，也不要只丢一句「已完成」。")
        appendLine("9. 排版用标准 Markdown（标题、列表、表格、代码块）；不要在正文里堆 ** 星号做装饰。")
        appendLine("10. 技能可以扩展：缺规范时用 skill_install 从远端 .md 或 skills-index.json 注入到当前工作区，注入后读一遍再用。")
        if (config.allowShell) {
            appendLine("11. Termux 可用：termux_exec 能跑 shell（只允许在 Termux 自己的目录里操作，破坏性命令会被拦截）。需要环境信息时先用 termux_status 或 pkg list-installed 之类只读命令。")
        } else {
            appendLine("11. Termux 目前关闭（allowShell=false）：不要声称已经执行 shell，需要时让用户去设置页打开。")
        }
        if (config.skillsGate) {
            appendLine("8. 技能闸门已开启：没读完技能说明时，写文件会被工具层直接拒绝。")
        }
        if (config.systemExtra.isNotBlank()) {
            appendLine()
            appendLine("## 用户附加要求")
            appendLine(config.systemExtra.trim())
        }
    }

    // ---------------- UI 更新 ----------------

    private fun updateText(messageId: String, text: String) {
        // 流式输出按 ~55ms 节流，避免每 token 都触发重组导致卡顿
        val now = System.currentTimeMillis()
        if (now - lastTextEmit < 55) return
        lastTextEmit = now
        _ui.update { state ->
            state.copy(messages = state.messages.map { msg ->
                if (msg.id == messageId) msg.copy(text = text) else msg
            })
        }
    }

    private fun finishMessage(messageId: String, text: String, failure: String?, toolCount: Int) {
        _ui.update { state ->
            state.copy(messages = state.messages.map { msg ->
                if (msg.id == messageId) {
                    val fallback = if (toolCount > 0) {
                        "已执行 $toolCount 项操作，结果见上方步骤卡片。"
                    } else {
                        "本轮没有文本输出。"
                    }
                    msg.copy(
                        text = text.ifBlank { fallback },
                        streaming = false,
                        error = failure,
                    )
                } else {
                    msg
                }
            })
        }
    }

    private fun upsertStep(messageId: String, step: StepUi) {
        _ui.update { state ->
            state.copy(messages = state.messages.map { msg ->
                if (msg.id != messageId) return@map msg
                val index = msg.steps.indexOfFirst { it.id == step.id }
                val steps = if (index >= 0) {
                    msg.steps.toMutableList().also { it[index] = step }
                } else {
                    msg.steps + step
                }
                msg.copy(steps = steps)
            })
        }
    }

    // ---------------- 杂项 ----------------

    private fun restoreSession() {
        val restored = sessionStore.load()
        if (restored.isEmpty()) return
        history.addAll(restored)
        trimHistory()
        val visible = restored
            .filter { it.role == "user" || (it.role == "assistant" && !it.content.isNullOrBlank()) }
            .map { MsgUi(newId(), it.role, it.content.orEmpty()) }
        _ui.update { it.copy(messages = visible) }
    }

    private fun prettyArgs(raw: String): String {
        if (raw.isBlank()) return "{}"
        return runCatching { JSONObject(raw).toString(2) }.getOrElse { raw.take(400) }
    }

    private fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(10)

    private class PendingCall {
        var id: String = ""
        var name: String = ""
        val args = StringBuilder()
    }
}
