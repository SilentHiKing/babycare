package com.zero.babydata.backup

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import com.zero.babydata.entity.EventType
import com.zero.babydata.entity.FeedingType
import java.io.Reader
import java.util.UUID

/** 纯备份协议解析与校验，不依赖数据库，避免预览文件时提前访问 Room。 */
object BabyBackupParser {
    private val gson = Gson()

    fun parse(reader: Reader): BabyBackupEnvelope = parse(readLimited(reader))

    /** 读取期间就限制大小，不能把任意大文件读完后才检查，否则校验前就可能耗尽内存。 */
    internal fun readLimited(reader: Reader, maxChars: Int = MAX_BACKUP_JSON_CHARS): String {
        val result = StringBuilder()
        val buffer = CharArray(8 * 1024)
        while (true) {
            val count = reader.read(buffer, 0, minOf(buffer.size, maxChars - result.length + 1))
            if (count < 0) return result.toString()
            if (count > maxChars - result.length) {
                throw BackupImportException(BackupImportError.INVALID_JSON)
            }
            result.append(buffer, 0, count)
        }
    }

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

    internal fun validateEnvelope(envelope: BabyBackupEnvelope) {
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
                hasValidEventExtra(record)
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

    /** Gson 不执行 Kotlin 非空约束，必须在导入前拦住会使统计与编辑页崩溃的缺失字段。 */
    private fun hasValidEventExtra(record: BackupEventRecord): Boolean {
        // 老版本事件没有扩展字段，空内容必须仍能恢复。
        if (record.extraData.isBlank()) return true
        val json = runCatching { JsonParser.parseString(record.extraData) }.getOrNull()
            ?.takeIf { it.isJsonObject }?.asJsonObject ?: return false
        return when {
            EventType.isGrowth(record.type) -> {
                val validUnits = if (record.type == EventType.GROWTH_WEIGHT) {
                    setOf("kg", "g", "lb", "oz")
                } else {
                    setOf("cm", "mm", "in")
                }
                json.positiveNumber("value") && json.stringValue("unit") in validUnits
            }
            record.type == EventType.HEALTH_TEMPERATURE ->
                json.positiveNumber("value") && json.stringValue("location") != null
            record.type == EventType.HEALTH_MEDICINE || record.type == EventType.HEALTH_VACCINE ->
                json.stringValue("name") != null
            else -> true
        }
    }

    private fun JsonObject.stringValue(key: String): String? = get(key)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.positiveNumber(key: String): Boolean {
        val value = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            ?.asDouble ?: return false
        return value.isFinite() && value > 0.0
    }

    private fun hasDuplicateTransportKeys(data: BabyBackupData): Boolean =
        data.feedingRecords.hasDuplicateBy { it.sourceId } ||
            data.sleepRecords.hasDuplicateBy { it.sourceId } ||
            data.eventRecords.hasDuplicateBy { it.sourceId } ||
            data.childDailyRecords.hasDuplicateBy { it.sourceId }

    private fun BackupBaby.isValidForImport(): Boolean =
        stableId.isUuid() &&
            name.isNotBlank() && name.length <= MAX_NAME_LENGTH &&
            gender.length <= MAX_NAME_LENGTH &&
            birthDate >= 0L &&
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

    private const val MAX_BACKUP_JSON_CHARS = 20 * 1024 * 1024
    private const val MAX_BACKUP_RECORDS = 200_000
    private const val MAX_TEXT_LENGTH = 10_000
    private const val MAX_EXTRA_LENGTH = 100_000
    private const val MAX_NAME_LENGTH = 200
    private const val MAX_TRANSPORT_ID_LENGTH = 300
    private const val MAX_FEEDING_AMOUNT = 100_000

    private val VALID_EVENT_TYPES = setOf(
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
