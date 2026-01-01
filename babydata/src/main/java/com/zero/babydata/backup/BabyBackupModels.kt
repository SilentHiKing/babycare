package com.zero.babydata.backup

import com.zero.babydata.entity.BabyInfo
import com.zero.babydata.entity.ChildDailyRecord
import com.zero.babydata.entity.EventRecord
import com.zero.babydata.entity.FeedingRecord
import com.zero.babydata.entity.SleepRecord

/**
 * 备份包版本号
 * 用于未来结构升级与兼容处理。
 */
const val BABY_BACKUP_VERSION = 1

/**
 * 导入策略
 */
enum class BackupImportStrategy {
    OVERWRITE,
    MERGE
}

/**
 * 导入结果统计
 */
data class BackupImportReport(
    val strategy: BackupImportStrategy,
    val babyInserted: Int,
    val babyMatched: Int,
    val babyDuplicateInBackup: Int,
    val feedingInserted: Int,
    val feedingSkipped: Int,
    val sleepInserted: Int,
    val sleepSkipped: Int,
    val eventInserted: Int,
    val eventSkipped: Int,
    val childDailyInserted: Int,
    val childDailyUpdated: Int
)

/**
 * 备份包封装
 */
data class BabyBackupEnvelope(
    val version: Int,
    val createdAt: Long,
    val appVersion: String? = null,
    val data: BabyBackupData
)

/**
 * 备份数据主体
 */
data class BabyBackupData(
    val babies: List<BabyInfo>,
    val feedingRecords: List<FeedingRecord>,
    val sleepRecords: List<SleepRecord>,
    val eventRecords: List<EventRecord>,
    val childDailyRecords: List<ChildDailyRecord>
)
