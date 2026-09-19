package com.zero.babycare.reminder

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.Operation
import com.zero.babydata.domain.BabyDataHelper.repository
import com.zero.common.mmkv.MMKVKeys
import com.zero.common.mmkv.MMKVStore
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 提醒调度入口。
 *
 * WorkManager 持久化每个宝宝、每种记录类型的一次性任务；记录变化后重新规划，
 * 不再依赖固定两小时轮询，也不需要自行处理开机广播。
 */
object ReminderScheduler {

    internal const val TAG_REMINDER = "babycare_reminder"
    internal const val KEY_BABY_ID = "baby_id"
    internal const val KEY_REMINDER_TYPE = "reminder_type"
    internal const val KEY_EXPECTED_DUE = "expected_due"
    private const val PLAN_WORK_NAME = "babycare_reminder_plan"

    fun ensureScheduled(context: Context) {
        requestSchedule(context)
    }

    /** 导入善后必须等调度请求真正落入 WorkManager，异步失败时提交记录保持待处理。 */
    suspend fun ensureScheduledAndAwait(context: Context) {
        val result = requestSchedule(context).result
        suspendCancellableCoroutine<Unit> { continuation ->
            result.addListener({
                if (continuation.isActive) {
                    try {
                        result.get() // 完成监听内读取已完成 future，不阻塞线程。
                        continuation.resume(Unit)
                    } catch (error: Exception) {
                        continuation.resumeWithException(error.cause ?: error)
                    }
                }
            }, Executor { it.run() })
        }
    }

    private fun requestSchedule(context: Context): Operation {
        val appContext = context.applicationContext
        if (!MMKVStore.getBoolean(MMKVKeys.SETTINGS_REMINDER_ENABLED, false) ||
            !ReminderNotifier.canPostNotifications(appContext)) {
            ReminderNotifier.clearAll(appContext)
            return WorkManager.getInstance(appContext).cancelAllWorkByTag(TAG_REMINDER)
        }

        ReminderNotifier.clearLegacy(appContext)
        val request = OneTimeWorkRequest.Builder(ReminderPlanWorker::class.java)
            .addTag(TAG_REMINDER)
            .build()
        return WorkManager.getInstance(appContext).enqueueUniqueWork(
            PLAN_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun cancel(context: Context) {
        ReminderNotifier.clearAll(context.applicationContext)
        WorkManager.getInstance(context.applicationContext).cancelAllWorkByTag(TAG_REMINDER)
    }

    /** 根据数据库最新记录重建全部提醒任务。 */
    internal suspend fun rebuildSchedule(context: Context) {
        if (!MMKVStore.getBoolean(MMKVKeys.SETTINGS_REMINDER_ENABLED, false)) {
            cancel(context)
            return
        }

        ReminderNotifier.reconcileMissingBabies(context)
        val workManager = WorkManager.getInstance(context.applicationContext)
        repository.getAllBabyInfo().forEach { baby ->
            val candidates = ReminderPolicy.loadCandidates(baby)
            currentCoroutineContext().ensureActive()
            if (!MMKVStore.getBoolean(MMKVKeys.SETTINGS_REMINDER_ENABLED, false) ||
                !ReminderNotifier.canPostNotifications(context)) {
                cancel(context)
                return
            }
            ReminderType.entries.forEach { type ->
                val workName = dueWorkName(baby.babyId, type)
                val candidate = candidates[type]
                val now = System.currentTimeMillis()
                // 规划包含多次异步查询，前面计算出的候选到入队时也可能已经过期。
                if (candidate == null || candidate.isExpired(now)) {
                    workManager.cancelUniqueWork(workName)
                    return@forEach
                }

                val delay = (candidate.dueTime - now).coerceAtLeast(0L)
                val input = Data.Builder()
                    .putInt(KEY_BABY_ID, baby.babyId)
                    .putString(KEY_REMINDER_TYPE, type.name)
                    .putLong(KEY_EXPECTED_DUE, candidate.dueTime)
                    .build()
                val request = OneTimeWorkRequest.Builder(ReminderDueWorker::class.java)
                    .setInputData(input)
                    .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                    .addTag(TAG_REMINDER)
                    .build()
                workManager.enqueueUniqueWork(workName, ExistingWorkPolicy.REPLACE, request)
            }
        }
    }

    private fun dueWorkName(babyId: Int, type: ReminderType): String {
        return "babycare_reminder_due_${babyId}_${type.name.lowercase()}"
    }
}

enum class ReminderType {
    FEEDING,
    SLEEP
}
