package com.zero.babycare.settings.backup

import android.app.Application
import com.blankj.utilcode.util.LogUtils
import com.zero.babycare.home.BabyRecordChangeBus
import com.zero.babycare.home.OngoingRecordManager
import com.zero.babycare.reminder.ReminderScheduler
import com.zero.babydata.backup.BackupImportJournal
import com.zero.babydata.backup.CommittedBackupImport
import com.zero.babydata.room.BabyDatabase
import com.zero.common.mmkv.MMKVKeys
import com.zero.common.mmkv.MMKVStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 由前台 ViewModel 生命周期调用；不创建全局协程，不恢复任何导入指令。 */
object BackupImportRecovery {
    suspend fun recoverPending(application: Application) = withContext(Dispatchers.IO) {
        BackupImportCommitCoordinator(
            BackupImportJournal(BabyDatabase.getInstance(application)),
            ApplicationBackupImportSideEffects(application)
        ).recoverPending()
    }
}

internal interface BackupImportSideEffects {
    suspend fun clearReplacedBabyState(babyIds: List<Int>)
    suspend fun refreshRecords()
}

/** 善后只针对事务中被替换的旧 ID；自动生成的新 ID 和新会话不受影响。 */
internal class ApplicationBackupImportSideEffects(private val app: Application) : BackupImportSideEffects {
    override suspend fun clearReplacedBabyState(babyIds: List<Int>) {
        babyIds.forEach { babyId ->
            OngoingRecordManager.clearBaby(babyId)
            listOf(MMKVKeys.SETTINGS_FEEDING_REMINDER_LAST_PREFIX, MMKVKeys.SETTINGS_SLEEP_REMINDER_LAST_PREFIX,
                MMKVKeys.SETTINGS_FEEDING_REMINDER_SOURCE_PREFIX, MMKVKeys.SETTINGS_SLEEP_REMINDER_SOURCE_PREFIX
            ).forEach { prefix -> MMKVStore.remove(prefix + babyId) }
        }
        // 当前选择由 MainViewModel 的 Room Flow 校验，不能在其写入新选择后再次清空。
    }

    override suspend fun refreshRecords() {
        // 先撤旧提示再允许新计划执行，避免刚发布的新通知被导入失效事件撤回。
        BabyRecordChangeBus.notifyAllChanged()
        ReminderScheduler.ensureScheduledAndAwait(app)
    }
}

/** 同进程多个入口串行补偿；失败与取消均保留待处理行，下次前台可安全重试。 */
internal class BackupImportCommitCoordinator(
    private val journal: BackupImportJournal,
    private val effects: BackupImportSideEffects
) {
    suspend fun recoverPending() {
        try {
            recoveryMutex.withLock {
                journal.pendingCleanup().forEach { commit -> complete(commit) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // 这里只是派生状态失败；Room 的已提交结果仍成功，不记录资料、ID 或异常正文。
            LogUtils.w("导入已提交，派生状态刷新待重试：${error.javaClass.simpleName}")
        }
    }

    private suspend fun complete(commit: CommittedBackupImport) {
        if (commit.replacedBabyIds.isNotEmpty()) effects.clearReplacedBabyState(commit.replacedBabyIds)
        // MERGE 保留所有已提醒来源；新记录会自然改变来源，不靠全量清缓存重新提醒。
        effects.refreshRecords()
        journal.markCleanupComplete(commit.commitId)
        LogUtils.i("导入提交后的派生状态刷新完成")
    }

    private companion object { val recoveryMutex = Mutex() }
}
