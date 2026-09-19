package com.zero.babycare.reminder

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.blankj.utilcode.util.LogUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** 后台系统设置改变后重建提醒；仅提交持久工作，不在广播线程读取业务记录或发布通知。 */
class ReminderSystemReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val relevant = when (intent.action) {
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
            NotificationManager.ACTION_APP_BLOCK_STATE_CHANGED -> true
            NotificationManager.ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED ->
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                    intent.getStringExtra(NotificationManager.EXTRA_NOTIFICATION_CHANNEL_ID) == ReminderNotifier.CHANNEL_ID
            else -> false
        }
        if (!relevant) return

        // 不信任广播附带的开关值：现有入口会重新读取应用偏好、系统权限和提醒渠道。
        // goAsync 保持进程到 WorkManager 真正接受请求；超时后仍由前台/后续系统变化兜底。
        val pending = goAsync()
        val appContext = context.applicationContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                withTimeout(8_000L) { ReminderScheduler.ensureScheduledAndAwait(appContext) }
            } catch (_: TimeoutCancellationException) {
                LogUtils.w("系统变化后等待提醒规划结果超时")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LogUtils.w("系统变化后更新提醒失败：${error.javaClass.simpleName}")
            } finally {
                pending.finish()
                scope.cancel()
            }
        }
    }
}
