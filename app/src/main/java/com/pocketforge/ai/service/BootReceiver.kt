package com.pocketforge.ai.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 开机 / 应用更新后，如果用户开过后台常驻，就把服务拉起来 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (!KeepAliveService.isEnabled(context)) return
        runCatching { KeepAliveService.start(context) }
    }
}
