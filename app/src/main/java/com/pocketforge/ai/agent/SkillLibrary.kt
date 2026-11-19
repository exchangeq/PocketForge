package com.pocketforge.ai.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 技能库。
 *
 * 三层优先级（前层覆盖后层）：
 *   1. workspace/skills 下的 .md    工作区里注入/手改的版本
 *   2. APK assets/skills 下的 .md   随包内置的基线
 *   3. DEFAULT_INDEX                全部缺失时的兜底
 *
 * 「强制注入」会在工作区写一个 .force 标记：之后该工作区只认第 1 层，
 * 内置基线不再顶替缺失文件 —— 只作用于这一个工作区，不影响全局。
 */
class SkillLibrary(private val context: Context) {

    companion object {
        val ENTRIES: List<Pair<String, String>> = listOf(
            "SKILLS.md" to "总索引：开工纪律与技能路由",
            "boundaries.md" to "越界防护：可写范围与禁止事项",
            "game-dev.md" to "游戏项目：目录结构、引擎与产物",
            "writing.md" to "写作项目：大纲、章节、去 AI 味",
            "app-dev.md" to "应用/网页项目：工程结构与验收",
            "runtime-config.md" to "加密配置与数据流协议说明",
            "answer-style.md" to "回答风格：结论、分段、总结、文件清单",
            "markdown-style.md" to "Markdown 排版规范：标题层级、表格、代码块",
            "character-card.md" to "角色卡：人设、口吻、禁写区（写作/剧本用）",
            "termux.md" to "Termux 桥接：sshd、密钥、SSH 加密套件与命令红线",
        )

        const val FORCE_MARK = ".force"
        const val MANIFEST = "skills-index.json"
        const val MAX_SKILL_BYTES = 512 * 1024

        const val DEFAULT_INDEX = """
# PocketForge 技能索引（缺失回退）

1. 任何新项目开工前先读本文件；
2. 再按项目类型读 game-dev.md / writing.md / app-dev.md / character-card.md；
3. 写文件前必须读 boundaries.md；
4. 对答前读 answer-style.md，排版按 markdown-style.md。
"""
    }

    // ---------- 读 ----------

    fun readBundled(name: String): String = try {
        context.assets.open("skills/$name").bufferedReader().use { it.readText() }
    } catch (t: Throwable) {
        ""
    }

    fun skillsDir(workspace: File): File = File(workspace, "skills")

    fun isForced(workspace: File): Boolean = File(skillsDir(workspace), FORCE_MARK).exists()

    /** 工作区优先；强制注入后不再回退内置基线 */
    fun read(name: String, workspace: File?): String {
        val safe = sanitizeName(name)
        if (workspace != null) {
            val local = File(skillsDir(workspace), safe)
            if (local.isFile) {
                val text = runCatching { local.readText() }.getOrDefault("")
                if (text.isNotBlank()) return text
            }
            if (isForced(workspace)) return ""
        }
        return readBundled(safe)
    }

    fun index(workspace: File? = null): String =
        read("SKILLS.md", workspace).ifBlank { DEFAULT_INDEX }

    fun catalog(workspace: File? = null): String = buildString {
        appendLine("# 可用技能文件")
        val localNames = workspace?.let { dir ->
            skillsDir(dir).listFiles { f -> f.isFile && f.name.endsWith(".md") }
                ?.map { it.name }?.sorted() ?: emptyList()
        } ?: emptyList()
        ENTRIES.forEach { (name, desc) ->
            val mark = if (localNames.contains(name)) "（工作区已注入）" else ""
            appendLine("- $name：$desc$mark")
        }
        localNames.filter { it !in ENTRIES.map { e -> e.first } }.forEach { extra ->
            appendLine("- $extra：工作区自定义技能")
        }
        if (workspace != null && isForced(workspace)) {
            appendLine("")
            appendLine("> 本工作区已开启强制注入：只使用工作区内技能，缺哪个就补哪个。")
        }
    }

    fun skillFor(projectType: String): String = when (projectType.lowercase()) {
        "game", "游戏" -> "game-dev.md"
        "writing", "write", "book", "写作" -> "writing.md"
        "app", "web", "应用", "网页" -> "app-dev.md"
        "character", "角色" -> "character-card.md"
        else -> "SKILLS.md"
    }

    // ---------- 写 ----------

    /** 把内置技能同步到工作区。force=false 时不覆盖已有文件 */
    fun installTo(workspace: File, force: Boolean = false): Int {
        val dir = skillsDir(workspace)
        dir.mkdirs()
        var written = 0
        ENTRIES.forEach { (name, _) ->
            val target = File(dir, name)
            val content = readBundled(name)
            if (content.isNotBlank() && (force || !target.exists())) {
                runCatching { target.writeText(content) }.onSuccess { written++ }
            }
        }
        writeManifest(dir)
        return written
    }

    /** 强制注入：覆盖工作区技能并落 .force 标记（仅本工作区生效） */
    fun forceInject(workspace: File, extra: Map<String, String> = emptyMap()): Int {
        val dir = skillsDir(workspace)
        dir.mkdirs()
        var written = installTo(workspace, force = true)
        extra.forEach { (name, text) ->
            val target = File(dir, sanitizeName(name))
            runCatching { target.writeText(text) }.onSuccess { written++ }
        }
        runCatching { File(dir, FORCE_MARK).writeText("forced at ${System.currentTimeMillis()}") }
        writeManifest(dir)
        return written
    }

    fun clearForce(workspace: File) {
        runCatching { File(skillsDir(workspace), FORCE_MARK).delete() }
    }

    /** 从远端拉取技能并注入工作区；url 可以是单个 .md，也可以是技能索引 JSON */
    fun remoteInstall(
        url: String,
        token: String,
        workspace: File,
        force: Boolean,
    ): RemoteResult {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return RemoteResult(false, "地址为空", 0)
        return try {
            val files = if (trimmed.substringBefore('?').endsWith(".md", true)) {
                val name = trimmed.substringBefore('?').trimEnd('/').substringAfterLast('/')
                listOf(name to fetch(trimmed, token))
            } else {
                val indexUrl = if (trimmed.endsWith(".json", true)) trimmed else "$trimmed/skills-index.json"
                parseIndex(fetch(indexUrl, token), token)
            }
            if (files.isEmpty()) return RemoteResult(false, "索引里没有可下载的技能", 0)

            val dir = skillsDir(workspace)
            dir.mkdirs()
            var count = 0
            files.forEach { (name, content) ->
                val safe = sanitizeName(name)
                if (content.contains("#") && content.length in 16..MAX_SKILL_BYTES) {
                    val target = File(dir, safe)
                    if (force || !target.exists()) {
                        target.writeText(content)
                        count++
                    } else {
                        target.writeText(content)
                        count++
                    }
                }
            }
            if (force) {
                runCatching { File(dir, FORCE_MARK).writeText("remote ${System.currentTimeMillis()}") }
            }
            writeManifest(dir)
            RemoteResult(true, "已注入 $count 个技能文件到 ${dir.absolutePath}", count)
        } catch (t: Throwable) {
            RemoteResult(false, "下载失败：${t.message}", 0)
        }
    }

    private fun parseIndex(json: String, token: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val root = runCatching { JSONObject(json) }.getOrNull()
        val array: JSONArray? = root?.optJSONArray("skills")
            ?: runCatching { JSONArray(json) }.getOrNull()
        array?.let { arr ->
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val name = obj.optString("name", "")
                val itemUrl = obj.optString("url", "")
                if (name.isBlank() || itemUrl.isBlank()) continue
                out.add(name to fetch(itemUrl, token))
            }
        }
        return out
    }

    private fun fetch(url: String, token: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 25000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "text/plain, application/json")
            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalMessage("HTTP $code")
            val bytes = conn.inputStream.use { it.readBytes() }
            if (bytes.size > MAX_SKILL_BYTES) throw IllegalMessage("文件过大")
            return String(bytes, Charsets.UTF_8)
        } finally {
            conn.disconnect()
        }
    }

    private fun writeManifest(dir: File) {
        val entries = JSONArray()
        dir.listFiles { f -> f.isFile && f.name.endsWith(".md") }?.sortedBy { it.name }?.forEach { file ->
            entries.put(
                JSONObject()
                    .put("name", file.name)
                    .put("bytes", file.length())
                    .put("sha256", sha256(file.readBytes()))
            )
        }
        runCatching { File(dir, MANIFEST).writeText(JSONObject().put("skills", entries).toString(2)) }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun sanitizeName(raw: String): String {
        val base = raw.trim().substringAfterLast('/').substringAfterLast('\\')
        val cleaned = base.replace(Regex("[^A-Za-z0-9._\\-\\u4e00-\\u9fa5]"), "_")
        return when {
            cleaned.isEmpty() -> "skill.md"
            cleaned.endsWith(".md", true) -> cleaned
            else -> "$cleaned.md"
        }
    }

    data class RemoteResult(val ok: Boolean, val message: String, val count: Int)

    private class IllegalMessage(msg: String) : Exception(msg)
}
