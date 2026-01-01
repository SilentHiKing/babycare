package com.zero.babydata.backup

import android.content.Context
import com.google.gson.Gson
import com.zero.babydata.entity.ChildDailyRecord
import com.zero.babydata.entity.EventRecord
import com.zero.babydata.entity.FeedingRecord
import com.zero.babydata.entity.SleepRecord
import com.zero.babydata.room.BabyRepository

/**
 * 数据备份导出器
 * 负责收集全量数据并序列化为 JSON。
 */
class BabyBackupExporter(context: Context) {

    private val repository = BabyRepository(context)
    private val gson = Gson()

    /**
     * 构建备份数据包
     */
    fun buildEnvelope(appVersion: String? = null): BabyBackupEnvelope {
        val babies = repository.getAllBabyInfo()
        val feedingRecords = ArrayList<FeedingRecord>()
        val sleepRecords = ArrayList<SleepRecord>()
        val eventRecords = ArrayList<EventRecord>()
        val childDailyRecords = ArrayList<ChildDailyRecord>()

        // 按宝宝聚合数据，避免遗漏与重复查询
        babies.forEach { baby ->
            feedingRecords.addAll(repository.getAllFeedingRecordsSync(baby.babyId))
            sleepRecords.addAll(repository.getAllSleepRecordsSync(baby.babyId))
            eventRecords.addAll(repository.getAllEventRecords(baby.babyId))
            childDailyRecords.addAll(repository.getAllChildDailyRecordsSync(baby.babyId))
        }

        return BabyBackupEnvelope(
            version = BABY_BACKUP_VERSION,
            createdAt = System.currentTimeMillis(),
            appVersion = appVersion,
            data = BabyBackupData(
                babies = babies,
                feedingRecords = feedingRecords,
                sleepRecords = sleepRecords,
                eventRecords = eventRecords,
                childDailyRecords = childDailyRecords
            )
        )
    }

    /**
     * 备份包序列化
     */
    fun toJson(envelope: BabyBackupEnvelope): String {
        return gson.toJson(envelope)
    }
}
