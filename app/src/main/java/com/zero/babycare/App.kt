package com.zero.babycare

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import com.zero.babycare.reminder.ReminderScheduler
import com.zero.common.util.DeviceUtils
import com.zero.common.util.LanguageManager
import me.jessyan.autosize.AutoSizeConfig

class App : Application() {
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
    }

    override fun onCreate() {
        super.onCreate()
        configAutoSize()
        init()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 系统窗口配置变化后重新计算适配基线。
        configAutoSize()
    }

    /**
     * 紧凑窗口沿用手机设计基线，中大窗口按实际宽度布局并由页面 maxWidth 控制内容列。
     */
    private fun configAutoSize() {
        val designWidth = DeviceUtils.getDesignWidthInDp(this)
        AutoSizeConfig.getInstance().setDesignWidthInDp(designWidth)
    }

    private fun init() {
        // 应用语言设置需要尽早生效，避免首屏语言闪烁
        LanguageManager.applySavedLanguage()
        // 统一恢复提醒调度，避免重启后丢失
        ReminderScheduler.ensureScheduled(this)
    }
}
