package com.zero.babydata.backup

import android.content.Context
import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import com.zero.babydata.entity.EventRecord
import com.zero.babydata.entity.EventType
import com.zero.babydata.entity.FeedingRecord
import com.zero.babydata.entity.FeedingType
import com.zero.babydata.entity.SleepRecord
import com.zero.babydata.room.BabyDatabase
import java.util.UUID

/** 解析、校验并事务化导入独立备份协议。 */
class BabyBackupImporter(context: Context) {

    private val db = BabyDatabase.getInstance(context)
    private val gson = Gson()

    fun parse(json: String): BabyBackupEnvelope {
        if (json.isBlank() || json.length > MAX_BACKUP_JSON_CHARS) {
            throw BackupImportException(BackupImportError.INVALID_JSON)
        }
        return try {
            val envelope = gson.fromJson(json, BabyBackupEnvelope::class.java)
                ?: throw BackupImportException(BackupImportError.INVALID_JSON)
            validateEnvelope(envelope)
            envelope
        } catch (e: BackupImportException) {
            throw e
        } catch (e: JsonSyntaxException) {
            throw BackupImportException(BackupImportError.INVALID_JSON)
        } catch (e: Exception) {
            // 不向日志或 UI 透传解析异常，避免备份内容出现在诊断信息中。
            throw BackupImportException(BackupImportError.INVALID_JSON)
        }
    }

    suspend fun importOverwrite(envelope: BabyBackupEnvelope): BackupImportReport {
        val data = envelope.data
        db.withTransaction {
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
            data.childDailyRecords.forEach { record ->
                db.childDailyRecordDao().insertChildDailyRecord(
                    record.toEntity(babyIdMap.getValue(record.babyStableId))
                )
            }
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

    /** 合并只按稳定 UUID 匹配宝宝，可变姓名、性别或生日不再承担主键职责。 */
    suspend fun importMerge(envelope: BabyBackupEnvelope, timeBucketMinutes: Int): BackupImportReport {
        val data = envelope.data
        val bucketMinutes = timeBucketMinutes.coerceAtLeast(0)
        val bucketMillis = if (bucketMinutes == 0) 0L else bucketMinutes * 60_000L
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

        db.withTransaction {
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

            val feedingKeys = mutableMapOf<Int, MutableSet<String>>()
            val sleepKeys = mutableMapOf<Int, MutableSet<String>>()
            val eventKeys = mutableMapOf<Int, MutableSet<String>>()
            babyIdMap.values.toSet().forEach { babyId ->
                feedingKeys[babyId] = feedingDao.getAllFeedingRecords(babyId)
                    .map { buildFeedingKey(it, bucketMillis) }
                    .toMutableSet()
                sleepKeys[babyId] = sleepDao.getAllSleepRecords(babyId)
                    .map { buildSleepKey(it, bucketMillis) }
                    .toMutableSet()
                eventKeys[babyId] = eventDao.getAllEventRecord(babyId)
                    .map { buildEventKey(it, bucketMillis) }
                    .toMutableSet()
            }

            data.feedingRecords.forEach { backup ->
                val babyId = babyIdMap.getValue(backup.babyStableId)
                val record = backup.toEntity(babyId)
                val key = buildFeedingKey(record, bucketMillis)
                if (feedingKeys.getOrPut(babyId) { mutableSetOf() }.add(key)) {
                    feedingDao.insertFeedingRecord(record)
                    feedingInserted++
                } else {
                    feedingSkipped++
                }
            }
            data.sleepRecords.forEach { backup ->
                val babyId = babyIdMap.getValue(backup.babyStableId)
                val record = backup.toEntity(babyId)
                val key = buildSleepKey(record, bucketMillis)
                if (sleepKeys.getOrPut(babyId) { mutableSetOf() }.add(key)) {
                    sleepDao.insertSleepRecord(record)
                    sleepInserted++
                } else {
                    sleepSkipped++
                }
            }
            data.eventRecords.forEach { backup ->
                val babyId = babyIdMap.getValue(backup.babyStableId)
                val record = backup.toEntity(babyId)
                val key = buildEventKey(record, bucketMillis)
                if (eventKeys.getOrPut(babyId) { mutableSetOf() }.add(key)) {
                    eventDao.insertEventRecord(record)
                    eventInserted++
                } else {
                    eventSkipped++
                }
            }
            data.childDailyRecords.forEach { backup ->
                val babyId = babyIdMap.getValue(backup.babyStableId)
                val existing = dailyDao.getRecordByDate(babyId, backup.recordDate)
                val record = backup.toEntity(babyId)
                if (existing != null) {
                    dailyDao.updateChildDailyRecord(record.copy(recordId = existing.recordId))
                    childDailyUpdated++
                } else {
                    dailyDao.insertChildDailyRecord(record)
                    childDailyInserted++
                }
            }
        }

        return BackupImportReport(
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
    }

    private fun validateEnvelope(envelope: BabyBackupEnvelope) {
        if (envelope.version != BABY_BACKUP_VERSION) {
            throw BackupImportException(BackupImportError.UNSUPPORTED_VERSION)
        }
        val data = envelope.data
        if (envelope.createdAt <= 0L || data.totalRecordCount() > MAX_BACKUP_RECORDS) {
            throw BackupImportException(BackupImportError.INVALID_JSON)
        }
        val stableIds = data.babies.map { it.stableId }
        if (stableIds.toSet().size != stableIds.size || data.babies.any { !it.isValidForImport() }) {
            throw BackupImportException(BackupImportError.INVALID_JSON)
        }
        if (stableIds.isEmpty() && hasAnyRecord(data)) {
            throw BackupImportException(BackupImportError.INVALID_JSON)
        }
        val stableIdSet = stableIds.toSet()
        if (!recordsBelongToBabies(data, stableIdSet) || !hasValidRecordValues(data)) {
            throw BackupImportException(BackupImportError.INVALID_JSON)
        }
        if (hasDuplicateTransportKeys(data)) {
            throw BackupImportException(BackupImportError.INVALID_JSON)
        }
        if (data.totalRecordCount() == 0) {
            throw BackupImportException(BackupImportError.EMPTY_DATA)
        }
    }

    private suspend fun clearAllTables() {
        db.feedingRecordDao().deleteAllFeedingRecords()
        db.sleepRecordDao().deleteAllSleepRecords()
        db.eventRecordDao().deleteAllEventRecords()
        db.childDailyRecordDao().deleteAllChildDailyRecords()
        db.babyInfoDao().deleteAllBabyInfo()
    }

    private fun buildFeedingKey(record: FeedingRecord, bucketMillis: Long): String = listOf(
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
        record.isFirstTime,
        record.note,
        record.createdAt
    ).joinToString("#")

    private fun buildSleepKey(record: SleepRecord, bucketMillis: Long): String = listOf(
        record.babyId,
        bucketTime(record.sleepStart, bucketMillis),
        bucketTime(record.sleepEnd, bucketMillis),
        bucketTime(record.sleepDuration, bucketMillis),
        record.note,
        record.createdAt
    ).joinToString("#")

    private fun buildEventKey(record: EventRecord, bucketMillis: Long): String = listOf(
        record.babyId,
        record.type,
        bucketTime(record.time, bucketMillis),
        bucketTime(record.endTime, bucketMillis),
        canonicalJson(record.extraData),
        record.note,
        record.createdAt
    ).joinToString("#")

    /** JSON 对象键顺序不应改变事件语义，去重前递归排序对象键。 */
    private fun canonicalJson(raw: String): String {
        if (raw.isBlank()) return ""
        val element = runCatching { JsonParser.parseString(raw) }.getOrNull() ?: return raw.trim()
        return gson.toJson(sortJson(element))
    }

    private fun sortJson(element: JsonElement): JsonElement = when {
        element.isJsonObject -> JsonObject().apply {
            element.asJsonObject.entrySet()
                .sortedBy { it.key }
                .forEach { (key, value) -> add(key, sortJson(value)) }
        }
        element.isJsonArray -> JsonArray().apply {
            element.asJsonArray.forEach { add(sortJson(it)) }
        }
        else -> element
    }

    private fun bucketTime(value: Long, bucketMillis: Long): Long {
        if (value <= 0L || bucketMillis <= 0L) return value.coerceAtLeast(0L)
        return value / bucketMillis
    }

    private fun hasAnyRecord(data: BabyBackupData): Boolean =
        data.feedingRecords.isNotEmpty() ||
            data.sleepRecords.isNotEmpty() ||
            data.eventRecords.isNotEmpty() ||
            data.childDailyRecords.isNotEmpty()

    private fun recordsBelongToBabies(data: BabyBackupData, stableIds: Set<String>): Boolean =
        data.feedingRecords.all { it.babyStableId in stableIds } &&
            data.sleepRecords.all { it.babyStableId in stableIds } &&
            data.eventRecords.all { it.babyStableId in stableIds } &&
            data.childDailyRecords.all { it.babyStableId in stableIds }

    private fun hasValidRecordValues(data: BabyBackupData): Boolean {
        val validFeedingTypes = FeedingType.entries.mapTo(mutableSetOf()) { it.type }
        return data.feedingRecords.all { record ->
            record.sourceId.isValidTransportId() &&
                record.feedingType in validFeedingTypes &&
                record.feedingStart > 0L &&
                record.feedingEnd >= record.feedingStart &&
                record.feedingDuration in 0L..(record.feedingEnd - record.feedingStart) &&
                record.feedingDurationBreastLeft >= 0L &&
                record.feedingDurationBreastRight >= 0L &&
                (record.feedingAmount == null || record.feedingAmount in 0..MAX_FEEDING_AMOUNT) &&
                record.note.length <= MAX_TEXT_LENGTH &&
                (record.foodName?.length ?: 0) <= MAX_NAME_LENGTH
        } && data.sleepRecords.all { record ->
            record.sourceId.isValidTransportId() &&
                record.sleepStart > 0L &&
                record.sleepEnd >= record.sleepStart &&
                record.sleepDuration in 0L..(record.sleepEnd - record.sleepStart) &&
                record.note.length <= MAX_TEXT_LENGTH
        } && data.eventRecords.all { record ->
            record.sourceId.isValidTransportId() &&
                record.type in VALID_EVENT_TYPES &&
                record.time > 0L &&
                (record.endTime == 0L || record.endTime >= record.time) &&
                record.extraData.length <= MAX_EXTRA_LENGTH &&
                record.note.length <= MAX_TEXT_LENGTH &&
                (record.extraData.isBlank() || runCatching { JsonParser.parseString(record.extraData) }.isSuccess)
        } && data.childDailyRecords.all { record ->
            record.sourceId.isValidTransportId() &&
                record.recordDate > 0L &&
                record.weight.isFinite() && record.weight >= 0f &&
                record.height.isFinite() && record.height >= 0f &&
                record.headCircumference.isFinite() && record.headCircumference >= 0f &&
                record.pic.length <= MAX_TEXT_LENGTH &&
                record.extra.length <= MAX_EXTRA_LENGTH
        }
    }

    private fun hasDuplicateTransportKeys(data: BabyBackupData): Boolean =
        data.feedingRecords.hasDuplicateBy { it.sourceId } ||
            data.sleepRecords.hasDuplicateBy { it.sourceId } ||
            data.eventRecords.hasDuplicateBy { it.sourceId } ||
            data.childDailyRecords.hasDuplicateBy { it.sourceId } ||
            data.childDailyRecords.hasDuplicateBy { it.babyStableId to it.recordDate }

    private fun BackupBaby.isValidForImport(): Boolean =
        stableId.isUuid() &&
            name.isNotBlank() && name.length <= MAX_NAME_LENGTH &&
            gender.length <= MAX_NAME_LENGTH &&
            birthDate > 0L &&
            birthWeight.isFinite() && birthWeight >= 0f &&
            birthHeight.isFinite() && birthHeight >= 0f &&
            bloodType.length <= MAX_NAME_LENGTH &&
            extra.length <= MAX_EXTRA_LENGTH

    private fun String.isUuid(): Boolean =
        runCatching { UUID.fromString(this) }.getOrNull()?.toString() == lowercase()

    private fun String.isValidTransportId(): Boolean = isNotBlank() && length <= MAX_TRANSPORT_ID_LENGTH

    private fun BabyBackupData.totalRecordCount(): Int =
        babies.size + feedingRecords.size + sleepRecords.size + eventRecords.size + childDailyRecords.size

    private inline fun <T, K> List<T>.hasDuplicateBy(keySelector: (T) -> K): Boolean {
        val keys = HashSet<K>(size)
        return any { !keys.add(keySelector(it)) }
    }

    private companion object {
        const val MAX_BACKUP_JSON_CHARS = 20 * 1024 * 1024
        const val MAX_BACKUP_RECORDS = 200_000
        const val MAX_TEXT_LENGTH = 10_000
        const val MAX_EXTRA_LENGTH = 100_000
        const val MAX_NAME_LENGTH = 200
        const val MAX_TRANSPORT_ID_LENGTH = 300
        const val MAX_FEEDING_AMOUNT = 100_000

        val VALID_EVENT_TYPES = setOf(
            EventType.DIAPER_WET,
            EventType.DIAPER_DIRTY,
            EventType.DIAPER_MIXED,
            EventType.DIAPER_DRY,
            EventType.GROWTH_WEIGHT,
            EventType.GROWTH_HEIGHT,
            EventType.GROWTH_HEAD,
            EventType.HEALTH_TEMPERATURE,
            EventType.HEALTH_MEDICINE,
            EventType.HEALTH_VACCINE,
            EventType.HEALTH_DOCTOR,
            EventType.HEALTH_SYMPTOM,
            EventType.MILESTONE_ROLL,
            EventType.MILESTONE_SIT,
            EventType.MILESTONE_CRAWL,
            EventType.MILESTONE_STAND,
            EventType.MILESTONE_WALK,
            EventType.MILESTONE_FIRST_WORD,
            EventType.MILESTONE_FIRST_TOOTH,
            EventType.MILESTONE_CUSTOM,
            EventType.CARE_BATH,
            EventType.CARE_NAIL,
            EventType.CARE_SKINCARE,
            EventType.CARE_MASSAGE,
            EventType.CARE_NOSE,
            EventType.CARE_EAR,
            EventType.ACTIVITY_OUTDOOR,
            EventType.ACTIVITY_TUMMY_TIME,
            EventType.ACTIVITY_SWIMMING,
            EventType.ACTIVITY_PLAY,
            EventType.OTHER_BURP,
            EventType.OTHER_CRY,
            EventType.OTHER_SPIT_UP,
            EventType.OTHER_CUSTOM
        )
    }
}

class BackupImportException(val error: BackupImportError) : RuntimeException()

enum class BackupImportError {
    INVALID_JSON,
    UNSUPPORTED_VERSION,
    EMPTY_DATA
}
