package com.pocketforge.ai.termux

import android.util.Base64
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.pocketforge.ai.data.AppConfig
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Properties

/**
 * Termux 桥接：用 SSH 连到本机 Termux 的 sshd（默认 127.0.0.1:8022）。
 *
 * 传输层加密由 SSH-2 协商决定，典型组合：
 *   - 密钥交换：curve25519-sha256（或 ecdh-sha2-nistp256）
 *   - 主机密钥：ssh-ed25519 / rsa-sha2-256
 *   - 对称加密：chacha20-poly1305@openssh.com 或 aes256-gcm@openssh.com
 *   - 完整性：poly1305 / hmac-sha2-256
 *   - 用户认证：publickey（ed25519，~/.ssh/id_ed25519）或 password
 *
 * 我们在这层之上只做两件事：
 *   1. TOFU 指纹校验：首次连接记录主机公钥的 SHA-256，之后指纹变了就拒绝连接（防中间人）；
 *   2. 命令红线：明显的破坏性命令直接拦住，不发给 shell。
 */
object SshClient {

    private const val MAX_OUTPUT = 24_000

    data class Outcome(
        val ok: Boolean,
        val message: String,
        val exit: Int = -1,
        val fingerprint: String = "",
        val hostKeyLearned: Boolean = false,
    )

    private val BLOCKED = listOf(
        "rm -rf /", "rm -rf /*", "rm -fr /", "mkfs", "dd if=/dev/zero of=/dev",
        "reboot", "shutdown", ":(){", "pm uninstall", "pm clear",
        "> /system", "chmod -R 777 /", "mount -o remount",
    )

    fun blockedReason(command: String): String? {
        val normalized = command.trim().lowercase().replace("  ", " ")
        if (normalized.isEmpty()) return "命令为空"
        BLOCKED.firstOrNull { normalized.contains(it) }?.let {
            return "命令包含高风险片段「$it」，已拦截（只允许在 Termux 自己的目录里干活）"
        }
        return null
    }

    fun test(cfg: AppConfig): Outcome {
        val reason = validate(cfg)
        if (reason != null) return Outcome(false, reason)
        return try {
            val (session, fp, learned) = open(cfg)
            session.disconnect()
            Outcome(true, "连接成功 · ${cfg.termuxUser}@${cfg.termuxHost}:${cfg.termuxPort} · 主机指纹 $fp", 0, fp, learned)
        } catch (t: Throwable) {
            Outcome(false, "连接失败：${t.message ?: t.javaClass.simpleName}")
        }
    }

    fun exec(cfg: AppConfig, command: String, timeoutMs: Int = 30_000): Outcome {
        validate(cfg)?.let { return Outcome(false, it) }
        blockedReason(command)?.let { return Outcome(false, it) }

        var session: Session? = null
        return try {
            val opened = open(cfg)
            session = opened.first
            val channel = session.openChannel("exec") as ChannelExec
            channel.setCommand("cd ~ 2>/dev/null; $command")

            val out = ByteArrayOutputStream()
            val err = ByteArrayOutputStream()
            channel.setOutputStream(out)
            channel.setErrStream(err)
            channel.connect(8000)

            val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(2_000, 120_000)
            while (!channel.isClosed && System.currentTimeMillis() < deadline) {
                Thread.sleep(40)
            }
            val timedOut = !channel.isClosed
            val exit = if (!timedOut) channel.exitStatus else -1
            channel.disconnect()

            val stdout = out.toString("UTF-8")
            val stderr = err.toString("UTF-8")
            val merged = buildString {
                if (stdout.isNotBlank()) append(stdout.trim())
                if (stderr.isNotBlank()) {
                    if (isNotEmpty()) append('\n')
                    append("[stderr] ").append(stderr.trim())
                }
                if (isEmpty()) append("(无输出)")
            }.take(MAX_OUTPUT)

            Outcome(
                ok = !timedOut && exit == 0,
                message = if (timedOut) "执行超时（${timeoutMs / 1000}s），已断开\n$merged" else merged,
                exit = exit,
                fingerprint = opened.second,
                hostKeyLearned = opened.third,
            )
        } catch (t: Throwable) {
            Outcome(false, "SSH 执行失败：${t.message ?: t.javaClass.simpleName}")
        } finally {
            runCatching { session?.disconnect() }
        }
    }

    private fun validate(cfg: AppConfig): String? = when {
        cfg.termuxHost.isBlank() -> "Termux 主机地址为空"
        cfg.termuxUser.isBlank() -> "Termux 用户名未填（在 Termux 里执行 whoami 可查到，形如 u0_a123）"
        cfg.termuxAuth == "key" && cfg.termuxKey.isBlank() -> "选择了密钥认证，但私钥为空"
        cfg.termuxAuth != "key" && cfg.termuxPassword.isBlank() -> "选择了密码认证，但密码为空"
        else -> null
    }

    /** 返回 (session, 指纹, 是否首次学习到指纹) */
    private fun open(cfg: AppConfig): Triple<Session, String, Boolean> {
        val jsch = JSch()
        if (cfg.termuxAuth == "key") {
            val passBytes = cfg.termuxPassphrase.takeIf { it.isNotBlank() }?.toByteArray()
            jsch.addIdentity("pocketforge", cfg.termuxKey.toByteArray(), null, passBytes)
        }

        val session = jsch.getSession(
            cfg.termuxUser.trim(),
            cfg.termuxHost.trim(),
            cfg.termuxPort.coerceIn(1, 65535),
        )
        if (cfg.termuxAuth != "key") session.setPassword(cfg.termuxPassword)

        val props = Properties().apply {
            put("StrictHostKeyChecking", "no")
            put(
                "PreferredAuthentications",
                if (cfg.termuxAuth == "key") "publickey" else "password,keyboard-interactive,publickey"
            )
            put("ServerAliveInterval", "15")
        }
        session.setConfig(props)
        session.connect(12_000)

        val hostKey = runCatching { session.hostKey?.key }.getOrNull()
        val fp = if (!hostKey.isNullOrBlank()) fingerprintOf(hostKey.toByteArray()) else ""
        val learned: Boolean
        if (fp.isNotBlank() && cfg.termuxHostKey.isNotBlank() && cfg.termuxHostKey != fp) {
            runCatching { session.disconnect() }
            throw IllegalStateException(
                "主机指纹不匹配，可能被中间人劫持。\n已记录：${cfg.termuxHostKey}\n本次：$fp\n" +
                    "如果确实重装过 Termux，请在设置里点「信任新指纹」。"
            )
        }
        learned = fp.isNotBlank() && cfg.termuxHostKey.isBlank()
        return Triple(session, fp, learned)
    }

    fun fingerprintOf(hostKeyBlob: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(hostKeyBlob)
        val b64 = Base64.encodeToString(digest, Base64.NO_WRAP).trimEnd('=')
        return "SHA256:$b64"
    }
}
