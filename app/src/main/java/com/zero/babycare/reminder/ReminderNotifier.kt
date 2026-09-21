package com.zero.babycare.reminder

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import com.zero.common.ext.getAppString
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.zero.babycare.MainActivity
import com.zero.babycare.home.OngoingRecordManager
import com.zero.babycare.home.prediction.PredictionManager
import com.zero.babycare.home.prediction.PredictionRecordSelector
import com.zero.babycare.home.prediction.PredictionResult
import com.zero.babydata.domain.BabyDataHelper.repository
import com.zero.babydata.entity.BabyInfo
import com.zero.common.mmkv.MMKVKeys
import com.zero.common.mmkv.MMKVStore
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** 到期任务执行时再次校验最新记录、权限、进行中状态与可信度，再决定是否通知。 */
object ReminderNotifier {

    const val EXTRA_BABY_ID = "reminder_baby_id"
    const val EXTRA_BABY_STABLE_ID = "reminder_baby_stable_id"
    const val EXTRA_REMINDER_TYPE = "reminder_type"
    internal const val CHANNEL_ID = "record_reminder"
    private const val TAG_PREFIX = "babycare_reminder:"
    private val deliveryGate = ReminderDeliveryGate()

    internal suspend fun notifyIfDue(
        context: Context,
        babyId: Int,
        type: ReminderType,
        expectedDue: Long,
        source: ReminderDataSource = RepositoryReminderDataSource,
        clock: () -> Long = System::currentTimeMillis,
        ongoingCheck: suspend (Int, ReminderType) -> Boolean = ::isOngoing
    ): ReminderDeliveryResult {
        val ticket = deliveryGate.capture(babyId, type)
        if (!MMKVStore.getBoolean(MMKVKeys.SETTINGS_REMINDER_ENABLED, false) ||
            !canPostNotifications(context)
        ) {
            return ReminderDeliveryResult.SUPPRESSED
        }

        val baby = source.getBaby(babyId) ?: run {
            currentCoroutineContext().ensureActive()
            // 删除可能已提交但来不及撤通知；旧到期任务也负责补齐这一派生状态。
            deliveryGate.ifCurrent(ticket) { clearForBaby(context, babyId) }
            return ReminderDeliveryResult.SUPPRESSED
        }
        val candidate = source.getCandidate(baby, type) ?: return ReminderDeliveryResult.SUPPRESSED
        val coroutineContext = currentCoroutineContext()
        coroutineContext.ensureActive()
        val now = clock()
        // 候选查询可能跨过预测区间；过期属于终止，不能重新创建零延迟任务。
        if (candidate.isExpired(now)) return ReminderDeliveryResult.SUPPRESSED
        // 任务可能因记录变化而过期；必须以当前候选时间为准，不能沿用旧任务的 due。
        if (candidate.dueTime != expectedDue || now < candidate.dueTime) {
            return ReminderDeliveryResult.RESCHEDULE
        }

        // Room 查询允许挂起，不能占用通知失效锁；开始/操作计时会先使 ticket 失效。
        val ongoing = ongoingCheck(babyId, type)
        coroutineContext.ensureActive()
        return deliveryGate.ifCurrent(ticket) {
            coroutineContext.ensureActive()
            // 查询会挂起；开关、渠道或计时状态可能已改变，必须在真正发布前复核。
            if (!MMKVStore.getBoolean(MMKVKeys.SETTINGS_REMINDER_ENABLED, false) ||
                !canPostNotifications(context) || ongoing ||
                MMKVStore.getLong(ReminderPolicy.notifiedSourceKey(type, babyId), 0L) == candidate.sourceRecordTime
            ) return@ifCurrent ReminderDeliveryResult.SUPPRESSED

            // 系统权限查询和会话读取也可能耗时；最终时间校验放在这些检查之后。
            val postingTime = clock()
            if (candidate.isExpired(postingTime)) return@ifCurrent ReminderDeliveryResult.SUPPRESSED
            if (postingTime < candidate.dueTime) return@ifCurrent ReminderDeliveryResult.RESCHEDULE

            val posted = when (type) {
                ReminderType.FEEDING -> sendFeedingNotification(context, baby)
                ReminderType.SLEEP -> sendSleepNotification(context, baby)
            }
            if (posted) {
                MMKVStore.put(ReminderPolicy.lastReminderKey(type, babyId), postingTime)
                MMKVStore.put(ReminderPolicy.notifiedSourceKey(type, babyId), candidate.sourceRecordTime)
            }
            if (posted) ReminderDeliveryResult.DELIVERED else ReminderDeliveryResult.RETRY
        } ?: ReminderDeliveryResult.SUPPRESSED
    }

    private suspend fun isOngoing(babyId: Int, type: ReminderType): Boolean = when (type) {
        ReminderType.FEEDING -> OngoingRecordManager.isFeeding(babyId)
        ReminderType.SLEEP -> OngoingRecordManager.isSleeping(babyId)
    }

    /** 开关关闭或权限丢失时既撤后台任务，也撤掉已显示的提醒；不影响其他通知渠道。 */
    internal fun clearAll(context: Context) = deliveryGate.invalidate {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.activeNotifications.filter { notification ->
            notification.tag?.startsWith(TAG_PREFIX) == true ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notification.notification.channelId == CHANNEL_ID) ||
                (notification.tag == null && notification.id in 21_000..40_999)
        }.forEach { manager.cancel(it.tag, it.id) }
    }

    /** 记录变更、开始计时或宝宝删除后，旧提示及查询中的同对象候选同时失效。 */
    internal fun clearForBaby(context: Context, babyId: Int, type: ReminderType? = null) =
        deliveryGate.invalidate(babyId, type) {
            val manager = NotificationManagerCompat.from(context)
            (type?.let(::listOf) ?: ReminderType.entries).forEach { recordType ->
                manager.cancel(notificationTag(babyId), recordType.ordinal)
            }
        }

    /** 冷恢复按 Room 核对已显示通知，补齐删除提交后退出留下的系统状态。 */
    internal suspend fun reconcileMissingBabies(
        context: Context,
        source: ReminderDataSource = RepositoryReminderDataSource
    ) = withContext(Dispatchers.IO) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // 先枚举通知再查库，不用较早的宝宝全集误删查询期间新建宝宝的有效通知。
        val displayedBabyIds = manager.activeNotifications.mapNotNull { notification ->
            notification.tag?.takeIf { it.startsWith(TAG_PREFIX) }
                ?.removePrefix(TAG_PREFIX)?.toIntOrNull()?.takeIf { it > 0 }
        }.toSet()
        displayedBabyIds.forEach { babyId ->
            if (source.getBaby(babyId) == null) {
                currentCoroutineContext().ensureActive()
                // 与最终发布共用 gate；查询期间取得旧候选的任务不能在撤回后补发。
                clearForBaby(context, babyId)
            }
        }
    }

    /** 旧通知的截断 ID 无法可靠还原宝宝归属，升级后在重新规划前统一撤回。 */
    internal fun clearLegacy(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.activeNotifications.filter { it.tag == null && it.id in 21_000..40_999 }
            .forEach { manager.cancel(it.id) }
    }

    private fun notificationTag(babyId: Int) = "$TAG_PREFIX$babyId"

    private fun sendFeedingNotification(context: Context, baby: BabyInfo): Boolean {
        return postNotification(
            context = context,
            baby = baby,
            type = ReminderType.FEEDING,
            title = context.getAppString(com.zero.common.R.string.reminder_notification_title),
            content = context.getAppString(
                com.zero.common.R.string.reminder_feeding_content,
                baby.name.ifBlank { context.getAppString(com.zero.babycare.R.string.app_name) }
            )
        )
    }

    private fun sendSleepNotification(context: Context, baby: BabyInfo): Boolean {
        return postNotification(
            context = context,
            baby = baby,
            type = ReminderType.SLEEP,
            title = context.getAppString(com.zero.common.R.string.reminder_notification_title),
            content = context.getAppString(
                com.zero.common.R.string.reminder_sleep_content,
                baby.name.ifBlank { context.getAppString(com.zero.babycare.R.string.app_name) }
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
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            // Intent extras 不参与 PendingIntent 身份匹配；URI 保证不同宝宝、类型互不覆盖。
            data = Uri.Builder().scheme("babycare").authority("reminder")
                .appendPath(baby.stableId).appendPath(baby.babyId.toString()).appendPath(type.name).build()
            putExtra(EXTRA_BABY_ID, baby.babyId)
            putExtra(EXTRA_BABY_STABLE_ID, baby.stableId)
            putExtra(EXTRA_REMINDER_TYPE, type.name)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
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
            NotificationManagerCompat.from(context).notify(notificationTag(baby.babyId), type.ordinal, notification)
            true
        }.getOrDefault(false)
    }

    private fun createChannelIfNeeded(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getAppString(com.zero.common.R.string.reminder_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getAppString(com.zero.common.R.string.reminder_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    internal fun canPostNotifications(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return false
            // 应用总开关开启时，用户仍可单独关闭提醒渠道；notify 不会为此抛异常。
            val channel = manager.getNotificationChannel(CHANNEL_ID)
            if (channel?.importance == NotificationManager.IMPORTANCE_NONE) return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && channel?.group != null &&
                manager.getNotificationChannelGroup(channel.group)?.isBlocked == true
            ) return false
        }
        return true
    }
}

/** 预测只提供候选时间；提醒策略在此决定可信度、去重、冷却与进行中状态。 */
internal object ReminderPolicy {

    private const val PREDICTION_RECORD_COUNT = 15
    private const val MIN_RELEVANT_RECORDS = 3

    suspend fun loadCandidates(baby: BabyInfo): Map<ReminderType, ReminderCandidate> {
        // 候选数量和去重来源必须与预测采用同一组记录，辅食不能重置喂奶提醒来源。
        val feedings = PredictionRecordSelector.completedMilkFeedings(
            repository.getRecentCompletedMilkFeedings(baby.babyId, PREDICTION_RECORD_COUNT)
        )
        val sleeps = PredictionRecordSelector.completedSleeps(
            repository.getRecentCompletedSleeps(baby.babyId, PREDICTION_RECORD_COUNT)
        )
        val ageMonths = com.zero.babycare.home.prediction.PredictionAgePolicy.ageMonths(baby.birthDate)
        val result = mutableMapOf<ReminderType, ReminderCandidate>()

        if (!OngoingRecordManager.isFeeding(baby.babyId) && feedings.size >= MIN_RELEVANT_RECORDS) {
            val lastEnd = feedings.maxOfOrNull(PredictionRecordSelector::feedingEnd) ?: 0L
            val prediction = PredictionManager.predictNextFeeding(ageMonths, feedings, sleeps)
            buildCandidate(baby.babyId, ReminderType.FEEDING, lastEnd, prediction)
                ?.let { result[ReminderType.FEEDING] = it }
        }

        if (!OngoingRecordManager.isSleeping(baby.babyId) && sleeps.size >= MIN_RELEVANT_RECORDS) {
            val lastEnd = sleeps.maxOfOrNull(PredictionRecordSelector::sleepEnd) ?: 0L
            val prediction = PredictionManager.predictNextSleep(ageMonths, sleeps, feedings)
            buildCandidate(baby.babyId, ReminderType.SLEEP, lastEnd, prediction)
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
        prediction: PredictionResult?
    ): ReminderCandidate? {
        return ReminderCandidatePolicy.build(
            lastRecordEnd = lastRecordEnd,
            prediction = prediction,
            lastRemindedAt = MMKVStore.getLong(lastReminderKey(type, babyId), 0L),
            notifiedSourceRecordTime = MMKVStore.getLong(notifiedSourceKey(type, babyId), 0L),
            now = System.currentTimeMillis()
        )
    }

}
