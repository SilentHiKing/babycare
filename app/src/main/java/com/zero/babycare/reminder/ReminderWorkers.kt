package com.zero.babycare.reminder

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException

/** 轻量规划任务：读取最新数据库状态并生成每宝宝、每类型的一次性到期任务。 */
class ReminderPlanWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            ReminderScheduler.rebuildSchedule(applicationContext)
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

/** 到期任务：发送前复核当前策略，旧任务或低可信候选会安全退出。 */
class ReminderDueWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val babyId = inputData.getInt(ReminderScheduler.KEY_BABY_ID, -1)
        val type = inputData.getString(ReminderScheduler.KEY_REMINDER_TYPE)
            ?.let { runCatching { ReminderType.valueOf(it) }.getOrNull() }
            ?: return Result.failure()
        val expectedDue = inputData.getLong(ReminderScheduler.KEY_EXPECTED_DUE, -1L)
        if (babyId <= 0 || expectedDue <= 0L) return Result.failure()

        return try {
            val retry = ReminderDueHandler.run(
                deliver = { ReminderNotifier.notifyIfDue(applicationContext, babyId, type, expectedDue) },
                reschedule = { ReminderScheduler.ensureScheduled(applicationContext) }
            )
            if (retry) Result.retry() else Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
