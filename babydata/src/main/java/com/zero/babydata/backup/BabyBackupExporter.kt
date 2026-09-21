package com.zero.babydata.backup

import android.content.Context
import androidx.room.withTransaction
import com.zero.babydata.room.BabyDatabase
import com.zero.babydata.domain.TimerStatus

/**
 * 数据备份导出器
 * 负责收集全量数据并序列化为 JSON。
 */
class BabyBackupExporter internal constructor(private val database: BabyDatabase) {
    constructor(context: Context) : this(BabyDatabase.getInstance(context))

    /**
     * 构建备份数据包
     */
    suspend fun buildEnvelope(appVersion: String? = null): BabyBackupEnvelope {
        // 所有表在同一个读事务内采集，避免导出过程中新增记录造成跨表时间点不一致。
        return database.withTransaction {
            // 必须与读快照在同一事务里先计数，超量库不能先把全部实体装进内存。
            val recordCount = database.babyInfoDao().countForBackup() +
                database.feedingRecordDao().countForBackup() + database.sleepRecordDao().countForBackup() +
                database.eventRecordDao().countForBackup() + database.childDailyRecordDao().countForBackup()
            if (recordCount > BackupJsonLimits.MAX_RECORDS) {
                throw BackupExportException(BackupExportError.LIMIT_EXCEEDED)
            }
            val babies = database.babyInfoDao().getAllBabyInfo()
            val feedingRecords = ArrayList<BackupFeedingRecord>()
            val sleepRecords = ArrayList<BackupSleepRecord>()
            val eventRecords = ArrayList<BackupEventRecord>()
            val childDailyRecords = ArrayList<BackupChildDailyRecord>()

            babies.forEach { baby ->
                feedingRecords += database.feedingRecordDao().getAllFeedingRecords(baby.babyId)
                    .filter { it.timerStatus == TimerStatus.COMPLETED.code }
                    .map { it.toBackup(baby.stableId) }
                sleepRecords += database.sleepRecordDao().getAllSleepRecords(baby.babyId)
                    .filter { it.timerStatus == TimerStatus.COMPLETED.code }
                    .map { it.toBackup(baby.stableId) }
                eventRecords += database.eventRecordDao().getAllEventRecord(baby.babyId)
                    .filter { it.timerStatus == TimerStatus.COMPLETED.code }
                    .map { it.toBackup(baby.stableId) }
                childDailyRecords += database.childDailyRecordDao().getAllRecordsByBabyId(baby.babyId)
                    .map { it.toBackup(baby.stableId) }
            }

            BabyBackupEnvelope(
                version = BABY_BACKUP_VERSION,
                createdAt = System.currentTimeMillis(),
                appVersion = appVersion,
                data = BabyBackupData(
                    babies = babies.map { it.toBackup() },
                    feedingRecords = feedingRecords,
                    sleepRecords = sleepRecords,
                    eventRecords = eventRecords,
                    childDailyRecords = childDailyRecords
                )
            )
        }
    }

    /**
     * 备份包序列化
     */
    fun toJson(envelope: BabyBackupEnvelope): String {
        return BackupJsonEncoder.encode(envelope)
    }
}
