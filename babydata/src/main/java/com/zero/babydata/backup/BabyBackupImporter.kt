package com.zero.babydata.backup

import android.content.Context
import com.blankj.utilcode.util.LogUtils
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.zero.babydata.entity.EventRecord
import com.zero.babydata.entity.FeedingRecord
import com.zero.babydata.entity.SleepRecord
import com.zero.babydata.room.BabyDatabase

/**
 * 备份导入器
 * 负责解析备份 JSON，并根据导入策略落库。
 */
class BabyBackupImporter(context: Context) {

    private val db = BabyDatabase.getInstance(context)
    private val gson = Gson()

    /**
     * 解析备份 JSON
     */
    fun parse(json: String): BabyBackupEnvelope {
        if (json.isBlank()) {
            throw BackupImportException(BackupImportError.INVALID_JSON)
        }
        try {
            val envelope = gson.fromJson(json, BabyBackupEnvelope::class.java)
                ?: throw BackupImportException(BackupImportError.INVALID_JSON)
            validateEnvelope(envelope)
            return envelope
        } catch (e: Exception) {
            LogUtils.e("backup parse failed: ${e.message}")
            if (e is BackupImportException) {
                throw e
            }
            if (e is JsonSyntaxException) {
                throw BackupImportException(BackupImportError.INVALID_JSON)
            }
            throw BackupImportException(BackupImportError.INVALID_JSON)
        }
    }

    /**
     * 覆盖式导入
     */
    fun importOverwrite(envelope: BabyBackupEnvelope): BackupImportReport {
        val data = envelope.data
        db.runInTransaction {
            clearAllTables()
            // 先插入宝宝，再插入关联记录，保证外键可用
            data.babies.forEach { db.babyInfoDao().insertBabyInfo(it) }
            data.feedingRecords.forEach { db.feedingRecordDao().insertFeedingRecord(it) }
            data.sleepRecords.forEach { db.sleepRecordDao().insertSleepRecord(it) }
            data.eventRecords.forEach { db.eventRecordDao().insertEventRecord(it) }
            data.childDailyRecords.forEach { db.childDailyRecordDao().insertChildDailyRecord(it) }
        }
        return BackupImportReport(
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
    }

    /**
     * 合并式导入
     * - 通过宝宝基本信息匹配已有数据
     * - 未匹配的宝宝会新增，并重建关联 ID
     */
    fun importMerge(envelope: BabyBackupEnvelope, timeBucketMinutes: Int): BackupImportReport {
        val data = envelope.data
        val bucketMinutes = timeBucketMinutes.coerceAtLeast(0)
        val bucketMillis = if (bucketMinutes == 0) 0L else bucketMinutes * 60_000L
        var babyInserted = 0
        var babyMatched = 0
        var babyDuplicateInBackup = 0
        var feedingInserted = 0
        var feedingSkipped = 0
        var sleepInserted = 0
        var sleepSkipped = 0
        var eventInserted = 0
        var eventSkipped = 0
        var childDailyInserted = 0
        var childDailyUpdated = 0

        db.runInTransaction {
            val babyDao = db.babyInfoDao()
            val feedingDao = db.feedingRecordDao()
            val sleepDao = db.sleepRecordDao()
            val eventDao = db.eventRecordDao()
            val dailyDao = db.childDailyRecordDao()

            // 建立旧 babyId 到新 babyId 的映射
            val babyIdMap = mutableMapOf<Int, Int>()
            val identitySet = mutableSetOf<String>()
            data.babies.forEach { baby ->
                val identityKey = buildBabyIdentityKey(baby.name, baby.gender, baby.birthDate)
                if (!identitySet.add(identityKey)) {
                    babyDuplicateInBackup++
                    LogUtils.w("duplicate baby identity in backup: $identityKey")
                }
                val existing = babyDao.findBabyByIdentity(baby.name, baby.gender, baby.birthDate)
                val targetId = if (existing != null) {
                    babyMatched++
                    existing.babyId
                } else {
                    // 重置主键，避免与当前数据冲突
                    val newId = babyDao.insertBabyInfo(baby.copy(babyId = 0))
                    babyInserted++
                    newId.toInt()
                }
                babyIdMap[baby.babyId] = targetId
            }

            // 构建去重索引（按宝宝维度）
            val feedingKeyMap = mutableMapOf<Int, MutableSet<String>>()
            val sleepKeyMap = mutableMapOf<Int, MutableSet<String>>()
            val eventKeyMap = mutableMapOf<Int, MutableSet<String>>()

            babyIdMap.values.toSet().forEach { targetBabyId ->
                feedingKeyMap[targetBabyId] = feedingDao.getAllFeedingRecords(targetBabyId)
                    .map { buildFeedingKey(it, bucketMillis) }
                    .toMutableSet()
                sleepKeyMap[targetBabyId] = sleepDao.getAllSleepRecords(targetBabyId)
                    .map { buildSleepKey(it, bucketMillis) }
                    .toMutableSet()
                eventKeyMap[targetBabyId] = eventDao.getAllEventRecord(targetBabyId)
                    .map { buildEventKey(it, bucketMillis) }
                    .toMutableSet()
            }

            // 逐类记录追加导入，并修正 babyId 与自增主键
            data.feedingRecords.forEach { record ->
                val targetBabyId = babyIdMap[record.babyId] ?: return@forEach
                val newRecord = record.copy(feedingId = 0, babyId = targetBabyId)
                val key = buildFeedingKey(newRecord, bucketMillis)
                val keySet = feedingKeyMap.getOrPut(targetBabyId) { mutableSetOf() }
                if (keySet.add(key)) {
                    feedingDao.insertFeedingRecord(newRecord)
                    feedingInserted++
                } else {
                    feedingSkipped++
                }
            }
            data.sleepRecords.forEach { record ->
                val targetBabyId = babyIdMap[record.babyId] ?: return@forEach
                val newRecord = record.copy(sleepId = 0, babyId = targetBabyId)
                val key = buildSleepKey(newRecord, bucketMillis)
                val keySet = sleepKeyMap.getOrPut(targetBabyId) { mutableSetOf() }
                if (keySet.add(key)) {
                    sleepDao.insertSleepRecord(newRecord)
                    sleepInserted++
                } else {
                    sleepSkipped++
                }
            }
            data.eventRecords.forEach { record ->
                val targetBabyId = babyIdMap[record.babyId] ?: return@forEach
                val newRecord = record.copy(eventId = 0, babyId = targetBabyId)
                val key = buildEventKey(newRecord, bucketMillis)
                val keySet = eventKeyMap.getOrPut(targetBabyId) { mutableSetOf() }
                if (keySet.add(key)) {
                    eventDao.insertEventRecord(newRecord)
                    eventInserted++
                } else {
                    eventSkipped++
                }
            }
            data.childDailyRecords.forEach { record ->
                val targetBabyId = babyIdMap[record.babyId] ?: return@forEach
                val existing = dailyDao.getRecordByDate(targetBabyId, record.recordDate)
                if (existing != null) {
                    // 同日记录以导入数据为准，避免重复
                    dailyDao.updateChildDailyRecord(
                        record.copy(recordId = existing.recordId, babyId = targetBabyId)
                    )
                    childDailyUpdated++
                } else {
                    dailyDao.insertChildDailyRecord(record.copy(recordId = 0, babyId = targetBabyId))
                    childDailyInserted++
                }
            }
        }
        return BackupImportReport(
            strategy = BackupImportStrategy.MERGE,
            babyInserted = babyInserted,
            babyMatched = babyMatched,
            babyDuplicateInBackup = babyDuplicateInBackup,
            feedingInserted = feedingInserted,
            feedingSkipped = feedingSkipped,
            sleepInserted = sleepInserted,
            sleepSkipped = sleepSkipped,
            eventInserted = eventInserted,
            eventSkipped = eventSkipped,
            childDailyInserted = childDailyInserted,
            childDailyUpdated = childDailyUpdated
        )
    }

    /**
     * 校验备份内容
     */
    private fun validateEnvelope(envelope: BabyBackupEnvelope) {
        if (envelope.version > BABY_BACKUP_VERSION) {
            throw BackupImportException(BackupImportError.UNSUPPORTED_VERSION)
        }
        val data = envelope.data
        val babyIdSet = data.babies.map { it.babyId }.toSet()
        if (babyIdSet.isEmpty() && hasAnyRecord(data)) {
            LogUtils.e("backup has records but no baby info")
            throw BackupImportException(BackupImportError.INVALID_JSON)
        }
        if (!isRecordsBelongToBabies(data, babyIdSet)) {
            throw BackupImportException(BackupImportError.INVALID_JSON)
        }
        val hasContent = data.babies.isNotEmpty() ||
            data.feedingRecords.isNotEmpty() ||
            data.sleepRecords.isNotEmpty() ||
            data.eventRecords.isNotEmpty() ||
            data.childDailyRecords.isNotEmpty()
        if (!hasContent) {
            throw BackupImportException(BackupImportError.EMPTY_DATA)
        }
    }

    /**
     * 清空表数据
     */
    private fun clearAllTables() {
        db.feedingRecordDao().deleteAllFeedingRecords()
        db.sleepRecordDao().deleteAllSleepRecords()
        db.eventRecordDao().deleteAllEventRecords()
        db.childDailyRecordDao().deleteAllChildDailyRecords()
        db.babyInfoDao().deleteAllBabyInfo()
    }

    /**
     * 构建宝宝身份 Key
     */
    private fun buildBabyIdentityKey(name: String, gender: String, birthDate: Long): String {
        return "${name.trim()}|${gender.trim()}|$birthDate"
    }

    /**
     * 生成喂养记录去重 Key
     */
    private fun buildFeedingKey(record: FeedingRecord, bucketMillis: Long): String {
        return listOf(
            record.babyId,
            record.feedingType,
            bucketTime(record.feedingStart, bucketMillis),
            bucketTime(record.feedingEnd, bucketMillis),
            bucketTime(record.feedingDuration, bucketMillis),
            bucketTime(record.feedingDurationBreastLeft, bucketMillis),
            bucketTime(record.feedingDurationBreastRight, bucketMillis),
            record.feedingAmount ?: -1,
            record.babyMood ?: -1,
            record.feedingLocation ?: -1,
            record.solidFoodType ?: -1,
            record.foodName.orEmpty(),
            record.isFirstTime
        ).joinToString("#")
    }

    /**
     * 生成睡眠记录去重 Key
     */
    private fun buildSleepKey(record: SleepRecord, bucketMillis: Long): String {
        return listOf(
            record.babyId,
            bucketTime(record.sleepStart, bucketMillis),
            bucketTime(record.sleepEnd, bucketMillis),
            bucketTime(record.sleepDuration, bucketMillis)
        ).joinToString("#")
    }

    /**
     * 生成事件记录去重 Key
     */
    private fun buildEventKey(record: EventRecord, bucketMillis: Long): String {
        return listOf(
            record.babyId,
            record.type,
            bucketTime(record.time, bucketMillis),
            bucketTime(record.endTime, bucketMillis),
            record.extraData
        ).joinToString("#")
    }

    /**
     * 时间归一化，按分钟桶对齐
     * bucketMillis=0 时保持原值
     */
    private fun bucketTime(value: Long, bucketMillis: Long): Long {
        if (value <= 0L) return 0L
        if (bucketMillis <= 0L) return value
        return value / bucketMillis
    }

    /**
     * 判断是否有任何记录数据
     */
    private fun hasAnyRecord(data: BabyBackupData): Boolean {
        return data.feedingRecords.isNotEmpty() ||
            data.sleepRecords.isNotEmpty() ||
            data.eventRecords.isNotEmpty() ||
            data.childDailyRecords.isNotEmpty()
    }

    /**
     * 校验记录是否归属到备份中的宝宝
     */
    private fun isRecordsBelongToBabies(data: BabyBackupData, babyIdSet: Set<Int>): Boolean {
        val invalidFeeding = data.feedingRecords.firstOrNull { it.babyId !in babyIdSet }
        val invalidSleep = data.sleepRecords.firstOrNull { it.babyId !in babyIdSet }
        val invalidEvent = data.eventRecords.firstOrNull { it.babyId !in babyIdSet }
        val invalidDaily = data.childDailyRecords.firstOrNull { it.babyId !in babyIdSet }
        if (invalidFeeding != null) {
            LogUtils.e("backup invalid feeding babyId: ${invalidFeeding.babyId}")
            return false
        }
        if (invalidSleep != null) {
            LogUtils.e("backup invalid sleep babyId: ${invalidSleep.babyId}")
            return false
        }
        if (invalidEvent != null) {
            LogUtils.e("backup invalid event babyId: ${invalidEvent.babyId}")
            return false
        }
        if (invalidDaily != null) {
            LogUtils.e("backup invalid daily babyId: ${invalidDaily.babyId}")
            return false
        }
        return true
    }
}

/**
 * 备份导入异常
 */
class BackupImportException(val error: BackupImportError) : RuntimeException()

/**
 * 备份导入错误类型
 */
enum class BackupImportError {
    INVALID_JSON,
    UNSUPPORTED_VERSION,
    EMPTY_DATA
}
