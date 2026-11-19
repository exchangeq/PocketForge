package com.pocketforge.ai.agent

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 文档锁：把已经定稿的文稿/设定锁住，防止 AI「顺手改一下」。
 *
 * 两种模式：
 *   - soft：默认。整篇保护，AI 必须显式 override=true 才能覆盖；
 *           若登记了章节，则只有被登记的章节受保护，其余章节可自由续写。
 *   - hard：硬锁，任何写入一律拒绝，只能先 doc_unlock。
 *
 * 锁记录落在 <workspace>/.pocketforge/locks.json，跟着项目走，不写全局。
 */
class DocLocks(private val workspaceProvider: () -> File?) {

    data class Entry(
        val path: String,
        val mode: String,
        val note: String,
        val lockedAt: Long,
        val sections: List<String>,
    ) {
        val hard: Boolean get() = mode == "hard"
    }

    private fun file(): File? {
        val ws = workspaceProvider() ?: return null
        val dir = File(ws, ".pocketforge")
        dir.mkdirs()
        return File(dir, "locks.json")
    }

    private fun root(): JSONObject {
        val f = file() ?: return JSONObject()
        return runCatching { JSONObject(f.readText()) }.getOrElse { JSONObject() }
    }

    private fun persist(root: JSONObject) {
        runCatching { file()?.writeText(root.toString(2)) }
    }

    fun list(): List<Entry> {
        val root = root()
        return root.keys().asSequence().mapNotNull { key ->
            val obj = root.optJSONObject(key) ?: return@mapNotNull null
            Entry(
                path = key,
                mode = obj.optString("mode", "soft"),
                note = obj.optString("note", ""),
                lockedAt = obj.optLong("lockedAt", 0L),
                sections = obj.optJSONObject("sections")?.keys()?.asSequence()?.toList() ?: emptyList(),
            )
        }.sortedBy { it.path }.toList()
    }

    /** 锁定；content 用于给登记章节拍快照 */
    fun lock(path: String, content: String, sections: List<String>, hard: Boolean, note: String): String {
        val root = root()
        val sectionHashes = JSONObject()
        sections.forEach { title ->
            val body = extractSection(content, title)
            if (body != null) sectionHashes.put(title, sha256(body))
        }
        root.put(
            path,
            JSONObject()
                .put("mode", if (hard) "hard" else "soft")
                .put("note", note)
                .put("lockedAt", System.currentTimeMillis())
                .put("sha256", sha256(content))
                .put("sections", sectionHashes)
        )
        persist(root)
        val scope = when {
            hard -> "硬锁"
            sectionHashes.length() > 0 -> "章节锁（${sectionHashes.length()} 节）"
            else -> "整篇保护"
        }
        return "已锁定 $path · $scope${if (note.isNotBlank()) " · 备注：$note" else ""}"
    }

    fun unlock(path: String): Boolean {
        val root = root()
        if (!root.has(path)) return false
        root.remove(path)
        persist(root)
        return true
    }

    /**
     * 写入前校验。返回 null 表示放行，否则返回拒绝原因。
     * override 只对 soft 整篇保护生效；hard 一律拒绝。
     */
    fun check(path: String, newContent: String, override: Boolean): String? {
        val entry = list().firstOrNull { it.path == path } ?: return null
        if (entry.hard) {
            return "「$path」是硬锁文档，禁止任何写入。需要改动请先用 doc_unlock 解锁，或让用户确认。"
        }
        if (entry.sections.isNotEmpty()) {
            entry.sections.forEach { title ->
                val newBody = extractSection(newContent, title)
                    ?: return "「$path」的锁定章节「$title」在新内容里消失了。锁定章节只能追加，不能删改。"
                val oldBody = extractSection(
                    runCatching {
                        val ws = workspaceProvider() ?: return@forEach
                        File(ws, path).readText()
                    }.getOrElse { "" },
                    title
                ) ?: return "「$path」的锁定章节「$title」读取失败，已拦截本次写入。"
                if (sha256(newBody) != sha256(oldBody)) {
                    return "「$path」的锁定章节「$title」内容被改动。要改它，先 doc_unlock 或明确说明。"
                }
            }
            return null
        }
        return if (override) null else "「$path」已加整篇保护（soft）。确认要改就传 override=true，或先 doc_unlock。"
    }

    // ---------- 工具 ----------

    fun extractSection(source: String, title: String): String? {
        val lines = source.lines()
        var start = -1
        var level = 0
        lines.forEachIndexed { index, line ->
            if (start >= 0) return@forEachIndexed
            val trimmed = line.trim()
            if (trimmed.startsWith("#")) {
                val hashes = trimmed.takeWhile { it == '#' }.length
                val text = trimmed.drop(hashes).trim()
                if (text == title) {
                    start = index + 1
                    level = hashes
                }
            }
        }
        if (start < 0) return null
        val body = StringBuilder()
        var index = start
        while (index < lines.size) {
            val trimmed = lines[index].trim()
            if (trimmed.startsWith("#")) {
                val hashes = trimmed.takeWhile { it == '#' }.length
                if (hashes <= level) break
            }
            body.appendLine(lines[index])
            index++
        }
        return body.toString().trimEnd()
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
