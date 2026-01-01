package com.zero.babycare

import android.app.Application
import android.content.Context
import com.zero.babycare.reminder.ReminderScheduler
import com.zero.common.util.LanguageManager

class App : Application() {
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
    }

    override fun onCreate() {
        super.onCreate()
        init()
    }

    private fun init() {
        // 应用语言设置需要尽早生效，避免首屏语言闪烁
        LanguageManager.applySavedLanguage()
        // 统一恢复提醒调度，避免重启后丢失
        ReminderScheduler.ensureScheduled(this)
    }
}
