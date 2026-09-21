package com.zero.babydata.backup

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import com.zero.babydata.entity.EventType
import com.zero.babydata.entity.FeedingType
import com.zero.babydata.domain.DurationBasis
import com.zero.babydata.domain.TimerMetadata
import com.zero.babydata.domain.TimerMetadataCodec
import com.zero.babydata.domain.TimerRecordKind
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
            BackupJsonLimits.validate(json, countRecords = true)
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
        if (data.totalRecordCount() == 0L) {
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
                (record.feedingEnd >= record.feedingStart ||
                    record.timing?.basis == DurationBasis.UNMEASURED && record.feedingEnd == 0L) &&
                record.feedingDuration in 0L..(record.feedingEnd - record.feedingStart).coerceAtLeast(0L) &&
                record.feedingDurationBreastLeft >= 0L &&
                record.feedingDurationBreastRight >= 0L &&
                (record.feedingAmount == null || record.feedingAmount in 0..MAX_FEEDING_AMOUNT) &&
                record.note.isPresent() &&
                validTiming(record.timing, TimerRecordKind.FEEDING, record.feedingStart, record.feedingEnd,
                    record.feedingDuration, record.feedingDurationBreastLeft, record.feedingDurationBreastRight)
        } && data.sleepRecords.all { record ->
            record.sourceId.isValidTransportId() &&
                record.sleepStart > 0L &&
                record.sleepEnd >= record.sleepStart &&
                record.sleepDuration in 0L..(record.sleepEnd - record.sleepStart) &&
                record.note.isPresent() &&
                validTiming(record.timing, TimerRecordKind.SLEEP, record.sleepStart, record.sleepEnd, record.sleepDuration)
        } && data.eventRecords.all { record ->
            record.sourceId.isValidTransportId() &&
                record.type in VALID_EVENT_TYPES &&
                record.time > 0L &&
                (record.endTime == 0L || record.endTime >= record.time) &&
                record.note.isPresent() &&
                (record.activeDurationMs == null || record.activeDurationMs in 0L..(record.endTime - record.time).coerceAtLeast(0L)) &&
                (record.timing == null || EventType.hasDuration(record.type)) &&
                validTiming(record.timing, TimerRecordKind.EVENT, record.time, record.endTime, record.activeDurationMs) &&
                hasValidEventExtra(record)
        } && data.childDailyRecords.all { record ->
            record.sourceId.isValidTransportId() &&
                record.recordDate > 0L &&
                record.weight.isFinite() && record.weight >= 0f &&
                record.height.isFinite() && record.height >= 0f &&
                record.headCircumference.isFinite() && record.headCircumference >= 0f &&
                record.pic.isPresent() && record.extra.isPresent()
        }
    }

    /** Gson 不执行 Kotlin 非空约束，必须在导入前拦住会使统计与编辑页崩溃的缺失字段。 */
    private fun hasValidEventExtra(record: BackupEventRecord): Boolean {
        // 老版本事件没有扩展字段，空内容必须仍能恢复。
        if (record.extraData.isBlank()) return true
        val json = try {
            BackupJsonLimits.validate(record.extraData, maxValues = BackupJsonLimits.MAX_EXTRA_VALUES)
            JsonParser.parseString(record.extraData)
        } catch (_: Exception) { return false }
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
            record.type == EventType.HEALTH_MEDICINE ->
                json.stringValue("name") != null && json.optionalStrings("dosage", "unit")
            record.type == EventType.HEALTH_VACCINE ->
                json.stringValue("name") != null && json.optionalStrings("site", "batchNumber", "clinic") &&
                    json.optional("dose") { value ->
                        value.isJsonPrimitive && value.asJsonPrimitive.isNumber &&
                            runCatching { value.asBigDecimal.intValueExact() }.isSuccess
                    }
            EventType.isDiaper(record.type) ->
                json.optionalStrings("color", "consistency", "urineAmount", "abnormalDesc") &&
                    json.optionalBoolean("abnormal")
            record.type == EventType.HEALTH_SYMPTOM -> json.optionalStrings("description")
            EventType.isMilestone(record.type) ->
                json.optionalStrings("name", "description") && json.optionalBoolean("isFirst")
            record.type == EventType.OTHER_CUSTOM -> json.optionalStrings("name", "description")
            else -> json.optional("data") { it.isJsonObject }
        }
    }

    /** 协议只接受可完成的业务事实，不能夹带另一设备的运行锚点或不一致的派生汇总。 */
    private fun validTiming(
        timing: TimerMetadata?, kind: TimerRecordKind, start: Long, end: Long,
        duration: Long?, left: Long? = null, right: Long? = null
    ): Boolean {
        if (timing == null) return true
        if (timing.clockAnchor != null || timing.automaticStart) return false
        return runCatching {
            TimerMetadataCodec.validateRecord(timing, kind, start, end, duration, left, right)
        }.isSuccess
    }

    /** 可选字段允许旧版缺失/null，但存在时必须与实际编辑/统计 DTO 的类型一致。 */
    private fun JsonObject.optional(key: String, accepts: (JsonElement) -> Boolean): Boolean {
        val value = get(key) ?: return true
        return value.isJsonNull || accepts(value)
    }

    private fun JsonObject.optionalStrings(vararg keys: String): Boolean = keys.all { key ->
        optional(key) { it.isJsonPrimitive && it.asJsonPrimitive.isString }
    }

    private fun JsonObject.optionalBoolean(key: String): Boolean =
        optional(key) { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }

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
            name.isNotBlank() &&
            gender.isPresent() &&
            birthDate >= 0L &&
            birthWeight.isFinite() && birthWeight >= 0f &&
            birthHeight.isFinite() && birthHeight >= 0f &&
            bloodType.isPresent() && extra.isPresent()

    // 自由文本与本地编辑能力一致，只受整包字符上限约束；Gson 的 null 仍须拒绝。
    private fun String?.isPresent(): Boolean = this != null

    private fun String.isUuid(): Boolean =
        runCatching { UUID.fromString(this) }.getOrNull()?.toString() == lowercase()

    private fun String.isValidTransportId(): Boolean = isNotBlank() && length <= MAX_TRANSPORT_ID_LENGTH

    private inline fun <T, K> List<T>.hasDuplicateBy(keySelector: (T) -> K): Boolean {
        val keys = HashSet<K>(size)
        return any { !keys.add(keySelector(it)) }
    }

    private const val MAX_BACKUP_JSON_CHARS = BackupJsonLimits.MAX_JSON_CHARS
    private const val MAX_BACKUP_RECORDS = BackupJsonLimits.MAX_RECORDS
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
