package com.pocketforge.ai.core

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 崩溃与异常落盘：出问题能回看栈，不用连电脑抓 logcat */
object CrashLogger {

    private var dir: File? = null

    fun install(context: Context) {
        dir = File(context.filesDir, "logs").apply { mkdirs() }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { write("crash", thread.name + "\n" + throwable.stackTraceToString()) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun write(tag: String, text: String) {
        runCatching {
            val d = dir ?: return
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            File(d, "$tag-$stamp.txt").writeText(text)
            // 只留最近 20 份，别把存储吃满
            d.listFiles()?.sortedByDescending { it.lastModified() }?.drop(20)?.forEach { it.delete() }
        }
    }

    fun summary(): String {
        val files = dir?.listFiles()?.sortedByDescending { it.lastModified() } ?: return "无日志"
        if (files.isEmpty()) return "无异常记录"
        val latest = files.first()
        return "最近 ${files.size} 条，最新 ${latest.name}"
    }

    fun latest(): String {
        val f = dir?.listFiles()?.sortedByDescending { it.lastModified() }?.firstOrNull() ?: return "没有日志"
        return runCatching { f.readText().take(4000) }.getOrDefault("读取失败")
    }

    fun clear(): String {
        val files = dir?.listFiles() ?: return "无日志"
        files.forEach { it.delete() }
        return "已清空 ${files.size} 条日志"
    }
}
