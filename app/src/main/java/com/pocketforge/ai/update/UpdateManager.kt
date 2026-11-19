package com.pocketforge.ai.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.pocketforge.ai.data.AppConfig
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 发行版远程更新：
 *   1. 读 GitHub Releases 最新版（tag + assets）；
 *   2. 与已安装版本比较；
 *   3. 下载 release APK 到 cache；
 *   4. 校验包名 + 签名摘要（必须与当前安装包一致），再拉起系统安装器。
 *
 * 只接受 https，且签名不一致直接拒绝 —— 防止被替换成第三方包。
 */
class UpdateManager(
    private val context: Context,
    private val configProvider: () -> AppConfig,
) {

    data class ReleaseInfo(
        val tag: String,
        val name: String,
        val notes: String,
        val apkUrl: String,
        val apkSize: Long,
        val htmlUrl: String,
    )

    data class CheckResult(
        val upToDate: Boolean,
        val currentVersion: String,
        val release: ReleaseInfo?,
        val message: String,
    )

    fun currentVersion(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    private fun currentVersionCode(): Long = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
    }.getOrDefault(0L)

    fun check(): CheckResult {
        val cfg = configProvider()
        val repo = cfg.updateRepo.ifBlank { "exchangeq/PocketForge" }.trim().removePrefix("https://github.com/")
        val current = currentVersion()
        return try {
            val conn: HttpURLConnection = URL("https://api.github.com/repos/$repo/releases/latest")
                .openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 12_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "PocketForge")
            if (cfg.githubToken.isNotBlank()) {
                conn.setRequestProperty("Authorization", "Bearer ${cfg.githubToken.trim()}")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
                return CheckResult(false, current, null, "查询失败：HTTP $code ${err.take(160)}")
            }
            val raw = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val json = JSONObject(raw)
            val tag = json.optString("tag_name", "")
            val name = json.optString("name", tag)
            val notes = json.optString("body", "").take(2000)
            val html = json.optString("html_url", "https://github.com/$repo/releases")

            val assets = json.optJSONArray("assets") ?: org.json.JSONArray()
            var apkUrl = ""
            var apkSize = 0L
            var assetApiUrl = ""
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                val assetName = a.optString("name", "")
                if (assetName.endsWith(".apk") && !assetName.contains("debug")) {
                    apkUrl = a.optString("browser_download_url", "")
                    apkSize = a.optLong("size", 0L)
                    assetApiUrl = a.optString("url", "")
                }
            }
            if (apkUrl.isBlank()) {
                return CheckResult(false, current, null, "最新版 $tag 没找到 release APK")
            }
            val release = ReleaseInfo(tag, name, notes, apkUrl, apkSize, html)
            val newer = compareVersion(tag.removePrefix("v"), current) > 0
            CheckResult(
                upToDate = !newer,
                currentVersion = current,
                release = release,
                message = if (newer) "发现新版本 $tag（当前 $current）" else "已是最新版本（$current）",
            )
        } catch (t: Throwable) {
            CheckResult(false, current, null, "检查更新失败：${t.message ?: t.javaClass.simpleName}")
        }
    }

    /** 下载 APK 到 cache/update，返回文件；失败抛异常 */
    fun download(onProgress: ((Int, String) -> Unit)? = null): File {
        val cfg = configProvider()
        val result = check()
        val release = result.release ?: throw IllegalStateException(result.message)
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        val target = File(dir, "PocketForge-${release.tag}.apk")
        val tmp = File(dir, "${release.tag}.part")
        tmp.delete()

        val conn: HttpURLConnection = URL(release.apkUrl).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("User-Agent", "PocketForge")
        conn.setRequestProperty("Accept", "application/octet-stream")
        if (cfg.githubToken.isNotBlank() && release.apkUrl.contains("github.com")) {
            conn.setRequestProperty("Authorization", "Bearer ${cfg.githubToken.trim()}")
        }
        val code = conn.responseCode
        if (code !in 200..299) throw IllegalStateException("下载失败：HTTP $code")
        val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
        conn.inputStream.use { input ->
            tmp.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var read = 0L
                var lastPercent = -1
                while (true) {
                    val n = input.read(buffer)
                    if (n <= 0) break
                    output.write(buffer, 0, n)
                    read += n
                    val percent = if (total > 0) ((read * 100) / total).toInt() else -1
                    if (percent != lastPercent) {
                        lastPercent = percent
                        onProgress?.invoke(percent.coerceIn(0, 100), human(read, total))
                    }
                }
            }
        }
        conn.disconnect()
        if (target.exists()) target.delete()
        tmp.renameTo(target)
        return target
    }

    /** 校验：包名一致 + 签名摘要一致 */
    fun verify(file: File): String? {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags)
            ?: return "这不是有效的 APK"
        if (archive.packageName != context.packageName) {
            return "包名不一致：${archive.packageName}，拒绝安装"
        }
        val incoming = signatureDigest(archive)
            ?: return "读不到新包签名，拒绝安装"
        val installed = runCatching {
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(context.packageName, flags)
            signatureDigest(info)
        }.getOrNull() ?: return "读不到当前包签名，拒绝安装"
        if (incoming != installed) {
            return "签名不一致，拒绝安装（可能是被替换的包）"
        }
        return null
    }

    fun install(file: File): String {
        verify(file)?.let { return it }
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            "已拉起系统安装器。若被拦截，请在系统设置里允许「安装未知应用」。"
        } catch (t: Throwable) {
            "无法拉起安装器：${t.message}（可到 GitHub Release 页手动下载）"
        }
    }

    private fun signatureDigest(info: android.content.pm.PackageInfo): String? {
        val bytes: ByteArray? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            @Suppress("DEPRECATION")
            info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.firstOrNull()?.toByteArray()
        }
        return bytes?.let { sha256(it) }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun human(read: Long, total: Long): String {
        fun mb(v: Long) = String.format("%.1fMB", v / 1048576.0)
        return if (total > 0) "${mb(read)} / ${mb(total)}" else mb(read)
    }

    private fun compareVersion(a: String, b: String): Int {
        val pa = a.split('.', '-').mapNotNull { it.toIntOrNull() }
        val pb = b.split('.', '-').mapNotNull { it.toIntOrNull() }
        val size = maxOf(pa.size, pb.size)
        for (i in 0 until size) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        return 0
    }

    fun latestVersionCodeGuess(): Long = currentVersionCode()
}
