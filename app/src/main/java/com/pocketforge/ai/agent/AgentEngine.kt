package com.pocketforge.ai.agent

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
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
    private val updater = com.pocketforge.ai.update.UpdateManager(appContext) { configRepo.config.value }
    private val sandbox = Sandbox(appContext) { configRepo.config.value }
    private val skills = SkillLibrary(appContext)
    private val client = AiClient()
    private val sessionStore = SessionStore(File(appContext.filesDir, "sessions"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var sessionKey: String = "main"
    private var job: Job? = null
    private var lastTextEmit = 0L
    private val history = mutableListOf<ChatMessage>()
    private var indexRead = false
    private val readSkills = linkedSetOf<String>()

    private val noRecallFile = File(appContext.filesDir, "no-recall.json")

    private var noRecall: Boolean = false

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
        updateCheck = {
            val r = updater.check()
            buildString {
                appendLine(r.message)
                val rel = r.release
                if (rel != null) {
                    appendLine("tag: ${rel.tag}")
                    appendLine("apk: ${rel.apkSize / 1048576}MB")
                    appendLine("页面: ${rel.htmlUrl}")
                    if (rel.notes.isNotBlank()) {
                        appendLine()
                        appendLine(rel.notes.take(600))
                    }
                }
            }.toString()
        },
        updateInstall = { updater.install(updater.download()) },
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
            sessionStore.save(sessionKey, history)
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
                sessionStore.save(sessionKey, history)
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
        sessionStore.clear(sessionKey)
        val title = sessionStore.title(sessionKey, "主对话")
        _ui.value = UiState(projectName = title, sessionTitle = title)
    }

    // ---------------- 多会话（一个项目一个对话页） ----------------

    // ---------------- 防撤回 / 消息编辑 ----------------

    private fun noRecallMap(): org.json.JSONObject = runCatching {
        if (noRecallFile.exists()) org.json.JSONObject(noRecallFile.readText()) else org.json.JSONObject()
    }.getOrDefault(org.json.JSONObject())

    private fun persistNoRecall(key: String, on: Boolean) {
        runCatching {
            val obj = noRecallMap()
            obj.put(key, on)
            noRecallFile.writeText(obj.toString())
        }
    }

    fun isNoRecall(): Boolean = noRecallMap().optBoolean(sessionKey, false)

    /** 开启后，本会话已写完的内容不可编辑、不可撤回 */
    fun setNoRecall(on: Boolean): String {
        noRecall = on
        persistNoRecall(sessionKey, on)
        return if (on) "已开启防撤回：本会话已写内容锁定，无法修改" else "已关闭防撤回"
    }

    /** 复制整段会话为纯文本 */
    fun copyTranscript(): String = _ui.value.messages.joinToString("\n\n") { m ->
        val role = if (m.role == "user") "我" else "助手"
        "【$role】\n" + m.text + m.steps.joinToString("") { st -> "\n· " + st.title }
    }

    /** 编辑一条消息；防撤回开启时直接拒绝 */
    fun editMessage(id: String, newText: String): String {
        if (isNoRecall()) return "防撤回已开启：本会话已写内容不可修改（可在设置里关闭后再改）"
        val target = _ui.value.messages.firstOrNull { it.id == id } ?: return "消息不存在"
        val old = target.text
        _ui.update { st -> st.copy(messages = st.messages.map { if (it.id == id) it.copy(text = newText, error = null) else it }) }
        // 同步历史里的对应消息，保证后续上下文一致
        if (target.role == "user" || target.role == "assistant") {
            val idx = history.indexOfLast { it.role == target.role && it.content == old }
            if (idx >= 0) {
                val calls = history[idx].toolCalls
                history[idx] = ChatMessage(role = target.role, content = newText, toolCalls = calls, toolCallId = history[idx].toolCallId)
            }
        }
        sessionStore.save(sessionKey, history)
        return "已修改"
    }

    fun currentSessionKey(): String = sessionKey

    fun currentSessionTitle(): String = sessionStore.title(sessionKey, if (sessionKey == "main") "主对话" else sessionKey)

    /** 列出会话：key / 标题 / 消息条数 */
    fun sessions(): List<Triple<String, String, Int>> {
        val keys = (listOf("main") + sessionStore.keys()).distinct()
        return keys.map { key ->
            val title = sessionStore.title(key, if (key == "main") "主对话" else key)
            Triple(key, title, sessionStore.load(key).count { it.role == "user" || it.role == "assistant" })
        }
    }

    /** 切换会话：先保存当前，再载入目标 */
    fun switchSession(key: String): Boolean {
        if (key == sessionKey) return true
        job?.cancel()
        sessionStore.save(sessionKey, history)
        history.clear()
        readSkills.clear()
        indexRead = false
        sessionKey = key
        history.addAll(sessionStore.load(key))
        val title = sessionStore.title(key, if (key == "main") "主对话" else key)
        val visible = history
            .filter { it.role == "user" || (it.role == "assistant" && !it.content.isNullOrBlank()) }
            .map { MsgUi(newId(), it.role, it.content.orEmpty()) }
        _ui.value = UiState(messages = visible, projectName = title, sessionTitle = title)
        return true
    }

    /** 新建一个会话页；key 为空则生成临时 key */
    fun newSession(title: String, key: String = "chat-" + System.currentTimeMillis()): String {
        sessionStore.save(sessionKey, history)
        sessionKey = key
        history.clear()
        readSkills.clear()
        indexRead = false
        sessionStore.setTitle(key, title)
        persistNoRecall(key, false)
        noRecall = false
        _ui.value = UiState(projectName = title, sessionTitle = title)
        return key
    }

    fun slugOf(name: String): String = name.trim().lowercase()
        .replace(Regex("[^a-z0-9\\u4e00-\\u9fff]+"), "-")
        .trim('-').ifEmpty { "project-" + System.currentTimeMillis() }

    fun enterProjectSessionByName(name: String): String = enterProjectSession(slugOf(name), name)

    /** 只建页不切走：创建项目时后台预备一个对话页 */
    fun ensureProjectSession(name: String): String {
        val key = "project-" + slugOf(name)
        if (!sessionStore.exists(key)) {
            sessionStore.setTitle(key, name)
            sessionStore.save(key, emptyList())
            return "已为「$name」新建对话页"
        }
        return "「$name」已有对话页"
    }

    /** 项目专属会话：已存在则直接进入，否则新建并进入 */
    fun enterProjectSession(slug: String, title: String): String {
        val key = "project-" + slug
        val existed = sessionStore.exists(key)
        if (!existed) {
            sessionStore.setTitle(key, title)
            sessionStore.save(key, emptyList())
        }
        switchSession(key)
        sessionStore.setTitle(key, title)
        _ui.update { it.copy(projectName = title, sessionTitle = title) }
        return if (existed) "已进入「$title」的对话页" else "已为「$title」新建对话页"
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

    // ---------------- 本地文件注入 ----------------

    /** 从系统文件选择器拿到的 .md/.txt 直接注入到当前工作区的 skills/ */
    fun installSkillFiles(uris: List<Uri>): String {
        if (uris.isEmpty()) return "没有选中文件"
        val ws = runCatching { sandbox.workspace() }.getOrNull() ?: return "工作区不可用"
        val dir = skills.skillsDir(ws).apply { mkdirs() }
        val ok = mutableListOf<String>()
        val failed = mutableListOf<String>()
        uris.forEach { uri ->
            val name = displayName(uri) ?: ("skill-" + System.currentTimeMillis() + ".md")
            val text = runCatching {
                appContext.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            when {
                text == null -> failed.add("$name（读不出来）")
                text.length < 16 -> failed.add("$name（内容太短）")
                text.length > 512 * 1024 -> failed.add("$name（超过 512KB）")
                !text.contains("#") -> failed.add("$name（不是 Markdown）")
                else -> {
                    val target = java.io.File(dir, skills.sanitizeName(name))
                    target.writeText(text)
                    ok.add(target.name)
                }
            }
        }
        if (ok.isNotEmpty()) {
            indexRead = true
            readSkills.add("SKILLS.md")
            _ui.update { it.copy(skillsLoaded = true) }
        }
        return buildString {
            if (ok.isNotEmpty()) append("已注入 ${ok.size} 个技能：").append(ok.joinToString("、"))
            if (failed.isNotEmpty()) {
                if (isNotEmpty()) append("\n")
                append("未注入：").append(failed.joinToString("、"))
            }
            append("\n目录：").append(dir.absolutePath)
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        appContext.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val index = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && c.moveToFirst()) c.getString(index) else null
        }
    }.getOrNull()

    /** 项目文件树（最多 200 项），给项目页展开用 */
    fun projectFiles(projectName: String): List<String> {
        val root = runCatching { sandbox.projectsDir() }.getOrElse { return emptyList() }
        val dir = root.listFiles()?.firstOrNull { d ->
            d.isDirectory && (d.name == projectName || runCatching {
                org.json.JSONObject(java.io.File(d, ".pocketforge/project.json").readText()).optString("name") == projectName
            }.getOrDefault(false))
        } ?: return emptyList()
        val out = mutableListOf<String>()
        fun walk(f: java.io.File, prefix: String, depth: Int) {
            if (out.size >= 200 || depth > 4) return
            f.listFiles()?.sortedBy { it.name }?.forEach { child ->
                if (out.size >= 200) return
                if (child.name.startsWith(".") && child.name != ".pocketforge") return@forEach
                val rel = if (prefix.isEmpty()) child.name else prefix + "/" + child.name
                if (child.isDirectory) {
                    out.add(rel + "/")
                    walk(child, rel, depth + 1)
                } else out.add(rel)
            }
        }
        walk(dir, "", 0)
        return out
    }

    /** 有新版本时返回 tag，否则 null */
    fun updateAvailable(): String? = runCatching {
        val r = updater.check()
        if (!r.upToDate) r.release?.tag else null
    }.getOrNull()

    fun lockedDocs(): List<Pair<String, String>> = runCatching {
        val ws = sandbox.workspace()
        DocLocks { ws }.list().map { entry ->
            val scope = when {
                entry.hard -> "硬锁"
                entry.sections.isEmpty() -> "整篇保护"
                else -> "章节：" + entry.sections.joinToString(" / ")
            }
            entry.path to scope
        }
    }.getOrDefault(emptyList())

    fun unlockDoc(path: String): String = runCatching {
        val rel = sandbox.relative(sandbox.resolveWrite(path))
        val ok = DocLocks { sandbox.workspace() }.unlock(rel)
        if (ok) "已解锁 $rel" else "这个文件本来就没上锁"
    }.getOrElse { "解锁失败：${it.message}" }

    // ---------------- 远程更新 ----------------

    /** 给 UI 用的后台执行入口，跑在引擎自己的 IO 作用域 */
    fun onIo(block: suspend () -> Unit) {
        scope.launch { runCatching { block() } }
    }

    fun currentVersion(): String = updater.currentVersion()

    /** 会话导出为 Markdown，落在工作区 exports/ */
    fun exportSession(): String = runCatching {
        val ws = sandbox.workspace()
        val dir = File(ws, "exports").apply { mkdirs() }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val file = File(dir, "chat-" + stamp + ".md")
        val md = StringBuilder()
        md.appendLine("# PocketForge 会话记录").appendLine()
        md.appendLine("- 导出时间：" + stamp)
        md.appendLine("- 模型：" + configRepo.config.value.model)
        md.appendLine("- 消息数：" + history.size).appendLine()
        history.forEach { m ->
            when (m.role) {
                "user" -> md.appendLine("## 用户").appendLine().appendLine(m.content.orEmpty()).appendLine()
                "assistant" -> {
                    md.appendLine("## 助手").appendLine()
                    m.toolCalls?.forEach { tc ->
                        md.appendLine("- 工具 `" + tc.name + "` " + tc.arguments.take(160))
                    }
                    if (!m.content.isNullOrBlank()) md.appendLine().appendLine(m.content)
                    md.appendLine()
                }
                "tool" -> md.appendLine("- 工具结果：" + m.content.orEmpty().lines().firstOrNull().orEmpty().take(200))
            }
        }
        file.writeText(md.toString())
        "已导出：exports/" + file.name + "（" + md.length + " 字符）"
    }.getOrElse { "导出失败：" + it.message }

    /** 存储占用概览 */
    fun storageUsage(): String = runCatching {
        fun size(f: File): Long = if (!f.exists()) 0 else if (f.isFile) f.length() else f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        fun mb(v: Long) = String.format("%.1fMB", v / 1048576.0)
        val ws = sandbox.workspace()
        val data = File(appContext.filesDir, "sessions")
        val cache = appContext.cacheDir
        "工作区 " + mb(size(ws)) + " · 会话 " + mb(size(data)) + " · 缓存 " + mb(size(cache))
    }.getOrElse { "统计失败：" + it.message }

    /** 清理缓存（保留 update 目录里的安装包） */
    fun clearCache(): String = runCatching {
        val cache = appContext.cacheDir
        var freed = 0L
        cache.listFiles()?.forEach { child ->
            if (child.name == "update") return@forEach
            freed += if (child.isDirectory) child.walkTopDown().filter { it.isFile }.sumOf { it.length() } else child.length()
            if (child.isDirectory) child.deleteRecursively() else child.delete()
        }
        "已清理缓存 " + String.format("%.1fMB", freed / 1048576.0)
    }.getOrElse { "清理失败：" + it.message }

    fun checkUpdate(): String {
        val r = updater.check()
        val rel = r.release
        return buildString {
            append(r.message)
            if (rel != null) {
                append("\napk：${rel.apkSize / 1048576}MB · ${rel.tag}\n${rel.htmlUrl}")
                if (rel.notes.isNotBlank()) append("\n\n" + rel.notes.take(500))
            }
        }
    }

    fun downloadAndInstallUpdate(onProgress: (Int, String) -> Unit): String = runCatching {
        val file = updater.download { percent, text -> onProgress(percent, text) }
        updater.verify(file)?.let { return@runCatching "校验未通过：$it" }
        updater.install(file)
    }.getOrElse { "更新失败：${it.message}" }

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

    fun createProject(name: String, type: String, engine: String, scale: String = "standard"): ToolResult {
        // 用户手动点「新建」＝已确认意图，这里补记技能读取，避免闸门把骨架拦在半路
        indexRead = true
        readSkills.add("SKILLS.md")
        readSkills.add(runCatching { skills.skillFor(type) }.getOrDefault("game-dev.md"))
        _ui.update { it.copy(skillsLoaded = true) }
        val args = JSONObject()
            .put("name", name)
            .put("type", type)
            .put("engine", engine)
            .put("scale", scale)
            .toString()
        val result = tools.dispatch("project_new", args)
        if (result.ok) openProjectChat(name)
        return result
    }

    /** 一个项目一个对话页：进入或新建 project-<slug> 会话 */
    fun openProjectChat(name: String): String {
        val key = "project-" + slugOf(name)
        if (!sessionStore.exists(key)) {
            sessionStore.setTitle(key, name)
            if (key == sessionKey) return key
            sessionStore.save(sessionKey, history)
            sessionKey = key
            history.clear()
            readSkills.clear()
            indexRead = false
            _ui.value = UiState(projectName = name, sessionTitle = name)
            return key
        }
        switchSession(key)
        return key
    }

    /** 撤回上一轮用户消息：把它从历史里摘掉并返回文本，供输入框修改后重发 */
    fun rewindLastTurn(): String {
        val idx = history.indexOfLast { it.role == "user" }
        if (idx < 0) return ""
        val text = history[idx].content.orEmpty()
        while (history.size > idx) history.removeAt(history.size - 1)
        val visible = history
            .filter { it.role == "user" || (it.role == "assistant" && !it.content.isNullOrBlank()) }
            .map { MsgUi(newId(), it.role, it.content.orEmpty()) }
        _ui.update { it.copy(messages = visible, plan = it.plan, busy = false) }
        sessionStore.save(sessionKey, history)
        return text
    }

    /** 导入用户文档到当前工作区 docs/inbox/，供 AI 读取 */
    fun importDocuments(uris: List<Uri>): String {
        if (uris.isEmpty()) return "没有选中文件"
        val ws = runCatching { sandbox.workspace() }.getOrNull() ?: return "工作区不可用"
        val dir = java.io.File(ws, "docs/inbox").apply { mkdirs() }
        val ok = mutableListOf<String>()
        val bad = mutableListOf<String>()
        uris.forEach { uri ->
            val name = displayName(uri) ?: ("doc-" + System.currentTimeMillis() + ".txt")
            val text = runCatching {
                appContext.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            when {
                text == null -> bad.add("$name（读不出来）")
                text.length > 4 * 1024 * 1024 -> bad.add("$name（超过 4MB）")
                else -> { java.io.File(dir, name).writeText(text); ok.add(name) }
            }
        }
        return buildString {
            if (ok.isNotEmpty()) append("已导入 ${ok.size} 份文档到 docs/inbox：").append(ok.joinToString("、"))
            if (bad.isNotEmpty()) append("\n未导入：").append(bad.joinToString("、"))
            append("\n提示 AI：先读 docs/inbox 再动笔。")
        }
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
                sessionStore.save(sessionKey, history)
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
        appendLine("5. 任何交付都按 quality-output.md 自检：不留 TODO / 省略号 / 空实现，不写填充句，命名与数值前后一致；交付先给结论，再给依据，最后给下一步。")
        appendLine("6. 用户消息里出现 `【附件：文件名】` 或让你读工作区 docs/ 下的文件时，按 document-ingest.md 处理：先建事实清单，再动笔；不擅自改写用户原文里的数字与称谓。")
        appendLine("5. 删除一律用 fs_delete（默认进 .trash 回收站）。")
        appendLine("6. 回复用中文：先给结论，再列「改了哪些文件」，最后给「下一步」。不要输出 API Key 或任何密钥。")
        appendLine("7. 游戏项目只做纯前端/纯代码能跑通的方案（如 HTML5/Godot 工程文件/纯 Kotlin 逻辑），不要声称能在这里编译 APK。")
        appendLine("8. 回答要完整：结论 → 关键点（分条，必要时用表格）→ 文件清单 → 下一步。禁止半截话、禁止把过程塞成一大段，也不要只丢一句「已完成」。")
        appendLine("9. 排版用标准 Markdown（标题、列表、表格、代码块）；不要在正文里堆 ** 星号做装饰。")
        appendLine("10. 技能可以扩展：缺规范时用 skill_install 从远端 .md 或 skills-index.json 注入到当前工作区，注入后读一遍再用。")
        when (config.reasoningEffort) {
            "off" -> appendLine("12. 推理强度=关：直接给结论和动作，不写内心推演。")
            "low" -> appendLine("12. 推理强度=低：先做最直接的方案，不比较备选。")
            "high", "max" -> appendLine("12. 推理强度=高：动手前先列 2~3 个方案并选一个，写完后自检两次（结构 + 事实），再给结论。")
            else -> appendLine("12. 推理强度=中：动手前花一句话说明思路，写完自检一次。")
        }
        appendLine("13. 长文本没有上限，但单次工具调用有 4MB 上限：长文件按「分卷 append」写，每卷 ≤ 300 行，写完读回尾部校验，禁止一次硬塞。")
        appendLine("14. 文档锁：定稿的设定/章节可先 doc_lock（soft 默认，hard 全禁写）。写入被锁文件会被工具层拒绝，除非用户明确要求解锁。")
        appendLine("15. 技能库很大（49 份），按需读，不要全读；读哪份由项目类型决定。")
        appendLine("18. 生成内容按 content-quality.md 自检：完整、具体、有据、去 AI 味；不许用占位符冒充成品。")
        appendLine("19. 用户导入的文档在 docs/inbox/（项目页「导入文档」写入）：动笔前先 fs_list + fs_read，引用要标来源，缺页就问，不猜。")
        appendLine("16. 找文件用 fs_search（先文件名，再 content=true）；不要自己遍历猜测路径。")
        appendLine("17. 只有用户明确要求时才调 update_check / update_install；安装包必须是本应用签名，校验不通过就停。")
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
        val restored = sessionStore.load(sessionKey)
        if (restored.isEmpty()) return
        history.addAll(restored)
        trimHistory()
        val visible = restored
            .filter { it.role == "user" || (it.role == "assistant" && !it.content.isNullOrBlank()) }
            .map { MsgUi(newId(), it.role, it.content.orEmpty()) }
        val title = sessionStore.title(sessionKey, if (sessionKey == "main") "主对话" else sessionKey)
        noRecall = noRecallMap().optBoolean(sessionKey, false)
        _ui.update { it.copy(messages = visible, sessionTitle = title, projectName = title) }
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
