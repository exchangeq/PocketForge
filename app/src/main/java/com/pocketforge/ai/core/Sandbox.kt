package com.pocketforge.ai.core

import android.content.Context
import android.os.Build
import android.os.Environment
import com.pocketforge.ai.data.AppConfig
import java.io.File
import java.io.IOException

/**
 * 文件系统边界（“防越界”第一道闸门）：
 * - 读取：允许 应用根目录 + 手机公共存储根目录（/storage/emulated/0）+ 应用私有目录
 * - 写入：只允许 PocketForge 工作区内部
 * - 禁止：系统分区、其他应用私有数据、Android/data 与 Android/obb
 */
class Sandbox(
    private val context: Context,
    private val configProvider: () -> AppConfig,
) {

    companion object {
        private val FORBIDDEN_SEGMENTS = listOf(
            "/system", "/system_ext", "/vendor", "/product", "/odm", "/apex",
            "/proc", "/sys", "/dev", "/data", "/cache", "/metadata",
            "/storage/emulated/0/Android/data", "/storage/emulated/0/Android/obb",
        )
        const val MAX_READ_BYTES = 512 * 1024
        const val MAX_WRITE_BYTES = 2 * 1024 * 1024
        const val MAX_LIST_ENTRIES = 800
    }

    fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else true

    fun defaultWorkspace(): File {
        return if (hasAllFilesAccess()) {
            File(Environment.getExternalStorageDirectory(), "PocketForge")
        } else {
            File(context.getExternalFilesDir(null) ?: context.filesDir, "PocketForge")
        }
    }

    fun workspace(): File {
        val configured = configProvider().workspaceRoot.trim()
        val root = if (configured.isEmpty()) defaultWorkspace() else File(configured)
        if (!root.exists()) root.mkdirs()
        return canonical(root)
    }

    fun projectsDir(): File {
        val dir = File(workspace(), "projects")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun readRoots(): List<File> = buildList {
        add(workspace())
        add(Environment.getExternalStorageDirectory())
        context.getExternalFilesDir(null)?.let { add(it) }
        add(context.filesDir)
    }.map { canonical(it) }

    /** 解析读取路径，越界抛 IllegalArgumentException */
    fun resolveRead(raw: String): File {
        val target = canonical(File(raw.trim()))
        assertNotForbidden(target)
        val allowed = readRoots().any { target.path.startsWith(it.path) }
        require(allowed) { "拒绝访问：$raw 不在可读范围内" }
        require(target.exists()) { "路径不存在：$raw" }
        require(target.canRead()) { "没有读取权限：$raw（请检查“所有文件访问”授权）" }
        return target
    }

    /** 解析写入路径，只允许工作区内 */
    fun resolveWrite(raw: String): File {
        val ws = workspace()
        val target = canonical(File(raw.trim()))
        assertNotForbidden(target)
        val allowed = target.path.startsWith(ws.path)
        require(allowed) { "越界拦截：只允许写入工作区 ${ws.path}，请求路径为 $raw" }
        return target
    }

    fun relative(file: File): String {
        val ws = workspace()
        return if (file.path.startsWith(ws.path)) {
            file.path.removePrefix(ws.path).trimStart('/').ifEmpty { "." }
        } else file.path
    }

    private fun assertNotForbidden(target: File) {
        val path = target.path
        val hit = FORBIDDEN_SEGMENTS.firstOrNull { path == it || path.startsWith("$it/") }
        require(hit == null) { "系统保护：禁止访问 $hit" }
    }

    private fun canonical(file: File): File = try {
        file.canonicalFile
    } catch (e: IOException) {
        file.absoluteFile
    }
}
