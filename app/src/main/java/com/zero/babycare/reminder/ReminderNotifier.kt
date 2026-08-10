package com.zero.babycare.reminder

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.zero.babycare.MainActivity
import com.zero.babycare.home.OngoingRecordManager
import com.zero.babycare.home.prediction.PredictionManager
import com.zero.babydata.domain.BabyDataHelper.repository
import com.zero.babydata.entity.BabyInfo
import com.zero.common.mmkv.MMKVKeys
import com.zero.common.mmkv.MMKVStore
import java.util.concurrent.TimeUnit

/** 到期任务执行时再次校验最新记录、权限、进行中状态与可信度，再决定是否通知。 */
object ReminderNotifier {

    const val EXTRA_BABY_ID = "reminder_baby_id"
    const val EXTRA_REMINDER_TYPE = "reminder_type"
    private const val CHANNEL_ID = "record_reminder"

    suspend fun notifyIfDue(
        context: Context,
        babyId: Int,
        type: ReminderType,
        expectedDue: Long
    ): Boolean {
        if (!MMKVStore.getBoolean(MMKVKeys.SETTINGS_REMINDER_ENABLED, false) ||
            !canPostNotifications(context)
        ) {
            return false
        }

        val baby = repository.getBabyInfo(babyId) ?: return false
        val candidate = ReminderPolicy.loadCandidates(baby)[type] ?: return false
        val now = System.currentTimeMillis()
        // 任务可能因记录变化而过期；必须以当前候选时间为准，不能沿用旧任务的 due。
        if (candidate.dueTime != expectedDue || now < candidate.dueTime) return false

        val posted = when (type) {
            ReminderType.FEEDING -> sendFeedingNotification(context, baby)
            ReminderType.SLEEP -> sendSleepNotification(context, baby)
        }
        if (posted) {
            MMKVStore.put(ReminderPolicy.lastReminderKey(type, babyId), now)
            MMKVStore.put(ReminderPolicy.notifiedSourceKey(type, babyId), candidate.sourceRecordTime)
        }
        return posted
    }

    private fun sendFeedingNotification(context: Context, baby: BabyInfo): Boolean {
        return postNotification(
            context = context,
            baby = baby,
            type = ReminderType.FEEDING,
            title = context.getString(com.zero.common.R.string.reminder_notification_title),
            content = context.getString(
                com.zero.common.R.string.reminder_feeding_content,
                baby.name.ifBlank { context.getString(com.zero.babycare.R.string.app_name) }
            )
        )
    }

    private fun sendSleepNotification(context: Context, baby: BabyInfo): Boolean {
        return postNotification(
            context = context,
            baby = baby,
            type = ReminderType.SLEEP,
            title = context.getString(com.zero.common.R.string.reminder_notification_title),
            content = context.getString(
                com.zero.common.R.string.reminder_sleep_content,
                baby.name.ifBlank { context.getString(com.zero.babycare.R.string.app_name) }
            )
        )
    }

    @SuppressLint("MissingPermission")
    private fun postNotification(
        context: Context,
        baby: BabyInfo,
        type: ReminderType,
        title: String,
        content: String
    ): Boolean {
        createChannelIfNeeded(context)
        val notificationId = 21_000 + ((baby.babyId.coerceAtLeast(0) % 10_000) * 2) + type.ordinal
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_BABY_ID, baby.babyId)
            putExtra(EXTRA_REMINDER_TYPE, type.name)
        }
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

        // 权限在调用前已经显式复核；这里仍捕获系统状态竞态，失败时不更新已提醒来源。
        return runCatching {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
            true
        }.getOrDefault(false)
    }

    private fun createChannelIfNeeded(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(com.zero.common.R.string.reminder_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(com.zero.common.R.string.reminder_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    private fun canPostNotifications(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}

/** 预测只提供候选时间；提醒策略在此决定可信度、去重、冷却与进行中状态。 */
internal object ReminderPolicy {

    private const val PREDICTION_RECORD_COUNT = 15
    private const val MIN_RELEVANT_RECORDS = 3
    private const val MIN_CONFIDENCE = 0.6f
    private val cooldownMillis = TimeUnit.HOURS.toMillis(2)
    private val maxPredictionHorizonMillis = TimeUnit.HOURS.toMillis(24)

    suspend fun loadCandidates(baby: BabyInfo): Map<ReminderType, ReminderCandidate> {
        val feedings = repository.getRecentFeedings(baby.babyId, PREDICTION_RECORD_COUNT)
        val sleeps = repository.getRecentSleeps(baby.babyId, PREDICTION_RECORD_COUNT)
        val ageMonths = calculateAgeMonths(baby.birthDate)
        val result = mutableMapOf<ReminderType, ReminderCandidate>()

        if (!OngoingRecordManager.isFeeding(baby.babyId) && feedings.size >= MIN_RELEVANT_RECORDS) {
            val lastEnd = feedings.maxOfOrNull { it.feedingEnd } ?: 0L
            val prediction = PredictionManager.predictNextFeeding(ageMonths, feedings, sleeps)
            buildCandidate(baby.babyId, ReminderType.FEEDING, lastEnd, prediction?.latestTime?.time, prediction?.confidence)
                ?.let { result[ReminderType.FEEDING] = it }
        }

        if (!OngoingRecordManager.isSleeping(baby.babyId) && sleeps.size >= MIN_RELEVANT_RECORDS) {
            val lastEnd = sleeps.maxOfOrNull { it.sleepEnd } ?: 0L
            val prediction = PredictionManager.predictNextSleep(ageMonths, sleeps, feedings)
            buildCandidate(baby.babyId, ReminderType.SLEEP, lastEnd, prediction?.latestTime?.time, prediction?.confidence)
                ?.let { result[ReminderType.SLEEP] = it }
        }
        return result
    }

    fun lastReminderKey(type: ReminderType, babyId: Int): String {
        val prefix = when (type) {
            ReminderType.FEEDING -> MMKVKeys.SETTINGS_FEEDING_REMINDER_LAST_PREFIX
            ReminderType.SLEEP -> MMKVKeys.SETTINGS_SLEEP_REMINDER_LAST_PREFIX
        }
        return prefix + babyId
    }

    fun notifiedSourceKey(type: ReminderType, babyId: Int): String {
        val prefix = when (type) {
            ReminderType.FEEDING -> MMKVKeys.SETTINGS_FEEDING_REMINDER_SOURCE_PREFIX
            ReminderType.SLEEP -> MMKVKeys.SETTINGS_SLEEP_REMINDER_SOURCE_PREFIX
        }
        return prefix + babyId
    }

    private fun buildCandidate(
        babyId: Int,
        type: ReminderType,
        lastRecordEnd: Long,
        predictedLatest: Long?,
        confidence: Float?
    ): ReminderCandidate? {
        if (lastRecordEnd <= 0L || confidence == null || confidence < MIN_CONFIDENCE) return null
        if (MMKVStore.getLong(notifiedSourceKey(type, babyId), 0L) == lastRecordEnd) return null
        val prediction = predictedLatest?.takeIf {
            it > lastRecordEnd && it - lastRecordEnd <= maxPredictionHorizonMillis
        } ?: return null
        val cooldownEnd = MMKVStore.getLong(lastReminderKey(type, babyId), 0L) + cooldownMillis
        return ReminderCandidate(
            dueTime = maxOf(prediction, cooldownEnd),
            sourceRecordTime = lastRecordEnd
        )
    }

    private fun calculateAgeMonths(birthDate: Long): Int {
        if (birthDate <= 0L) return 0
        val days = TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - birthDate)
        return (days / 30).toInt().coerceAtLeast(0)
    }
}

internal data class ReminderCandidate(
    val dueTime: Long,
    val sourceRecordTime: Long
)
