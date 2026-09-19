package com.zero.common.util

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import com.blankj.utilcode.util.LogUtils
import java.util.UUID

/** 启动标识只在后台读一次；计时热路径只读取系统时钟与内存缓存。 */
object TimerClock {
    private val processId = UUID.randomUUID().toString()
    @Volatile private var bootCount: Int? = null

    fun initialize(context: Context) {
        check(Looper.myLooper() != Looper.getMainLooper())
        bootCount = try {
            Settings.Global.getInt(context.applicationContext.contentResolver, Settings.Global.BOOT_COUNT)
                .takeIf { it >= 0 }
        } catch (_: Exception) {
            LogUtils.w("启动时钟标识读取失败，跨进程计时使用兼容恢复")
            null
        }
    }

    fun snapshot(): TimerClockSnapshot = TimerClockSnapshot(
        wallTimeMs = System.currentTimeMillis(),
        elapsedRealtimeMs = SystemClock.elapsedRealtime(),
        bootCount = bootCount,
        processId = processId
    )
}
