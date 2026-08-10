package com.zero.babycare.reminder

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.zero.babydata.domain.BabyDataHelper.repository
import com.zero.common.mmkv.MMKVKeys
import com.zero.common.mmkv.MMKVStore
import java.util.concurrent.TimeUnit

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
        val appContext = context.applicationContext
        if (!MMKVStore.getBoolean(MMKVKeys.SETTINGS_REMINDER_ENABLED, false)) {
            cancel(appContext)
            return
        }

        val request = OneTimeWorkRequest.Builder(ReminderPlanWorker::class.java)
            .addTag(TAG_REMINDER)
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            PLAN_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelAllWorkByTag(TAG_REMINDER)
    }

    /** 根据数据库最新记录重建全部提醒任务。 */
    internal suspend fun rebuildSchedule(context: Context) {
        if (!MMKVStore.getBoolean(MMKVKeys.SETTINGS_REMINDER_ENABLED, false)) {
            cancel(context)
            return
        }

        val workManager = WorkManager.getInstance(context.applicationContext)
        repository.getAllBabyInfo().forEach { baby ->
            val candidates = ReminderPolicy.loadCandidates(baby)
            ReminderType.entries.forEach { type ->
                val workName = dueWorkName(baby.babyId, type)
                val candidate = candidates[type]
                if (candidate == null) {
                    workManager.cancelUniqueWork(workName)
                    return@forEach
                }

                val delay = (candidate.dueTime - System.currentTimeMillis()).coerceAtLeast(0L)
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
