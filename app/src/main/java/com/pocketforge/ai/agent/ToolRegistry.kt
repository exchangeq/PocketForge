package com.pocketforge.ai.agent

import android.content.Context
import com.pocketforge.ai.core.Sandbox
import com.pocketforge.ai.data.AppConfig
import com.pocketforge.ai.data.PlanItem
import com.pocketforge.ai.data.ToolResult
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
) {

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
        )

        private val WRITE_TOOLS = setOf(
            "fs_write", "fs_mkdir", "fs_move", "fs_delete", "project_new"
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
                else -> ToolResult(false, "未知工具：$name")
            }
        } catch (t: Throwable) {
            ToolResult(false, "执行失败：${t.message ?: t.javaClass.simpleName}")
        }
    }

    // ---------------- 技能 ----------------

    private fun skillsIndex(): ToolResult {
        val workspace = sandbox.workspace()
        val dir = skills.installTo(workspace)
        onIndexRead()
        val payload = buildString {
            appendLine(skills.index())
            appendLine()
            appendLine(skills.catalog())
            appendLine()
            appendLine("工作区：${workspace.absolutePath}")
            appendLine("技能副本：${dir.absolutePath}")
        }
        return ToolResult(true, "已读取技能总索引（${workspace.absolutePath}/skills）", payload)
    }

    private fun skillRead(args: JSONObject): ToolResult {
        val name = args.optString("name").trim()
        require(name.isNotEmpty()) { "缺少参数 name" }
        require(name in SkillLibrary.ENTRIES.map { it.first }) { "技能文件不存在：$name" }
        val content = skills.read(name)
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
        require(bytes.size <= Sandbox.MAX_WRITE_BYTES) { "单次写入上限 2MB，当前 ${humanSize(bytes.size.toLong())}" }
        val file = sandbox.resolveWrite(path)
        file.parentFile?.mkdirs()
        require(file.absolutePath != sandbox.workspace().absolutePath) { "不能把工作区根目录当文件写" }
        if (append) file.appendText(content) else file.writeText(content)
        val lines = content.count { it == '\n' } + 1
        return ToolResult(
            true,
            "已写入 ${sandbox.relative(file)}",
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
        val rootRaw = args.optString("path", "").trim()
        val root = if (rootRaw.isEmpty() || rootRaw == ".") sandbox.workspace() else sandbox.resolveRead(rootRaw)
        val maxHits = args.optInt("maxHits", 40).coerceIn(1, 200)
        val hits = StringBuilder()
        var hitCount = 0
        var scanned = 0

        fun walk(file: File, depth: Int) {
            if (hitCount >= maxHits || scanned > 4000 || depth > 8) return
            if (file.isDirectory) {
                if (file.name in listOf(".git", "build", "node_modules", ".trash")) return
                file.listFiles()?.forEach { walk(it, depth + 1) }
                return
            }
            if (file.length() == 0L || file.length() > 200_000) return
            val ext = file.extension.lowercase()
            if (ext in listOf("png", "jpg", "jpeg", "webp", "gif", "mp3", "mp4", "wav", "zip", "apk", "jar", "ogg", "ttf", "db")) return
            scanned++
            val lines = runCatching { file.readLines() }.getOrNull() ?: return
            lines.forEachIndexed { index, line ->
                if (hitCount < maxHits && line.contains(query, ignoreCase = true)) {
                    hitCount++
                    hits.append(sandbox.relative(file)).append(':').append(index + 1).append(' ')
                        .append(line.trim().take(160)).append('\n')
                }
            }
        }
        walk(root, 0)
        val summary = if (hitCount == 0) "未找到包含「$query」的内容（扫描 $scanned 个文本文件）"
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
        dir.mkdirs()

        val skillName = skills.skillFor(type)
        val skillText = skills.read(skillName).ifBlank { skills.index() }

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
        onProjectOpened(name)

        val tip = if (skillText.isNotBlank()) "\n\n已为该类型匹配技能 $skillName。" else ""
        return ToolResult(
            true,
            "项目 $name 已就绪：${dir.absolutePath}（新建 ${created.size} 项）$tip",
            created.joinToString("\n")
        )
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
        }
        return ToolResult(true, "运行配置（已脱敏）", payload)
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
