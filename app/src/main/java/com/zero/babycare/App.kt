package com.zero.babycare

import android.app.Application
import android.content.Context
import com.zero.babycare.reminder.ReminderScheduler
import com.zero.common.util.LanguageManager
import com.zero.common.util.TimerClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class App : Application() {
    // 仅服务这一次启动读取，完成后释放，不在主线程访问系统设置 Provider。
    private val clockInitializationScope = CoroutineScope(Dispatchers.IO)
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
    }

    override fun onCreate() {
        super.onCreate()
        init()
    }

    private fun init() {
        clockInitializationScope.launch {
            try { TimerClock.initialize(this@App) } finally { clockInitializationScope.cancel() }
        }
        // 应用语言设置需要尽早生效，避免首屏语言闪烁
        LanguageManager.applySavedLanguage()
        // 统一恢复提醒调度，避免重启后丢失
        ReminderScheduler.ensureScheduled(this)
    }
}
