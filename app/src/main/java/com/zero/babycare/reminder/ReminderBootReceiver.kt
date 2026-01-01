package com.zero.babycare.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机后恢复提醒调度
 */
class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            ReminderScheduler.ensureScheduled(context)
        }
    }
}
