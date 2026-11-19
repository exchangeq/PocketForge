package com.pocketforge.ai

import android.app.Application
import com.pocketforge.ai.agent.AgentEngine
import com.pocketforge.ai.data.ConfigRepository

/**
 * 进程级单例：引擎挂在 Application 上，不随 Activity 重建/切页销毁。
 * 这样切 tab、转屏、锁屏、Activity 被回收，正在跑的任务都还在同一个进程里继续。
 */
class PocketForgeApp : Application() {

    val configRepo: ConfigRepository by lazy { ConfigRepository(this) }

    val engine: AgentEngine by lazy { AgentEngine(this, configRepo) }
}
