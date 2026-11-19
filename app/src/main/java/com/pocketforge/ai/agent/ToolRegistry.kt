package com.pocketforge.ai.agent

import android.content.Context
import com.pocketforge.ai.core.Sandbox
import com.pocketforge.ai.data.AppConfig
import com.pocketforge.ai.data.PlanItem
import com.pocketforge.ai.data.ToolResult
import com.pocketforge.ai.termux.SshClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 工具层：AI 唯一能“动手机”的入口。
 * 所有路径先过 Sandbox 两道闸（可读范围 / 可写范围），系统分区与其他应用数据直接拒绝。
 */
class ToolRegistry(
    @Suppress("UNUSED_PARAMETER") private val context: Context,
    private val sandbox: Sandbox,
    private val skills: SkillLibrary,
    private val configProvider: () -> AppConfig,
    private val isUnlocked: () -> Boolean,
    private val onIndexRead: () -> Unit,
    private val onSkillRead: (String) -> Unit,
    private val onPlan: (List<PlanItem>) -> Unit,
    private val onProjectOpened: (String) -> Unit,
    private val onHostKeyLearned: (String) -> Unit = {},
    private val updateCheck: () -> String = { "更新模块未接入" },
    private val updateInstall: () -> String = { "更新模块未接入" },
) {

    private val locks = DocLocks { runCatching { sandbox.workspace() }.getOrNull() }

    companion object {
        val LABELS = mapOf(
            "skills_index" to "读取技能总索引",
            "skill_read" to "读取技能说明",
            "fs_list" to "列目录",
            "fs_read" to "读文件",
            "fs_write" to "写文件",
            "fs_mkdir" to "建目录",
            "fs_move" to "移动/改名",
            "fs_delete" to "删除（默认进回收站）",
            "fs_search" to "全文搜索",
            "project_new" to "创建项目骨架",
            "plan_set" to "更新任务计划",
            "config_info" to "查看运行配置",
            "skill_install" to "下载并注入技能",
            "doc_lock" to "锁定文档",
            "doc_unlock" to "解锁文档",
            "doc_locks" to "查看锁定清单",
            "termux_status" to "检查 Termux 连接",
            "termux_exec" to "在 Termux 执行命令",
            "fs_search" to "搜索文件/内容",
            "update_check" to "检查新版本",
            "update_install" to "下载并安装新版",
        )

        private val WRITE_TOOLS = setOf(
            "fs_write", "fs_mkdir", "fs_move", "fs_delete", "project_new", "termux_exec"
        )

        fun label(name: String): String = LABELS[name] ?: name
    }

    val schemas: JSONArray = buildSchemas()

    fun dispatch(name: String, argsJson: String): ToolResult {
        if (configProvider().skillsGate && name in WRITE_TOOLS && !isUnlocked()) {
            return ToolResult(
                false,
                "越界拦截：本会话还没读完技能说明。请先调用 skills_index，再调用 skill_read 读取与项目类型匹配的技能（game-dev.md / writing.md / app-dev.md），然后才能新建目录或写文件。",
                "gate=skills_not_loaded",
            )
        }
        return try {
            val args = if (argsJson.isBlank()) JSONObject() else JSONObject(argsJson)
            when (name) {
                "skills_index" -> skillsIndex()
                "skill_read" -> skillRead(args)
                "fs_list" -> fsList(args)
                "fs_read" -> fsRead(args)
                "fs_write" -> fsWrite(args)
                "fs_mkdir" -> fsMkdir(args)
                "fs_move" -> fsMove(args)
                "fs_delete" -> fsDelete(args)
                "fs_search" -> fsSearch(args)
                "project_new" -> projectNew(args)
                "plan_set" -> planSet(args)
                "config_info" -> configInfo()
                "skill_install" -> skillInstall(args)
                "doc_lock" -> docLock(args)
                "doc_unlock" -> docUnlock(args)
                "doc_locks" -> docLocks()
                "termux_status" -> termuxStatus()
                "termux_exec" -> termuxExec(args)
                "update_check" -> ToolResult(true, updateCheck())
                "update_install" -> ToolResult(true, updateInstall())
                else -> ToolResult(false, "未知工具：$name")
            }
        } catch (t: Throwable) {
            ToolResult(false, "执行失败：${t.message ?: t.javaClass.simpleName}")
        }
    }

    // ---------------- 技能 ----------------

    private fun skillsIndex(): ToolResult {
        val workspace = sandbox.workspace()
        skills.installTo(workspace)
        val dir = skills.skillsDir(workspace)
        onIndexRead()
        val payload = buildString {
            appendLine(skills.index(workspace))
            appendLine()
            appendLine(skills.catalog(workspace))
            appendLine()
            appendLine("工作区：${workspace.absolutePath}")
            appendLine("技能副本：${dir.absolutePath}")
            if (skills.isForced(workspace)) {
                appendLine("强制注入：已开启（仅本工作区生效，缺失技能不会用内置基线顶替）")
            }
        }
        return ToolResult(true, "已读取技能总索引（${workspace.absolutePath}/skills）", payload)
    }

    private fun skillRead(args: JSONObject): ToolResult {
        val name = skills.sanitizeName(args.optString("name").trim())
        require(name.isNotEmpty()) { "缺少参数 name" }
        val workspace = sandbox.workspace()
        val local = File(skills.skillsDir(workspace), name)
        require(name in SkillLibrary.ENTRIES.map { it.first } || local.isFile) {
            "技能文件不存在：$name（可先用 skill_install 从远端注入，或读取 skills_index 查看清单）"
        }
        val content = skills.read(name, workspace)
        require(content.isNotBlank()) { "技能文件为空：$name" }
        onSkillRead(name)
        return ToolResult(true, "已读取技能：$name", content)
    }

    // ---------------- 文件系统 ----------------

    private fun fsList(args: JSONObject): ToolResult {
        val raw = args.optString("path", "").trim()
        val depth = args.optInt("depth", 1).coerceIn(1, 3)
        val start = when {
            raw.isEmpty() || raw == "." || raw == "/" -> sandbox.workspace()
            else -> sandbox.resolveRead(raw)
        }
        if (start.isFile) {
            return ToolResult(true, "文件：${start.name}", "size=${start.length()} bytes")
        }
        val out = StringBuilder()
        var count = 0
        fun walk(dir: File, level: Int) {
            val children = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                ?: return
            for (child in children) {
                if (count >= Sandbox.MAX_LIST_ENTRIES) return
                count++
                val indent = "  ".repeat(level)
                val suffix = if (child.isDirectory) "/" else "  (${humanSize(child.length())})"
                out.append(indent).append(if (child.isDirectory) "[D]" else "[F]").append(' ')
                    .append(child.name).append(suffix).append('\n')
                if (child.isDirectory && level + 1 < depth) walk(child, level + 1)
            }
        }
        walk(start, 0)
        if (count == 0) out.append("（空目录）\n")
        if (count >= Sandbox.MAX_LIST_ENTRIES) out.append("…已达到 ${Sandbox.MAX_LIST_ENTRIES} 条上限，请缩小范围\n")
        return ToolResult(true, "已列出 ${sandbox.relative(start)}（$count 项）", out.toString())
    }

    private fun fsRead(args: JSONObject): ToolResult {
        val path = args.optString("path").trim()
        require(path.isNotEmpty()) { "缺少参数 path" }
        val file = sandbox.resolveRead(path)
        require(file.isFile) { "不是文件：$path" }
        val limit = args.optInt("maxBytes", Sandbox.MAX_READ_BYTES).coerceIn(1024, Sandbox.MAX_READ_BYTES)
        val total = file.length()
        val bytes = file.inputStream().use { input ->
            val buffer = ByteArray(minOf(total, limit.toLong()).toInt())
            var read = 0
            while (read < buffer.size) {
                val n = input.read(buffer, read, buffer.size - read)
                if (n <= 0) break
                read += n
            }
            if (read == buffer.size) buffer else buffer.copyOf(read)
        }
        if (bytes.any { it == 0.toByte() }) {
            return ToolResult(true, "二进制文件：${file.name}", "size=$total bytes，已跳过解码")
        }
        var text = String(bytes, Charsets.UTF_8)
        if (total > limit) text += "\n\n…（已截断，原始大小 ${humanSize(total)}）"
        return ToolResult(true, "已读取 ${sandbox.relative(file)}（${humanSize(total)}）", text)
    }

    private fun fsWrite(args: JSONObject): ToolResult {
        val path = args.optString("path").trim()
        require(path.isNotEmpty()) { "缺少参数 path" }
        val content = args.optString("content")
        val append = args.optBoolean("append", false)
        val bytes = content.toByteArray(Charsets.UTF_8)
        require(bytes.size <= Sandbox.MAX_WRITE_BYTES) {
            "单次写入上限 4MB，当前 ${humanSize(bytes.size.toLong())}；长文件请分卷 append"
        }
        val file = sandbox.resolveWrite(path)
        file.parentFile?.mkdirs()
        require(file.absolutePath != sandbox.workspace().absolutePath) { "不能把工作区根目录当文件写" }
        val rel = sandbox.relative(file)
        val override = args.optBoolean("override", false)
        locks.check(rel, content, override)?.let { reason ->
            return ToolResult(false, "写入被文档锁拦截：$reason")
        }
        if (append) file.appendText(content) else file.writeText(content)
        val lines = content.count { it == '\n' } + 1
        return ToolResult(
            true,
            "已写入 $rel",
            "bytes=${bytes.size}, lines=$lines, mode=${if (append) "append" else "overwrite"}"
        )
    }

    private fun fsMkdir(args: JSONObject): ToolResult {
        val path = args.optString("path").trim()
        require(path.isNotEmpty()) { "缺少参数 path" }
        val dir = sandbox.resolveWrite(path)
        val created = dir.mkdirs() || dir.isDirectory
        require(created) { "建目录失败：$path" }
        return ToolResult(true, "已就绪目录 ${sandbox.relative(dir)}", dir.absolutePath)
    }

    private fun fsMove(args: JSONObject): ToolResult {
        val from = args.optString("from").trim()
        val to = args.optString("to").trim()
        require(from.isNotEmpty() && to.isNotEmpty()) { "需要 from 与 to" }
        val src = sandbox.resolveWrite(from)
        require(src.exists()) { "源不存在：$from" }
        val dst = sandbox.resolveWrite(to)
        dst.parentFile?.mkdirs()
        require(!dst.exists()) { "目标已存在：$to" }
        val ok = src.renameTo(dst)
        if (!ok) {
            if (src.isDirectory) src.copyRecursively(dst, overwrite = false) else src.copyTo(dst)
            src.deleteRecursively()
        }
        return ToolResult(true, "已移动 ${sandbox.relative(src)} → ${sandbox.relative(dst)}")
    }

    private fun fsDelete(args: JSONObject): ToolResult {
        val path = args.optString("path").trim()
        require(path.isNotEmpty()) { "缺少参数 path" }
        val target = sandbox.resolveWrite(path)
        require(target.exists()) { "目标不存在：$path" }
        require(target.absolutePath != sandbox.workspace().absolutePath) { "拒绝删除工作区根目录" }
        val toTrash = args.optBoolean("toTrash", true)
        if (toTrash) {
            val trash = File(sandbox.workspace(), ".trash")
            trash.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val dst = File(trash, "${stamp}_${target.name}")
            if (target.renameTo(dst)) {
                return ToolResult(true, "已移入回收站：.trash/${dst.name}")
            }
        }
        val removed = if (target.isDirectory) target.deleteRecursively() else target.delete()
        require(removed) { "删除失败：$path" }
        return ToolResult(true, "已删除 ${sandbox.relative(target)}")
    }

    private fun fsSearch(args: JSONObject): ToolResult {
        val query = args.optString("query").trim()
        require(query.isNotEmpty()) { "缺少参数 query" }
        val content = args.optBoolean("content", true)
        val scope = args.optString("scope", "workspace").lowercase()
        val pathRaw = args.optString("path", "").trim()
        val maxHits = args.optInt("maxHits", 40).coerceIn(1, 200)
        val root = when {
            pathRaw.isNotEmpty() && pathRaw != "." -> sandbox.resolveRead(pathRaw)
            scope == "root" -> {
                if (!sandbox.hasAllFilesAccess()) {
                    return ToolResult(false, "没有「所有文件访问」权限，只能搜工作区")
                }
                File("/storage/emulated/0")
            }
            else -> sandbox.workspace()
        }
        if (!root.exists()) return ToolResult(false, "路径不存在：${root.absolutePath}")

        val hits = StringBuilder()
        var hitCount = 0
        var scanned = 0

        fun walk(file: File, depth: Int) {
            if (hitCount >= maxHits || scanned > 6000 || depth > 8) return
            if (file.isDirectory) {
                if (file.name in listOf(".git", "build", "node_modules", ".trash", "Android")) return
                file.listFiles()?.forEach { walk(it, depth + 1) }
                return
            }
            if (file.length() == 0L) return
            scanned++
            val nameHit = file.name.contains(query, ignoreCase = true)
            if (!content) {
                if (nameHit && hitCount < maxHits) {
                    hitCount++
                    hits.append(sandbox.relative(file)).append('\n')
                }
                return
            }
            if (file.length() > 2_000_000L) return
            val ext = file.extension.lowercase()
            if (ext in listOf("png","jpg","jpeg","webp","gif","mp3","mp4","wav","zip","apk","jar","ogg","ttf","db")) return
            val lines = runCatching { file.readLines() }.getOrNull() ?: return
            if (nameHit && hitCount < maxHits) {
                hitCount++
                hits.append(sandbox.relative(file)).append("  [文件名命中]\n")
            }
            lines.forEachIndexed { index, line ->
                if (hitCount < maxHits && line.contains(query, ignoreCase = true)) {
                    hitCount++
                    hits.append(sandbox.relative(file)).append(':').append(index + 1).append(' ')
                        .append(line.trim().take(160)).append('\n')
                }
            }
        }
        walk(root, 0)
        val summary = if (hitCount == 0) "未找到「$query」（扫描 $scanned 个文件）"
        else "命中 $hitCount 处（扫描 $scanned 个文件）"
        return ToolResult(true, summary, hits.toString())
    }

    // ---------------- 项目与计划 ----------------

    private fun projectNew(args: JSONObject): ToolResult {
        val name = args.optString("name").trim()
        require(name.isNotEmpty()) { "缺少参数 name" }
        val type = args.optString("type", "game").lowercase()
        val engine = args.optString("engine", "").trim()
        val slug = slugify(name)
        val dir = sandbox.resolveWrite(File(sandbox.projectsDir(), slug).absolutePath)
        val existed = File(dir, ".pocketforge/project.json").exists()
        dir.mkdirs()

        val skillName = skills.skillFor(type)
        val skillText = skills.read(skillName, sandbox.workspace()).ifBlank { skills.index(sandbox.workspace()) }

        val created = mutableListOf<String>()
        fun touch(rel: String, content: String) {
            val f = File(dir, rel)
            if (!f.exists()) {
                f.parentFile?.mkdirs()
                f.writeText(content)
                created.add(rel)
            }
        }

        when (type) {
            "writing", "book", "write", "写作" -> {
                listOf("chapters", "characters", "worldbuilding", "notes", "export").forEach { folder ->
                    File(dir, folder).mkdirs(); created.add("$folder/")
                }
                touch("outline.md", "# 《$name》大纲\n\n## 核心设定\n- 题材：\n- 主角：\n- 主线冲突：\n\n## 分卷结构\n1. \n2. \n")
                touch("style-guide.md", "# 文风约定\n\n- 视角：\n- 语体：\n- 禁用词（防 AI 味）：\n")
            }
            "app", "web", "应用", "网页" -> {
                listOf("src/main", "src/res", "docs", "build").forEach { folder ->
                    File(dir, folder).mkdirs(); created.add("$folder/")
                }
                touch("docs/architecture.md", "# 架构\n\n## 页面\n## 数据\n## 接口\n")
            }
            else -> {
                listOf("src", "assets/images", "assets/audio", "assets/data", "design", "docs", "build").forEach { folder ->
                    File(dir, folder).mkdirs(); created.add("$folder/")
                }
                touch("design/gdd.md", "# 游戏设计文档\n\n## 核心循环\n## 操作与反馈\n## 关卡/数值\n## 美术与音效\n")
                touch("design/tech.md", "# 技术方案\n\n- 引擎/框架：${engine.ifEmpty { "待定" }}\n- 目标平台：Android\n- 存档格式：\n")
            }
        }

        skeletons(type, engine).forEach { (rel, content) -> touch(rel, content) }

        touch(
            "README.md",
            """
# $name

- 类型：$type
- 引擎/框架：${engine.ifEmpty { "待定" }}
- 创建时间：${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())}
- 工作区：${dir.absolutePath}

## 快速开始
1. 阅读 `AGENTS.md` 与 `skills/$skillName`
2. 打开 `PROJECT.md` 确认交付定义
3. 按 `plan/todo.md` 分步推进
            """.trimIndent()
        )
        touch(
            "PROJECT.md",
            """
# 项目卡：$name

| 项 | 值 |
| --- | --- |
| 类型 | $type |
| 引擎 | ${engine.ifEmpty { "待定" }} |
| 目录 | ${dir.absolutePath} |
| 必读技能 | $skillName |

## 交付定义
- 可运行/可阅读的完整产物，而不是片段
- 每轮结束更新 `plan/todo.md`
- 资源与源码分离：`src/` 与 `assets/`

## 验收清单
- [ ] 目录结构与技能规范一致
- [ ] README 能在 1 分钟内看懂
- [ ] 无凭空引用的外部依赖
- [ ] 关键文件不超过 2MB
            """.trimIndent()
        )
        touch(
            "AGENTS.md",
            """
# 给 AI 的开工须知（$name）

## 每次动手前
1. 读 `skills/SKILLS.md`
2. 读 `skills/$skillName`
3. 读 `skills/boundaries.md`
4. 读本文件与 `PROJECT.md`

## 工作纪律
- 只在本项目目录内写入；读取可覆盖公共存储根目录
- 一次只推进一个可验证的小步骤，写完后自检
- 删除一律先入 `.trash`
- 不编造不存在的文件、命令或 API

## 本轮结束前
- 更新 `plan/todo.md`
- 在回复里列出「改了哪些文件 + 下一步」
            """.trimIndent()
        )
        touch("plan/todo.md", "# 待办\n\n- [ ] 确认需求与交付定义\n- [ ] 搭好目录骨架\n- [ ] 产出第一版可运行/可读内容\n- [ ] 自检并记录\n")

        File(dir, ".pocketforge").mkdirs()
        File(dir, ".pocketforge/project.json").writeText(
            JSONObject()
                .put("name", name)
                .put("slug", slug)
                .put("type", type)
                .put("engine", engine)
                .put("skill", skillName)
                .put("createdAt", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                .toString()
        )
        skills.installTo(sandbox.workspace())
        if (type == "writing" || type == "book" || type == "write" || type == "写作") {
            listOf("style-guide.md", "world.md").forEach { rel ->
                val f = File(dir, rel)
                if (f.isFile) {
                    locks.lock(
                        sandbox.relative(f), f.readText(), emptyList(), false,
                        "写作项目基线：文风/世界观已定稿，AI 不得擅自改写"
                    )
                }
            }
        }
        onProjectOpened(name)

        val tip = buildString {
            if (existed) append("\n\n项目已存在：只补齐缺失文件，没覆盖你已有的内容。")
            if (skillText.isNotBlank()) append("\n已匹配技能 $skillName。")
        }
        val head = when {
            existed && created.isEmpty() -> "项目 $name 已经完整，无需新建：${dir.absolutePath}"
            existed -> "项目 $name 已补齐 ${created.size} 项：${dir.absolutePath}"
            else -> "项目 $name 已就绪：${dir.absolutePath}（新建 ${created.size} 项）"
        }
        return ToolResult(true, head + tip, created.joinToString("\n"))
    }

    private fun planSet(args: JSONObject): ToolResult {
        val items = args.optJSONArray("items") ?: JSONArray()
        val list = (0 until items.length()).mapNotNull { i ->
            val text = items.optString(i, "").trim()
            if (text.isEmpty()) null else PlanItem(text)
        }
        onPlan(list)
        return ToolResult(true, "计划已更新（${list.size} 步）", list.joinToString("\n") { "- ${it.text}" })
    }

    private fun configInfo(): ToolResult {
        val cfg: AppConfig = configProvider()
        val payload = buildString {
            appendLine("baseUrl: ${cfg.baseUrl}")
            appendLine("model: ${cfg.model}")
            appendLine("stream: ${cfg.stream}")
            appendLine("apiKey: ${cfg.maskedKey()}")
            appendLine("streamCipher: ${if (cfg.streamCipherKey.isNotEmpty()) "AES-GCM 已启用" else "未启用"}")
            appendLine("workspace: ${sandbox.workspace().absolutePath}")
            appendLine("skillsGate: ${cfg.skillsGate}")
            appendLine("allFilesAccess: ${sandbox.hasAllFilesAccess()}")
            appendLine("skillSource: ${cfg.skillRepoUrl.ifBlank { "内置" }}")
            appendLine("forceInject: ${skills.isForced(sandbox.workspace())}")
            appendLine("termux: ${cfg.termuxUser.ifBlank { "未配置" }}@${cfg.termuxHost}:${cfg.termuxPort} (${cfg.termuxAuth}) allowShell=${cfg.allowShell}")
            appendLine("termuxHostKey: ${cfg.termuxHostKey.ifBlank { "未记录" }}")
        }
        return ToolResult(true, "运行配置（已脱敏）", payload)
    }

    // ---------------- 项目骨架 ----------------

    private fun skeletons(type: String, engine: String): Map<String, String> = when (type) {
        "writing", "book", "write", "写作" -> writingSkeleton()
        "app", "web", "应用", "网页" -> appSkeleton()
        else -> gameSkeleton(engine)
    }

    private fun gameSkeleton(engine: String): Map<String, String> = mapOf(
        "src/Main.kt" to """
package game

import engine.GameLoop
import engine.World
import game.Scene

// 入口：把引擎、世界、场景串起来
fun main() {
    val world = World()
    val loop = GameLoop(world, targetFps = 60)
    world.switch(Scene.Title())
    loop.run()
}
""".trimIndent(),

        "src/engine/GameLoop.kt" to """
package engine

/**
 * 固定步长主循环：逻辑 60Hz，渲染跟随刷新率。
 * 不用 sleep 卡帧，靠累计时间对齐，避免漂移。
 */
class GameLoop(private val world: World, private val targetFps: Int = 60) {
    private var running = false

    fun run() {
        running = true
        val step = 1000L / targetFps
        var last = System.currentTimeMillis()
        var acc = 0L

        while (running) {
            val now = System.currentTimeMillis()
            val delta = (now - last).coerceAtMost(250L)
            last = now
            acc += delta

            while (acc >= step) {
                world.update(step / 1000f)
                acc -= step
            }
            world.render(acc.toFloat() / step)
        }
    }

    fun stop() { running = false }
}
""".trimIndent(),

        "src/engine/World.kt" to """
package engine

/** 世界：持有实体表与当前场景，负责调度更新与渲染 */
class World {
    private val entities = mutableListOf<Entity>()
    private var scene: Scene = Scene.Empty()

    fun spawn(e: Entity): Entity { entities.add(e); return e }
    fun despawn(e: Entity) { entities.remove(e) }

    fun switch(next: Scene) {
        scene.onExit()
        scene = next
        scene.onEnter(this)
    }

    fun update(dt: Float) {
        scene.update(dt)
        entities.forEach { it.update(dt) }
    }

    fun render(alpha: Float) {
        scene.render(alpha)
        entities.forEach { it.render(alpha) }
    }
}
""".trimIndent(),

        "src/engine/Entity.kt" to """
package engine

/** 实体：用 id 标识，行为由组件或子类决定 */
open class Entity(val id: Long) {
    var x: Float = 0f
    var y: Float = 0f
    var active: Boolean = true

    open fun update(dt: Float) = Unit
    open fun render(alpha: Float) = Unit
}
""".trimIndent(),

        "src/engine/Scene.kt" to """
package engine

/** 场景基类：进入/退出/更新/渲染四个钩子 */
open class Scene {
    open fun onEnter(world: World) = Unit
    open fun onExit() = Unit
    open fun update(dt: Float) = Unit
    open fun render(alpha: Float) = Unit

    class Empty : Scene()
}
""".trimIndent(),

        "src/engine/Input.kt" to """
package engine

/** 输入快照：只记录状态与边沿，渲染层直接查 */
class Input {
    private val held = HashSet<String>()
    private val pressedOnce = HashSet<String>()

    fun down(key: String) { held.add(key); pressedOnce.add(key) }
    fun up(key: String) { held.remove(key) }
    fun isDown(key: String) = key in held
    fun justPressed(key: String) = key in pressedOnce
    fun endFrame() { pressedOnce.clear() }
}
""".trimIndent(),

        "src/engine/Renderer.kt" to """
package engine

/**
 * 渲染接口：Android 侧用 Canvas 实现，桌面/测试用空实现。
 * 引擎不直接依赖具体绘图 API，方便换皮。
 */
interface Renderer {
    fun clear(color: Int)
    fun drawRect(x: Float, y: Float, w: Float, h: Float, color: Int)
    fun drawText(text: String, x: Float, y: Float, size: Float, color: Int)
    fun present()
}
""".trimIndent(),

        "src/engine/Assets.kt" to """
package engine

import java.io.File

/** 资源加载：统一走 assets/，避免散落到源码里 */
class Assets(private val root: File) {
    fun bytes(rel: String): ByteArray = File(root, rel).readBytes()
    fun text(rel: String): String = File(root, rel).readText()
    fun exists(rel: String): Boolean = File(root, rel).isFile
}
""".trimIndent(),

        "src/system/Config.kt" to """
package system

import java.io.File

/**
 * 运行时配置：只读加载 + 显式保存，避免到处改全局状态。
 * 敏感字段（密钥）不要放这里，交给 Keystore 加密存储。
 */
class Config(private val file: File) {
    private val map = LinkedHashMap<String, String>()

    fun load() {
        if (!file.isFile) return
        file.readLines().forEach { line ->
            val i = line.indexOf('=')
            if (i > 0) map[line.substring(0, i).trim()] = line.substring(i + 1).trim()
        }
    }

    operator fun get(key: String): String? = map[key]
    operator fun set(key: String, value: String) { map[key] = value }

    fun save() {
        file.parentFile?.mkdirs()
        file.writeText(map.entries.joinToString("
") { (k, v) -> "" + k + "=" + v })
    }
}
""".trimIndent(),

        "src/system/SaveSystem.kt" to """
package system

import java.io.File

/**
 * 存档：JSON 落盘 + 临时文件原子替换，防止断电写坏。
 * 版本号必须写进去，读档时做迁移。
 */
class SaveSystem(private val dir: File) {
    fun save(slot: String, json: String) {
        dir.mkdirs()
        val tmp = File(dir, slot + ".tmp")
        tmp.writeText(json)
        val target = File(dir, slot + ".json")
        if (target.exists()) target.delete()
        tmp.renameTo(target)
    }

    fun load(slot: String): String? {
        val f = File(dir, slot + ".json")
        return if (f.isFile) f.readText() else null
    }
}
""".trimIndent(),

        "src/system/Logger.kt" to """
package system

import java.io.File

/** 简易日志：按天切文件，超过 2MB 丢弃旧行，避免把存储写满 */
class Logger(private val dir: File) {
    fun info(tag: String, message: String) = write("I", tag, message)
    fun warn(tag: String, message: String) = write("W", tag, message)
    fun error(tag: String, message: String) = write("E", tag, message)

    private fun write(level: String, tag: String, message: String) {
        runCatching {
            dir.mkdirs()
            val f = File(dir, "app.log")
            if (f.length() > 2_000_000L) f.writeText("")
            f.appendText(level + "/" + tag + ": " + message + "
")
        }
    }
}
""".trimIndent(),

        "src/game/Scene.kt" to """
package game

import engine.Scene

/** 标题场景：后续关卡场景照这个模板加 */
class Title : Scene()
""".trimIndent(),

        "docs/data-model.md" to """
# 数据模型

| 对象 | 字段 | 类型 | 说明 |
| --- | --- | --- | --- |
| Player | hp / speed / x / y | Int / Float | 主角状态 |
| Item | id / name / effect | String | 道具表，放 assets/data |
| Save | version / slot / payload | Int / String | 存档必须有版本号 |

## 约定
- 数值一律用 Float，避免整数除法丢精度。
- 表驱动：平衡性数据放 assets/data/*.json，不写死在代码里。
""".trimIndent(),

        "docs/tech.md" to """
# 技术方案

- 引擎：${engine.ifEmpty { "自研轻量 2D 引擎（src/engine）" }}
- 主循环：GameLoop 固定步长 60Hz，渲染插值
- 渲染：Renderer 接口抽象，Android 侧 Canvas 实现
- 输入：Input 快照（按住 + 单帧边沿）
- 存档：SaveSystem 原子写 + 版本迁移
- 配置：assets/config/*.properties，密钥不进配置文件

## 不做什么
- 不引入重型引擎依赖，保证纯代码可读可改
- 不承诺在本 App 内编译 APK；出包走 GitHub Actions 或 Termux
""".trimIndent(),
    )

    private fun writingSkeleton(): Map<String, String> = mapOf(
        "chapters/000-模板.md" to """
# 第 1 章 章节标题

## 场景一
- 时间：
- 地点：
- 在场人物：

正文从这里开始。一段只写一件事，动作优先于形容。

## 场景二
（转场要交代因果，不要硬切）

---
本章钩子：________
""".trimIndent(),

        "world.md" to """
# 世界观基线

## 一句话设定
（用一句话说清世界规则，读者看完能复述）

## 硬规则
1. 物理/超自然规则：
2. 技术上限：
3. 社会结构：

## 地理
- 主舞台：
- 边缘地带：

## 禁忌
- 不做无铺垫的能力升级
- 不改动本章已锁定的硬规则（改动需先解锁）
""".trimIndent(),

        "cast/cast.md" to """
# 人物表

| 角色 | 身份 | 首次出场 | 角色卡 |
| --- | --- | --- | --- |
| 主角 | | 第 1 章 | characters/主角.md |
| 对手 | | 第 3 章 | characters/对手.md |

## 关系网
- 主角 ↔ 对手：
- 主角 ↔ 配角：
""".trimIndent(),

        "continuity.md" to """
# 伏笔与连续性账本

| 编号 | 伏笔/设定 | 埋设章节 | 回收章节 | 状态 |
| --- | --- | --- | --- | --- |
| F01 | | 第 2 章 | 待定 | 未回收 |
| F02 | | 第 5 章 | 第 9 章 | 已回收 |

## 时间线
- 第 1 章：第 1 天
- 第 2 章：第 1 天夜

> 每写完一章回来登记，防止写到后面自相矛盾。
""".trimIndent(),

        "style-guide.md" to """
# 文风约定（已锁定）

- 视角：第三人称限知
- 语体：短句为主，少用四字成语堆叠
- 场景描写：动作 + 一个感官细节，不超过 3 行
- 禁用词（防 AI 味）：「不禁」「彷佛」「仿佛」「这一切」「命运的齿轮」
- 对话：一人一句，不加「他说道」以外的修饰

## 节奏
- 每 800 字一个信息增量
- 章末必须有钩子

> 本文件默认被文档锁保护。要改文风，先 doc_unlock，改完再 doc_lock。
""".trimIndent(),

        "docs/publishing.md" to """
# 出版与导出

## 导出顺序
1. 章节合并 → export/full.md
2. 目录生成（章号 + 章名）
3. 转 Word/EPUB 时去掉 Markdown 标记

## 校验
- 章节编号连续，无跳号
- 人物名一致（对照 cast.md）
- 无占位符残留（TODO、____）
""".trimIndent(),

        "plan/todo.md" to """
# 写作计划

- [ ] 世界观与人物表定稿（锁 world.md / style-guide.md）
- [ ] 大纲分卷
- [ ] 第 1 章初稿
- [ ] 第 1 章改稿（去 AI 味）
- [ ] 伏笔账本登记
""".trimIndent(),
    )

    private fun appSkeleton(): Map<String, String> = mapOf(
        "src/main/App.kt" to """
package app

/** 应用入口：只做装配，不写业务 */
fun main() {
    val config = system.Config(java.io.File("config/app.properties")).apply { load() }
    val router = Router()
    router.register("/home", ::home)
    router.go("/home")
}

private fun home() {
    // TODO: 首页渲染
}
""".trimIndent(),

        "src/main/Router.kt" to """
package app

/** 路由：路径 → 处理函数，避免页面互相直接调用 */
class Router {
    private val routes = LinkedHashMap<String, () -> Unit>()

    fun register(path: String, handler: () -> Unit) { routes[path] = handler }
    fun go(path: String) { routes[path]?.invoke() ?: error("未注册路由：" + path) }
}
""".trimIndent(),

        "src/system/Storage.kt" to """
package system

import java.io.File

/** 文件读写：统一 UTF-8，写前建目录，写后校验长度 */
class Storage(private val root: File) {
    fun read(rel: String): String = File(root, rel).readText()
    fun write(rel: String, content: String): Int {
        val f = File(root, rel)
        f.parentFile?.mkdirs()
        f.writeText(content)
        return content.length
    }
}
""".trimIndent(),

        "docs/architecture.md" to """
# 架构

## 分层
| 层 | 职责 | 目录 |
| --- | --- | --- |
| 入口 | 装配依赖、启动 | src/main/App.kt |
| 路由 | 路径分发 | src/main/Router.kt |
| 系统 | 存储/配置/日志 | src/system/ |
| 业务 | 页面与数据 | src/feature/ |

## 约束
- 页面不直接 new 依赖，统一从装配处传入
- 所有 IO 走 system.Storage，不散落 File 调用
""".trimIndent(),
    )

    // ---------------- 文档锁 ----------------

    private fun docLock(args: JSONObject): ToolResult {
        val path = args.optString("path").trim()
        require(path.isNotEmpty()) { "缺少参数 path" }
        val file = sandbox.resolveWrite(path)
        require(file.isFile) { "文件不存在：${sandbox.relative(file)}（先写内容再锁）" }
        val rel = sandbox.relative(file)
        val sections = args.optJSONArray("sections")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it, "").takeIf { s -> s.isNotBlank() } }
        } ?: emptyList()
        val hard = args.optBoolean("hard", false)
        val note = args.optString("note", "")
        val message = locks.lock(rel, file.readText(), sections, hard, note)
        return ToolResult(true, message, "path=$rel, hard=$hard, sections=${sections.joinToString()}")
    }

    private fun docUnlock(args: JSONObject): ToolResult {
        val path = args.optString("path").trim()
        require(path.isNotEmpty()) { "缺少参数 path" }
        val file = sandbox.resolveWrite(path)
        val unlocked = locks.unlock(sandbox.relative(file))
        return ToolResult(
            unlocked,
            if (unlocked) "已解锁 ${sandbox.relative(file)}" else "这个文件本来就没上锁",
        )
    }

    private fun docLocks(): ToolResult {
        val entries = locks.list()
        if (entries.isEmpty()) return ToolResult(true, "当前工作区没有锁定文档", "（空）")
        return ToolResult(
            true,
            "共 ${entries.size} 个锁定文档",
            entries.joinToString("\n") { e ->
                val scope = if (e.hard) "硬锁" else if (e.sections.isEmpty()) "整篇保护" else "章节：" + e.sections.joinToString(" / ")
                "- ${e.path} · $scope${if (e.note.isNotBlank()) " · ${e.note}" else ""}"
            }
        )
    }

    // ---------------- 技能注入 ----------------

    private fun skillInstall(args: JSONObject): ToolResult {
        val cfg = configProvider()
        val url = args.optString("url", "").trim().ifBlank { cfg.skillRepoUrl }
        val token = args.optString("token", "").ifBlank { cfg.skillRepoToken }
        val force = args.optBoolean("force", false)
        require(url.isNotBlank()) { "未提供技能地址，也没有配置默认技能源" }
        val workspace = sandbox.workspace()
        val result = skills.remoteInstall(url, token, workspace, force)
        if (!result.ok) return ToolResult(false, result.message)
        onSkillRead("SKILLS.md")
        return ToolResult(
            true,
            result.message,
            buildString {
                appendLine("来源：$url")
                appendLine("落盘：${skills.skillsDir(workspace).absolutePath}")
                appendLine("强制注入：${if (skills.isForced(workspace)) "开" else "关"}")
                appendLine()
                appendLine(skills.catalog(workspace))
            }.toString()
        )
    }

    // ---------------- Termux ----------------

    private fun termuxStatus(): ToolResult {
        val cfg = configProvider()
        if (cfg.termuxUser.isBlank()) {
            return ToolResult(
                false,
                "Termux 还没配：设置页填主机、端口、用户名（Termux 内 whoami）、密码或私钥。",
            )
        }
        val outcome = SshClient.test(cfg)
        if (outcome.hostKeyLearned && outcome.fingerprint.isNotBlank()) onHostKeyLearned(outcome.fingerprint)
        return ToolResult(
            outcome.ok,
            if (outcome.ok) "Termux 连接正常（allowShell=${cfg.allowShell}）" else outcome.message,
            buildString {
                appendLine("host: ${cfg.termuxHost}:${cfg.termuxPort}")
                appendLine("user: ${cfg.termuxUser}")
                appendLine("auth: ${cfg.termuxAuth}")
                appendLine("allowShell: ${cfg.allowShell}")
                appendLine("hostKey: ${outcome.fingerprint.ifBlank { cfg.termuxHostKey.ifBlank { "未记录" } }}")
                appendLine()
                appendLine(outcome.message)
            }.toString()
        )
    }

    private fun termuxExec(args: JSONObject): ToolResult {
        val cfg = configProvider()
        if (!cfg.allowShell) {
            return ToolResult(
                false,
                "设置页的「允许 AI 在 Termux 执行命令」是关闭状态。需要时打开它，我只在 Termux 自己的目录里干活。",
            )
        }
        val command = args.optString("command").trim()
        require(command.isNotEmpty()) { "缺少参数 command" }
        val timeoutSec = args.optInt("timeoutSec", 30).coerceIn(5, 120)
        val outcome = SshClient.exec(cfg, command, timeoutSec * 1000)
        if (outcome.hostKeyLearned && outcome.fingerprint.isNotBlank()) onHostKeyLearned(outcome.fingerprint)
        val body = buildString {
            appendLine("$ ${command}")
            appendLine()
            append(outcome.message)
            if (outcome.exit >= 0) append("\n\nexit=${outcome.exit}")
        }
        return ToolResult(outcome.ok, if (outcome.ok) "Termux 执行完成" else "Termux 执行未成功", body)
    }

    // ---------------- 工具 schema ----------------

    private fun buildSchemas(): JSONArray {
        val arr = JSONArray()
        arr.put(
            fn(
                "skills_index",
                "读取技能总索引 SKILLS.md 与技能清单。任何新项目开工、任何写入前的第一步。",
                JSONObject(),
                emptyList()
            )
        )
        arr.put(
            fn(
                "skill_read",
                "读取具体技能文件（game-dev.md / writing.md / app-dev.md / boundaries.md / runtime-config.md）。",
                JSONObject()
                    .put("name", prop("string", "技能文件名，例如 game-dev.md")),
                listOf("name")
            )
        )
        arr.put(
            fn(
                "fs_list",
                "列出目录内容。默认工作区；path 可指向公共存储根目录（只读）。",
                JSONObject()
                    .put("path", prop("string", "绝对路径或工作区相对路径，留空=工作区"))
                    .put("depth", prop("integer", "递归深度 1-3，默认 1")),
                emptyList()
            )
        )
        arr.put(
            fn(
                "fs_read",
                "读取文本文件内容（默认最多 512KB，二进制自动识别并跳过）。",
                JSONObject()
                    .put("path", prop("string", "文件路径"))
                    .put("maxBytes", prop("integer", "最大读取字节数"))
                    ,
                listOf("path")
            )
        )
        arr.put(
            fn(
                "fs_write",
                "写入文本文件（自动创建父目录）。仅允许写工作区内，单次 ≤2MB。",
                JSONObject()
                    .put("path", prop("string", "目标文件路径（工作区内）"))
                    .put("content", prop("string", "完整文本内容"))
                    .put("append", prop("boolean", "是否追加，默认 false（覆盖）")),
                listOf("path", "content")
            )
        )
        arr.put(
            fn(
                "fs_mkdir",
                "创建目录（含多级父目录）。",
                JSONObject().put("path", prop("string", "目录路径（工作区内）")),
                listOf("path")
            )
        )
        arr.put(
            fn(
                "fs_move",
                "移动或重命名文件/目录。",
                JSONObject()
                    .put("from", prop("string", "源路径"))
                    .put("to", prop("string", "目标路径")),
                listOf("from", "to")
            )
        )
        arr.put(
            fn(
                "fs_delete",
                "删除文件/目录，默认移入 .trash 回收站而不是真删。",
                JSONObject()
                    .put("path", prop("string", "目标路径"))
                    .put("toTrash", prop("boolean", "是否入回收站，默认 true")),
                listOf("path")
            )
        )
        arr.put(
            fn(
                "fs_search",
                "在文本文件中做全文搜索，返回文件:行号:片段。",
                JSONObject()
                    .put("query", prop("string", "搜索关键词"))
                    .put("path", prop("string", "搜索起点，默认工作区"))
                    .put("maxHits", prop("integer", "最大命中数，默认 40")),
                listOf("query")
            )
        )
        arr.put(
            fn(
                "project_new",
                "创建项目骨架（README/PROJECT.md/AGENTS.md/plan 与类型专属目录），并把技能副本铺到工作区。须先读完技能说明。",
                JSONObject()
                    .put("name", prop("string", "项目名"))
                    .put("type", prop("string", "类型：game | writing | app"))
                    .put("engine", prop("string", "引擎/框架，例如 Godot / Unity / 纯 HTML5 / Markdown")),
                listOf("name")
            )
        )
        arr.put(
            fn(
                "plan_set",
                "更新 UI 上的分步计划清单，让用户看到每一步。",
                JSONObject().put(
                    "items",
                    JSONObject().put("type", "array").put("description", "步骤文本数组")
                        .put("items", JSONObject().put("type", "string"))
                ),
                listOf("items")
            )
        )
        arr.put(
            fn(
                "fs_search",
                "在工作区（或公共存储根目录，只读）按文件名或内容搜索。返回路径，内容命中带行号。",
                JSONObject()
                    .put("query", prop("string", "关键词"))
                    .put("content", prop("boolean", "是否搜文件内容，默认 false（只搜文件名）"))
                    .put("scope", prop("string", "workspace 或 root，默认 workspace"))
                    .put("limit", prop("integer", "最多返回条数，默认 40")),
                listOf("query")
            )
        )
        arr.put(fn("update_check", "检查 GitHub Release 是否有新版本（读 updateRepo 配置）。", JSONObject(), emptyList()))
        arr.put(fn("update_install", "下载最新 release APK，校验包名与签名后拉起系统安装器。", JSONObject(), emptyList()))
        arr.put(
            fn(
                "doc_lock",
                "锁定文档（默认整篇保护；给了 sections 就只锁这些章节）。锁定后 AI 写入会被拦截，适合定稿的设定/正文章节。",
                JSONObject()
                    .put("path", prop("string", "相对工作区的文件路径"))
                    .put("sections", prop("array", "要锁定的章节标题列表，可省略"))
                    .put("hard", prop("boolean", "是否硬锁（禁止任何写入），默认 false"))
                    .put("note", prop("string", "锁定原因，便于日后回顾")),
                listOf("path")
            )
        )
        arr.put(
            fn(
                "doc_unlock",
                "解除文档锁。",
                JSONObject().put("path", prop("string", "相对工作区的文件路径")),
                listOf("path")
            )
        )
        arr.put(fn("doc_locks", "列出当前工作区所有锁定文档与锁定范围。", JSONObject(), emptyList()))
        arr.put(
            fn(
                "skill_install",
                "从远端下载技能 Markdown 并注入当前工作区的 skills/ 目录（支持单个 .md 或 skills-index.json 清单）。force=true 时覆盖已有并开启「仅本工作区」的强制注入。",
                JSONObject()
                    .put("url", prop("string", "技能地址；留空则用设置里的默认技能源"))
                    .put("token", prop("string", "私有仓库访问令牌，可留空"))
                    .put("force", prop("boolean", "是否覆盖并开启强制注入，默认 false")),
                emptyList()
            )
        )
        arr.put(
            fn("termux_status", "检查 Termux 的 SSH 是否连通（含主机指纹 TOFU 记录）。", JSONObject(), emptyList())
        )
        arr.put(
            fn(
                "termux_exec",
                "在 Termux 里执行一条 shell 命令（需在设置里打开 allowShell）。破坏性命令会被拦截，默认 30s 超时。",
                JSONObject()
                    .put("command", prop("string", "要执行的命令，例如 pkg list-installed | head"))
                    .put("timeoutSec", prop("integer", "超时秒数 5-120，默认 30")),
                listOf("command")
            )
        )
        arr.put(
            fn("config_info", "查看当前 API/工作区/加密状态（已脱敏，不含密钥原文）。", JSONObject(), emptyList())
        )
        return arr
    }

    private fun fn(name: String, desc: String, props: JSONObject, required: List<String>): JSONObject {
        val requiredArr = JSONArray()
        required.forEach { requiredArr.put(it) }
        return JSONObject().put("type", "function").put(
            "function",
            JSONObject()
                .put("name", name)
                .put("description", desc)
                .put(
                    "parameters",
                    JSONObject()
                        .put("type", "object")
                        .put("properties", props)
                        .put("required", requiredArr)
                )
        )
    }

    private fun prop(type: String, desc: String): JSONObject =
        JSONObject().put("type", type).put("description", desc)

    private fun humanSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }

    private fun slugify(name: String): String {
        val cleaned = name.trim().replace(Regex("[^\\p{L}\\p{N}_-]+"), "-").trim('-')
        return cleaned.ifEmpty { "project-${System.currentTimeMillis()}" }.take(48)
    }
}
