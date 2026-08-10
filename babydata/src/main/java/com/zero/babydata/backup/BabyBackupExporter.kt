package com.zero.babydata.backup

import android.content.Context
import androidx.room.withTransaction
import com.google.gson.Gson
import com.zero.babydata.room.BabyDatabase
import com.zero.babydata.room.BabyRepository

/**
 * 数据备份导出器
 * 负责收集全量数据并序列化为 JSON。
 */
class BabyBackupExporter(context: Context) {

    private val database = BabyDatabase.getInstance(context)
    private val repository = BabyRepository(context)
    private val gson = Gson()

    /**
     * 构建备份数据包
     */
    suspend fun buildEnvelope(appVersion: String? = null): BabyBackupEnvelope {
        // 所有表在同一个读事务内采集，避免导出过程中新增记录造成跨表时间点不一致。
        return database.withTransaction {
            val babies = repository.getAllBabyInfo()
            val feedingRecords = ArrayList<BackupFeedingRecord>()
            val sleepRecords = ArrayList<BackupSleepRecord>()
            val eventRecords = ArrayList<BackupEventRecord>()
            val childDailyRecords = ArrayList<BackupChildDailyRecord>()

            babies.forEach { baby ->
                feedingRecords += repository.getAllFeedingRecords(baby.babyId)
                    .map { it.toBackup(baby.stableId) }
                sleepRecords += repository.getAllSleepRecords(baby.babyId)
                    .map { it.toBackup(baby.stableId) }
                eventRecords += repository.getAllEventRecords(baby.babyId)
                    .map { it.toBackup(baby.stableId) }
                childDailyRecords += repository.getAllChildDailyRecords(baby.babyId)
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
        return gson.toJson(envelope)
    }
}
