package com.zero.babycare.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.zero.common.mmkv.MMKVKeys
import com.zero.common.mmkv.MMKVStore
import java.util.concurrent.TimeUnit

/**
 * 提醒调度器
 * 使用 AlarmManager 周期唤醒，触发后台检查并推送提醒。
 */
object ReminderScheduler {

    private const val REQUEST_CODE = 12001
    private val intervalMillis = TimeUnit.HOURS.toMillis(2)
    private val initialDelayMillis = TimeUnit.MINUTES.toMillis(10)

    /**
     * 根据开关状态确保调度存在
     */
    fun ensureScheduled(context: Context) {
        if (MMKVStore.getBoolean(MMKVKeys.SETTINGS_REMINDER_ENABLED, true)) {
            schedule(context)
        } else {
            cancel(context)
        }
    }

    /**
     * 启动提醒调度
     */
    fun schedule(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pendingIntent = buildPendingIntent(context)
        val triggerAt = System.currentTimeMillis() + initialDelayMillis
        alarmManager.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            triggerAt,
            intervalMillis,
            pendingIntent
        )
    }

    /**
     * 取消提醒调度
     */
    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        alarmManager.cancel(buildPendingIntent(context))
    }

    private fun buildPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }
}
