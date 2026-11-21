package com.pocketforge.ai.update

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent

/**
 * 与 LM Downloader（com.lm.downloader / .beta）对接：
 * 检测到包名就把链接塞进剪贴板并拉起它，由它接管下载任务。
 */
object DownloaderBridge {

    val PACKAGES = listOf("com.lm.downloader", "com.lm.downloader.beta")

    fun installedPackage(context: Context): String? = PACKAGES.firstOrNull { pkg ->
        runCatching {
            context.packageManager.getPackageInfo(pkg, 0)
            true
        }.getOrDefault(false) || context.packageManager.getLaunchIntentForPackage(pkg) != null
    }

    fun handoff(context: Context, url: String, filename: String): String {
        val pkg = installedPackage(context) ?: return "未检测到 LM 下载器（com.lm.downloader）"
        runCatching {
            context.getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText("PocketForge", url))
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
            setPackage(pkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val sent = runCatching { context.startActivity(send); true }.getOrDefault(false)
        if (!sent) {
            val launch = context.packageManager.getLaunchIntentForPackage(pkg)
                ?: return "下载器没有可启动的入口"
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launch)
        }
        return "已把「$filename」交给 $pkg：打开下载器确认任务即可（链接也放进了剪贴板）"
    }
}
