package com.zero.babycare.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.blankj.utilcode.util.StringUtils
import com.zero.babycare.MainActivity
import com.zero.babycare.home.OngoingRecordManager
import com.zero.babycare.home.prediction.PredictionManager
import com.zero.babydata.domain.BabyDataHelper.repository
import com.zero.babydata.entity.BabyInfo
import com.zero.common.mmkv.MMKVKeys
import com.zero.common.mmkv.MMKVStore
import java.util.concurrent.TimeUnit

/**
 * 提醒通知
 * 基于最近记录和预测结果决定是否推送喂养/睡眠提醒。
 */
object ReminderNotifier {

    private const val CHANNEL_ID = "record_reminder"
    private const val FEEDING_NOTIFICATION_ID = 21001
    private const val SLEEP_NOTIFICATION_ID = 21002
    private const val PREDICTION_RECORD_COUNT = 15
    private val reminderCooldownMillis = TimeUnit.HOURS.toMillis(2)
    private val feedingFallbackMillis = TimeUnit.HOURS.toMillis(3)
    private val sleepFallbackMillis = TimeUnit.HOURS.toMillis(4)

    /**
     * 检查并发送提醒
     */
    fun checkAndNotify(context: Context) {
        if (!MMKVStore.getBoolean(MMKVKeys.SETTINGS_REMINDER_ENABLED, true)) {
            return
        }
        if (!canPostNotifications(context)) {
            return
        }

        val babyInfo = getTargetBabyInfo() ?: return
        val now = System.currentTimeMillis()

        val recentFeedings = repository.getRecentFeedings(babyInfo.babyId, PREDICTION_RECORD_COUNT)
        val recentSleeps = repository.getRecentSleeps(babyInfo.babyId, PREDICTION_RECORD_COUNT)

        if (!OngoingRecordManager.isFeeding(babyInfo.babyId)) {
            val feedingPrediction = PredictionManager.predictNextFeeding(
                babyAgeMonths = calculateAgeMonths(babyInfo.birthDate),
                feedingRecords = recentFeedings,
                sleepRecords = recentSleeps
            )
            val shouldNotifyFeeding = shouldNotify(
                now = now,
                lastReminderKey = MMKVKeys.SETTINGS_FEEDING_REMINDER_LAST_PREFIX + babyInfo.babyId,
                predictedLatestTime = feedingPrediction?.latestTime?.time,
                lastRecordEndTime = recentFeedings.firstOrNull()?.feedingEnd,
                fallbackMillis = feedingFallbackMillis
            )
            if (shouldNotifyFeeding) {
                sendFeedingNotification(context, babyInfo)
            }
        }

        if (!OngoingRecordManager.isSleeping(babyInfo.babyId)) {
            val sleepPrediction = PredictionManager.predictNextSleep(
                babyAgeMonths = calculateAgeMonths(babyInfo.birthDate),
                sleepRecords = recentSleeps,
                feedingRecords = recentFeedings
            )
            val shouldNotifySleep = shouldNotify(
                now = now,
                lastReminderKey = MMKVKeys.SETTINGS_SLEEP_REMINDER_LAST_PREFIX + babyInfo.babyId,
                predictedLatestTime = sleepPrediction?.latestTime?.time,
                lastRecordEndTime = recentSleeps.firstOrNull()?.sleepEnd,
                fallbackMillis = sleepFallbackMillis
            )
            if (shouldNotifySleep) {
                sendSleepNotification(context, babyInfo)
            }
        }
    }

    private fun shouldNotify(
        now: Long,
        lastReminderKey: String,
        predictedLatestTime: Long?,
        lastRecordEndTime: Long?,
        fallbackMillis: Long
    ): Boolean {
        val lastReminderTime = MMKVStore.getLong(lastReminderKey, 0L)
        if (now - lastReminderTime < reminderCooldownMillis) {
            return false
        }

        val validLastEndTime = lastRecordEndTime?.takeIf { it > 0L }
        val validPredictedTime = predictedLatestTime
            ?.takeIf { it > 0L }
            // 预测时间早于上次结束，视为无效，避免误触发提醒
            ?.takeIf { predicted -> validLastEndTime == null || predicted > validLastEndTime }

        val dueTime = validPredictedTime
            ?: validLastEndTime?.let { it + fallbackMillis }
            ?: return false

        return now >= dueTime
    }

    private fun sendFeedingNotification(context: Context, babyInfo: BabyInfo) {
        val title = StringUtils.getString(com.zero.common.R.string.reminder_notification_title)
        val content = StringUtils.getString(
            com.zero.common.R.string.reminder_feeding_content,
            babyInfo.name.ifBlank { context.getString(com.zero.babycare.R.string.app_name) }
        )
        notify(
            context = context,
            notificationId = FEEDING_NOTIFICATION_ID,
            title = title,
            content = content,
            lastReminderKey = MMKVKeys.SETTINGS_FEEDING_REMINDER_LAST_PREFIX + babyInfo.babyId
        )
    }

    private fun sendSleepNotification(context: Context, babyInfo: BabyInfo) {
        val title = StringUtils.getString(com.zero.common.R.string.reminder_notification_title)
        val content = StringUtils.getString(
            com.zero.common.R.string.reminder_sleep_content,
            babyInfo.name.ifBlank { context.getString(com.zero.babycare.R.string.app_name) }
        )
        notify(
            context = context,
            notificationId = SLEEP_NOTIFICATION_ID,
            title = title,
            content = content,
            lastReminderKey = MMKVKeys.SETTINGS_SLEEP_REMINDER_LAST_PREFIX + babyInfo.babyId
        )
    }

    private fun notify(
        context: Context,
        notificationId: Int,
        title: String,
        content: String,
        lastReminderKey: String
    ) {
        createChannelIfNeeded(context)

        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(com.zero.common.R.drawable.ic_baby_default)
            .setContentTitle(title)
            .setContentText(content)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(context).notify(notificationId, notification)
        MMKVStore.put(lastReminderKey, System.currentTimeMillis())
    }

    private fun createChannelIfNeeded(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            StringUtils.getString(com.zero.common.R.string.reminder_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = StringUtils.getString(com.zero.common.R.string.reminder_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    private fun canPostNotifications(context: Context): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) {
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = android.Manifest.permission.POST_NOTIFICATIONS
            return ContextCompat.checkSelfPermission(context, permission) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    private fun getTargetBabyInfo(): BabyInfo? {
        val cached = MMKVStore.get(MMKVKeys.BABY_INFO, BabyInfo::class.java)
        if (cached != null) {
            // 校验缓存是否仍然存在，避免删除后仍触发提醒
            val existsInDb = repository.getAllBabyInfo().any { it.babyId == cached.babyId }
            if (existsInDb) {
                return cached
            }
            MMKVStore.remove(MMKVKeys.BABY_INFO)
        }

        // 回退为数据库中的第一个宝宝，并写回缓存
        return repository.getAllBabyInfo().firstOrNull()?.also {
            MMKVStore.put(MMKVKeys.BABY_INFO, it)
        }
    }

    /**
     * 计算宝宝月龄（用于预测）
     */
    private fun calculateAgeMonths(birthDate: Long): Int {
        if (birthDate <= 0) return 0
        val days = TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - birthDate)
        return (days / 30).toInt().coerceAtLeast(0)
    }
}
