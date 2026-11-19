package com.pocketforge.ai.agent

import android.content.Context
import java.io.File

/**
 * 技能库：随 APK 打包的 Markdown 规范（assets/skills），
 * 首次运行时同步到工作区 skills/ 目录，让 AI 与用户看到同一份规则。
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
        )

        const val DEFAULT_INDEX = """
# PocketForge 技能索引（缺失回退）

1. 任何新项目开工前先读本文件；
2. 再按项目类型读 game-dev.md / writing.md / app-dev.md；
3. 写文件前必须读 boundaries.md。
"""
    }

    fun read(name: String): String = try {
        context.assets.open("skills/$name").bufferedReader().use { it.readText() }
    } catch (t: Throwable) {
        ""
    }

    fun index(): String = read("SKILLS.md").ifBlank { DEFAULT_INDEX }

    fun catalog(): String = buildString {
        appendLine("# 可用技能文件")
        ENTRIES.forEach { (name, desc) -> appendLine("- $name：$desc") }
    }

    /** 同步到 <workspace>/skills/，不覆盖用户手改过的版本 */
    fun installTo(workspace: File): File {
        val dir = File(workspace, "skills")
        dir.mkdirs()
        ENTRIES.forEach { (name, _) ->
            val target = File(dir, name)
            val content = read(name)
            if (content.isNotBlank() && !target.exists()) {
                target.writeText(content)
            }
        }
        return dir
    }

    fun skillFor(projectType: String): String = when (projectType.lowercase()) {
        "game", "游戏" -> "game-dev.md"
        "writing", "write", "book", "写作" -> "writing.md"
        "app", "web", "应用", "网页" -> "app-dev.md"
        else -> "SKILLS.md"
    }
}
