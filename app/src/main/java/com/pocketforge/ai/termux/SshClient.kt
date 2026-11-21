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
 * Termux 桥接：SSH 连本机 Termux 的 sshd（默认 127.0.0.1:8022）。
 *
 * 稳定性加固（v1.12）：
 *  1. 连接池：一条 Session 复用 90 秒，连续命令不再反复握手（原来每条命令都要重新连接，慢且容易断）；
 *  2. 心跳：ServerAliveInterval 10s + CountMax 6，锁屏/切后台时不会被静默掐断；
 *  3. 压缩：zlib，弱网上少传一大截；
 *  4. 失败自愈：连接/通道失败最多重试 3 次（0.4s / 0.8s 退避），坏 Session 立刻丢弃重建；
 *  5. TOFU 指纹校验 + 破坏性命令拦截保持原样。
 */
object SshClient {

    private const val MAX_OUTPUT = 24_000
    private const val REUSE_MS = 90_000L

    data class Outcome(
        val ok: Boolean,
        val message: String,
        val exit: Int = -1,
        val fingerprint: String = "",
        val hostKeyLearned: Boolean = false,
        val latencyMs: Long = -1,
    )

    private val lock = Any()
    private var cached: Session? = null
    private var cachedFingerprint: String = ""
    private var cachedAt: Long = 0
    private var cachedSig: String = ""

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
        validate(cfg)?.let { return Outcome(false, it) }
        return try {
            val started = System.currentTimeMillis()
            val (session, fp, learned) = openWithRetry(cfg)
            val conn = session.openChannel("exec") as ChannelExec
            conn.setCommand("echo pf-ok")
            val out = ByteArrayOutputStream()
            conn.outputStream = out
            conn.connect(6_000)
            val deadline = System.currentTimeMillis() + 8_000
            while (!conn.isClosed && System.currentTimeMillis() < deadline) Thread.sleep(30)
            conn.disconnect()
            val latency = System.currentTimeMillis() - started
            val reply = out.toString("UTF-8").trim()
            Outcome(
                ok = reply.contains("pf-ok"),
                message = "连接成功 · ${cfg.termuxUser}@${cfg.termuxHost}:${cfg.termuxPort} · ${latency}ms · 主机指纹 $fp（连接已复用，后续更快）",
                exit = 0, fingerprint = fp, hostKeyLearned = learned, latencyMs = latency,
            )
        } catch (t: Throwable) {
            Outcome(false, "连接失败：${t.message ?: t.javaClass.simpleName}")
        }
    }

    fun exec(cfg: AppConfig, command: String, timeoutMs: Int = 30_000): Outcome {
        validate(cfg)?.let { return Outcome(false, it) }
        blockedReason(command)?.let { return Outcome(false, it) }

        synchronized(lock) {
            var lastError = ""
            repeat(3) { attempt ->
                val started = System.currentTimeMillis()
                var channel: ChannelExec? = null
                try {
                    val (session, fp, learned) = openWithRetry(cfg)
                    channel = session.openChannel("exec") as ChannelExec
                    channel.setCommand("cd ~ 2>/dev/null; $command")
                    val out = ByteArrayOutputStream()
                    val err = ByteArrayOutputStream()
                    channel.setOutputStream(out)
                    channel.setErrStream(err)
                    channel.connect(6_000)

                    val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(2_000, 120_000)
                    while (!channel.isClosed && System.currentTimeMillis() < deadline) {
                        Thread.sleep(25)
                        if (channel.isClosed) break
                    }
                    val timedOut = !channel.isClosed
                    val exit = if (!timedOut) channel.exitStatus else -1
                    channel.disconnect()
                    channel = null

                    val merged = buildString {
                        append(out.toString("UTF-8").trim())
                        if (err.toString("UTF-8").isNotBlank()) {
                            if (isNotEmpty()) append('\n')
                            append("[stderr] ").append(err.toString("UTF-8").trim())
                        }
                        if (isEmpty()) append("(无输出)")
                    }.take(MAX_OUTPUT)

                    return Outcome(
                        ok = !timedOut && exit == 0,
                        message = if (timedOut) "执行超时（${timeoutMs / 1000}s），已断开\n$merged" else merged,
                        exit = exit,
                        fingerprint = fp,
                        hostKeyLearned = learned,
                        latencyMs = System.currentTimeMillis() - started,
                    )
                } catch (t: Throwable) {
                    lastError = t.message ?: t.javaClass.simpleName
                    runCatching { channel?.disconnect() }
                    dropCache()
                    if (attempt < 2) Thread.sleep(400L * (attempt + 1))
                }
            }
            return Outcome(false, "SSH 执行失败（已重试 3 次）：$lastError")
        }
    }

    fun dropCache() {
        synchronized(lock) {
            runCatching { cached?.disconnect() }
            cached = null
            cachedAt = 0
            cachedSig = ""
        }
    }

    private fun validate(cfg: AppConfig): String? = when {
        cfg.termuxHost.isBlank() -> "Termux 主机地址为空"
        cfg.termuxUser.isBlank() -> "Termux 用户名未填（在 Termux 里执行 whoami 可查到，形如 u0_a123）"
        cfg.termuxAuth == "key" && cfg.termuxKey.isBlank() -> "选择了密钥认证，但私钥为空"
        cfg.termuxAuth != "key" && cfg.termuxPassword.isBlank() -> "选择了密码认证，但密码为空"
        else -> null
    }

    private fun signature(cfg: AppConfig): String =
        listOf(cfg.termuxHost, cfg.termuxPort.toString(), cfg.termuxUser, cfg.termuxAuth, cfg.termuxPassword.hashCode().toString()).joinToString("|")

    /** 复用还活着的连接；过期 / 参数变了 / 断开就重连 */
    private fun openWithRetry(cfg: AppConfig): Triple<Session, String, Boolean> {
        val now = System.currentTimeMillis()
        val sig = signature(cfg)
        cached?.let { s ->
            if (s.isConnected && now - cachedAt < REUSE_MS && cachedSig == sig) {
                return Triple(s, cachedFingerprint, false)
            }
            dropCache()
        }
        var last: Throwable? = null
        repeat(2) { attempt ->
            try {
                val triple = open(cfg)
                cached = triple.first
                cachedFingerprint = triple.second
                cachedAt = System.currentTimeMillis()
                cachedSig = sig
                return triple
            } catch (t: Throwable) {
                last = t
                dropCache()
                if (attempt == 0) Thread.sleep(500)
            }
        }
        throw IllegalStateException(last?.message ?: "连接失败")
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
        session.setTimeout(20_000)

        val props = Properties().apply {
            put("StrictHostKeyChecking", "no")
            put(
                "PreferredAuthentications",
                if (cfg.termuxAuth == "key") "publickey" else "password,keyboard-interactive,publickey"
            )
            put("ServerAliveInterval", "10")
            put("ServerAliveCountMax", "6")
            put("compression.s2c", "zlib")
            put("compression.c2s", "zlib")
            put("TCPNoDelay", "true")
        }
        session.setConfig(props)
        session.connect(8_000)

        val hostKey = runCatching { session.hostKey?.key }.getOrNull()
        val fp = if (!hostKey.isNullOrBlank()) fingerprintOf(hostKey.toByteArray()) else ""
        if (fp.isNotBlank() && cfg.termuxHostKey.isNotBlank() && cfg.termuxHostKey != fp) {
            runCatching { session.disconnect() }
            throw IllegalStateException(
                "主机指纹不匹配，可能被中间人劫持。\n已记录：${cfg.termuxHostKey}\n本次：$fp\n" +
                    "如果确实重装过 Termux，请在设置里点「信任新指纹」。"
            )
        }
        val learned = fp.isNotBlank() && cfg.termuxHostKey.isBlank()
        return Triple(session, fp, learned)
    }

    fun fingerprintOf(hostKeyBlob: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(hostKeyBlob)
        val b64 = Base64.encodeToString(digest, Base64.NO_WRAP).trimEnd('=')
        return "SHA256:$b64"
    }
}
