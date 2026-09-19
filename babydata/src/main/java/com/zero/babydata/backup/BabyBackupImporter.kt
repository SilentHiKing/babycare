package com.zero.babydata.backup

import android.content.Context
import androidx.room.withTransaction
import com.zero.babydata.entity.ChildDailyRecord
import com.zero.babydata.room.BabyDatabase
import java.util.UUID

/** 解析、校验并事务化导入独立备份协议。 */
class BabyBackupImporter(private val db: BabyDatabase) {
    constructor(context: Context) : this(BabyDatabase.getInstance(context))

    fun parse(json: String): BabyBackupEnvelope = BabyBackupParser.parse(json)

    suspend fun importOverwrite(envelope: BabyBackupEnvelope, commitId: String = UUID.randomUUID().toString()): BackupImportReport {
        BabyBackupParser.validateEnvelope(envelope)
        val data = envelope.data
        return db.withTransaction {
            val replacedBabyIds = db.babyInfoDao().getAllBabyInfo().map { it.babyId }
            clearAllTables()
            val babyIdMap = mutableMapOf<String, Int>()
            data.babies.forEach { baby ->
                babyIdMap[baby.stableId] = db.babyInfoDao().insertBabyInfo(baby.toEntity()).toInt()
            }
            data.feedingRecords.forEach { record ->
                db.feedingRecordDao().insertFeedingRecord(record.toEntity(babyIdMap.getValue(record.babyStableId)))
            }
            data.sleepRecords.forEach { record ->
                db.sleepRecordDao().insertSleepRecord(record.toEntity(babyIdMap.getValue(record.babyStableId)))
            }
            data.eventRecords.forEach { record ->
                db.eventRecordDao().insertEventRecord(record.toEntity(babyIdMap.getValue(record.babyStableId)))
            }
            // 导出按同日 ID 倒序排列；反向插入保留重建后“最新一条”的选择语义。
            data.childDailyRecords.asReversed().forEach { record ->
                db.childDailyRecordDao().insertChildDailyRecord(
                    record.toEntity(babyIdMap.getValue(record.babyStableId))
                )
            }
            val report = BackupImportReport(
            strategy = BackupImportStrategy.OVERWRITE,
            babyInserted = data.babies.size,
            babyMatched = 0,
            babyDuplicateInBackup = 0,
            feedingInserted = data.feedingRecords.size,
            feedingSkipped = 0,
            sleepInserted = data.sleepRecords.size,
            sleepSkipped = 0,
            eventInserted = data.eventRecords.size,
            eventSkipped = 0,
            childDailyInserted = data.childDailyRecords.size,
            childDailyUpdated = 0
            )
            BackupImportJournal(db).recordCommit(commitId, report, replacedBabyIds)
            report
        }
    }

    /** 合并只按稳定 UUID 匹配宝宝，可变姓名、性别或生日不再承担主键职责。 */
    suspend fun importMerge(envelope: BabyBackupEnvelope, timeToleranceMinutes: Int, commitId: String = UUID.randomUUID().toString()): BackupImportReport {
        BabyBackupParser.validateEnvelope(envelope)
        val data = envelope.data
        val toleranceMillis = timeToleranceMinutes.coerceAtLeast(0) * 60_000L
        var babyInserted = 0
        var babyMatched = 0
        var feedingInserted = 0
        var feedingSkipped = 0
        var sleepInserted = 0
        var sleepSkipped = 0
        var eventInserted = 0
        var eventSkipped = 0
        var childDailyInserted = 0
        var childDailyUpdated = 0

        return db.withTransaction {
            val babyDao = db.babyInfoDao()
            val feedingDao = db.feedingRecordDao()
            val sleepDao = db.sleepRecordDao()
            val eventDao = db.eventRecordDao()
            val dailyDao = db.childDailyRecordDao()
            val babyIdMap = mutableMapOf<String, Int>()

            data.babies.forEach { baby ->
                val existing = babyDao.findBabyByStableId(baby.stableId)
                val targetId = if (existing != null) {
                    babyMatched++
                    existing.babyId
                } else {
                    babyInserted++
                    babyDao.insertBabyInfo(baby.toEntity()).toInt()
                }
                babyIdMap[baby.stableId] = targetId
            }

            val deduplicator = BackupRecordDeduplicator(toleranceMillis)
            babyIdMap.values.toSet().forEach { babyId ->
                // 本地已有的每条记录都作为候选，不能因为两条本地记录接近就丢掉匹配范围。
                feedingDao.getAllFeedingRecords(babyId).forEach(deduplicator::remember)
                sleepDao.getAllSleepRecords(babyId).forEach(deduplicator::remember)
                eventDao.getAllEventRecord(babyId).forEach(deduplicator::remember)
            }

            data.feedingRecords.forEach { backup ->
                val babyId = babyIdMap.getValue(backup.babyStableId)
                val record = backup.toEntity(babyId)
                if (deduplicator.add(record)) {
                    feedingDao.insertFeedingRecord(record)
                    feedingInserted++
                } else {
                    feedingSkipped++
                }
            }
            data.sleepRecords.forEach { backup ->
                val babyId = babyIdMap.getValue(backup.babyStableId)
                val record = backup.toEntity(babyId)
                if (deduplicator.add(record)) {
                    sleepDao.insertSleepRecord(record)
                    sleepInserted++
                } else {
                    sleepSkipped++
                }
            }
            data.eventRecords.forEach { backup ->
                val babyId = babyIdMap.getValue(backup.babyStableId)
                val record = backup.toEntity(babyId)
                if (deduplicator.add(record)) {
                    eventDao.insertEventRecord(record)
                    eventInserted++
                } else {
                    eventSkipped++
                }
            }
            val dailyRecords = mutableMapOf<Pair<Int, Long>, MutableList<ChildDailyRecord>>()
            babyIdMap.values.toSet().forEach { babyId ->
                dailyDao.getAllRecordsByBabyId(babyId).forEach { record ->
                    dailyRecords.getOrPut(babyId to record.recordDate) { mutableListOf() }.add(record)
                }
            }
            data.childDailyRecords.asReversed().forEach { backup ->
                val babyId = babyIdMap.getValue(backup.babyStableId)
                val record = backup.toEntity(babyId)
                val sameDay = dailyRecords.getOrPut(babyId to record.recordDate) { mutableListOf() }
                if (sameDay.none { hasSameDailyContent(it, record) }) {
                    val existing = sameDay.firstOrNull { !hasDailyRecordConflict(it, record) }
                    if (existing == null) {
                        // 同日不同测量或照片是独立历史，不因合并导入而静默丢失。
                        val id = dailyDao.insertChildDailyRecord(record).toInt()
                        sameDay.add(0, record.copy(recordId = id))
                        childDailyInserted++
                    } else {
                        val merged = mergeDailyRecord(existing, record)
                        if (merged != existing) {
                            dailyDao.updateChildDailyRecord(merged)
                            sameDay[sameDay.indexOf(existing)] = merged
                            childDailyUpdated++
                        }
                    }
                }
            }
            val report = BackupImportReport(
            strategy = BackupImportStrategy.MERGE,
            babyInserted = babyInserted,
            babyMatched = babyMatched,
            babyDuplicateInBackup = 0,
            feedingInserted = feedingInserted,
            feedingSkipped = feedingSkipped,
            sleepInserted = sleepInserted,
            sleepSkipped = sleepSkipped,
            eventInserted = eventInserted,
            eventSkipped = eventSkipped,
            childDailyInserted = childDailyInserted,
            childDailyUpdated = childDailyUpdated
            )
            BackupImportJournal(db).recordCommit(commitId, report, emptyList())
            report
        }
    }

    private suspend fun clearAllTables() {
        db.feedingRecordDao().deleteAllFeedingRecords()
        db.sleepRecordDao().deleteAllSleepRecords()
        db.eventRecordDao().deleteAllEventRecords()
        db.childDailyRecordDao().deleteAllChildDailyRecords()
        db.babyInfoDao().deleteAllBabyInfo()
    }

}

class BackupImportException(val error: BackupImportError) : RuntimeException()

enum class BackupImportError {
    INVALID_JSON,
    UNSUPPORTED_VERSION,
    EMPTY_DATA
}
