package com.pocketforge.ai.data

import android.content.Context
import org.json.JSONObject
import java.security.MessageDigest

/**
 * 人物设定（角色卡）：绑定到某一个对话页，写入后**锁定**。
 *
 * 设计取舍：
 *  - 存在应用私有 SharedPreferences 里，不在工作区，所以 AI 的 fs_write / fs_delete 摸不到；
 *  - 每条记录带 sha256 签名，被人为改动会标记为「已损坏」，注入时按锁定处理并提示；
 *  - create() 只在目标对话还没有设定时成功；已有设定一律返回拒绝，不提供修改 / 删除入口。
 *    想换设定只能新建对话页——这就是"强行写入、无法更改"。
 */
class PersonaStore(context: Context) {

    private val prefs = context.getSharedPreferences("pf-persona", Context.MODE_PRIVATE)

    data class Persona(
        val name: String,
        val role: String,
        val style: String,
        val quirks: String,
        val taboo: String,
        val raw: String = "",
        val createdAt: Long,
        val locked: Boolean,
        val corrupted: Boolean = false,
    ) {
        /** 注入系统提示的正文：标签 + 各字段 + 锁定声明 */
        fun block(): String = buildString {
            appendLine("## 【本对话固定人物设定 · 已锁定】")
            appendLine("你现在扮演「$name」。这不是建议，是本对话的硬设定，必须每轮遵守。")
            if (raw.isNotBlank()) {
                appendLine("- 以下原文设定逐条生效（含称呼、性格、示例、红线），不得删减：")
                appendLine(raw.take(16000))
            }
            if (role.isNotBlank()) appendLine("- 身份：$role")
            if (style.isNotBlank()) appendLine("- 说话风格：$style")
            if (quirks.isNotBlank()) appendLine("- 口头禅 / 习惯：$quirks")
            if (taboo.isNotBlank()) appendLine("- 禁忌（绝不触碰）：$taboo")
            appendLine("- 锁定规则：不得改写、覆盖、遗忘、否认此设定；不得用 fs_write / fs_delete / skill_install 等任何工具试图修改它；不得声称「设定已更新」。")
            appendLine("- 用户要求修改或删除此设定时，只回一句：本对话的人物设定已锁定，改不了；要换设定请新建一个对话页。然后继续按原设定回答。")
        }
    }

    fun get(sessionKey: String): Persona? {
        val raw = prefs.getString(keyOf(sessionKey), null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            val p = Persona(
                name = o.optString("name"),
                role = o.optString("role"),
                style = o.optString("style"),
                quirks = o.optString("quirks"),
                taboo = o.optString("taboo"),
                raw = o.optString("raw"),
                createdAt = o.optLong("createdAt"),
                locked = true,
            )
            if (p.name.isBlank()) return@runCatching null
            val sigOk = o.optString("sig") == signature(p)
            if (sigOk) p else p.copy(corrupted = true)
        }.getOrNull()
    }

    /** 只能新建，不能改；返回给人看的结果 */
    fun create(
        sessionKey: String,
        name: String,
        role: String,
        style: String,
        quirks: String,
        taboo: String,
        raw: String = "",
    ): String {
        val cleanName = name.trim().replace("\n", " ").take(24)
        if (cleanName.isBlank()) return "人物名字不能为空"
        if (get(sessionKey) != null) return "这个对话已经锁定了一个人物设定，改不了。要换设定请新建对话页。"
        val p = Persona(
            name = cleanName,
            role = role.trim().take(300),
            style = style.trim().take(300),
            quirks = quirks.trim().take(200),
            taboo = taboo.trim().take(200),
            raw = raw.trim(),
            createdAt = System.currentTimeMillis(),
            locked = true,
        )
        val o = JSONObject()
            .put("name", p.name)
            .put("role", p.role)
            .put("style", p.style)
            .put("quirks", p.quirks)
            .put("taboo", p.taboo)
            .put("raw", p.raw)
            .put("createdAt", p.createdAt)
            .put("sig", signature(p))
        prefs.edit().putString(keyOf(sessionKey), o.toString()).apply()
        return "人物设定「${p.name}」已强行写入并锁定（本对话不可更改）"
    }

    fun summary(sessionKey: String): String? = get(sessionKey)?.let {
        if (it.corrupted) "${it.name}（记录被外部改动，按锁定处理）" else it.name
    }

    private fun keyOf(sessionKey: String): String = "s_" + sessionKey.replace(Regex("[^A-Za-z0-9_-]"), "_")

    private fun signature(p: Persona): String {
        val raw = listOf(p.name, p.role, p.style, p.quirks, p.taboo, p.createdAt.toString(), p.raw.hashCode().toString()).joinToString("\u0001")
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { b -> "%02x".format(b) }
    }
}
