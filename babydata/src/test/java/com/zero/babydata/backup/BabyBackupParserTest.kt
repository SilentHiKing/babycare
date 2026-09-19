package com.zero.babydata.backup

import com.google.gson.Gson
import com.zero.babydata.entity.BabyInfo
import com.zero.babydata.entity.ChildDailyRecord
import com.zero.babydata.entity.EventRecord
import com.zero.babydata.entity.EventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.StringReader

class BabyBackupParserTest {
    private val baby = BabyInfo(name = "测试宝宝")
    private val gson = Gson()

    @Test
    fun nameOnlyProfileCanRoundTripThroughBackup() {
        val envelope = envelope()
        assertEquals(envelope, BabyBackupParser.parse(gson.toJson(envelope)))
    }

    @Test
    fun legacySameDayMeasurementsArePreservedInBackup() {
        val records = listOf(
            ChildDailyRecord(recordId = 1, recordDate = 1000, weight = 4f),
            ChildDailyRecord(recordId = 2, recordDate = 1000, weight = 5f)
        ).map { it.toBackup(baby.stableId) }
        val envelope = envelope().let { it.copy(data = it.data.copy(childDailyRecords = records)) }
        assertEquals(records, BabyBackupParser.parse(gson.toJson(envelope)).data.childDailyRecords)
    }

    @Test
    fun negativeBirthdayIsStillRejected() {
        val invalid = envelope().let {
            it.copy(data = it.data.copy(babies = listOf(baby.copy(birthDate = -1).toBackup())))
        }
        assertInvalid(gson.toJson(invalid))
    }

    @Test
    fun malformedNestedEventsAreRejectedBeforeImport() {
        listOf(
            EventType.GROWTH_WEIGHT to "{}",
            EventType.GROWTH_WEIGHT to "{\"value\":4,\"unit\":null}",
            EventType.GROWTH_WEIGHT to "{\"value\":1e999,\"unit\":\"kg\"}",
            EventType.GROWTH_HEIGHT to "{\"value\":50,\"unit\":\"kg\"}",
            EventType.HEALTH_MEDICINE to "{}",
            EventType.HEALTH_VACCINE to "{\"name\":null}",
            EventType.HEALTH_TEMPERATURE to "{\"value\":37}",
            EventType.DIAPER_WET to "[]"
        ).forEach { (type, extra) ->
            assertInvalid(gson.toJson(withEvent(type, extra)))
        }
    }

    @Test
    fun validAndLegacyEventsRemainReadable() {
        listOf(
            EventType.GROWTH_WEIGHT to "{\"value\":4,\"unit\":\"kg\"}",
            EventType.HEALTH_TEMPERATURE to "{\"value\":37,\"location\":\"ear\"}",
            EventType.HEALTH_MEDICINE to "{\"name\":\"测试\"}",
            EventType.DIAPER_WET to "{}",
            EventType.GROWTH_WEIGHT to ""
        ).forEach { (type, extra) ->
            val expected = withEvent(type, extra)
            assertEquals(expected, BabyBackupParser.parse(gson.toJson(expected)))
        }
    }

    @Test
    fun missingOrNullRequiredCollectionsAreRejected() {
        assertInvalid("{\"version\":1,\"createdAt\":1000,\"data\":null}")
        assertInvalid("{\"version\":1,\"createdAt\":1000,\"data\":{}}")
    }

    @Test
    fun streamingReaderStopsAtSizeLimit() {
        val reader = StringReader("0123456789abcdefghijklmnopqrstuvwxyz")
        val error = assertThrows(BackupImportException::class.java) {
            BabyBackupParser.readLimited(reader, maxChars = 10)
        }
        assertEquals(BackupImportError.INVALID_JSON, error.error)
        // 只读到限制后一字符即可失败，不继续消耗巨大文件。
        assertEquals('b'.code, reader.read())
        assertEquals("0123456789", BabyBackupParser.readLimited(StringReader("0123456789"), 10))
    }

    @Test
    fun deeplyNestedOuterAndEventJsonAreRejectedBeforeObjectConstruction() {
        val nested = "[".repeat(5_000) + "0" + "]".repeat(5_000)
        assertInvalid(gson.toJson(envelope()).dropLast(1) + ",\"unknown\":$nested}")
        val event = withEvent(EventType.DIAPER_WET, "{\"unknown\":$nested}")
        assertInvalid(gson.toJson(event))
        // 直接调用 importer 的 DTO 校验入口也不能绕过扩展 JSON 深度限制。
        assertThrows(BackupImportException::class.java) { BabyBackupParser.validateEnvelope(event) }
        val wide = "{\"unknown\":[" + "0,".repeat(BackupJsonLimits.MAX_EXTRA_VALUES) + "0]}"
        assertInvalid(gson.toJson(withEvent(EventType.DIAPER_WET, wide)))
    }

    @Test
    fun recordCountIsLimitedBeforeGsonAllocatesMillionsOfEmptyObjects() {
        val prefix = "{\"data\":{\"babies\":["
        val records = "{},".repeat(BackupJsonLimits.MAX_RECORDS)
        BackupJsonLimits.validate(prefix + records.dropLast(1) + "]}}", countRecords = true)
        // 后面故意缺少结束符；必须在第 200001 条出现时受控拒绝，而不是继续分配或读到语法错误。
        assertThrows(BackupImportException::class.java) {
            BackupJsonLimits.validate(prefix + records + "{}", countRecords = true)
        }
    }

    @Test
    fun optionalEventFieldsMustMatchTheirActualDtoTypes() {
        listOf(
            EventType.HEALTH_MEDICINE to "{\"name\":\"测试\",\"dosage\":[]}",
            EventType.HEALTH_MEDICINE to "{\"name\":\"测试\",\"unit\":{}}",
            EventType.HEALTH_VACCINE to "{\"name\":\"测试\",\"dose\":2147483648}",
            EventType.HEALTH_VACCINE to "{\"name\":\"测试\",\"dose\":1.5}",
            EventType.HEALTH_VACCINE to "{\"name\":\"测试\",\"site\":[]}",
            EventType.DIAPER_WET to "{\"color\":[],\"abnormal\":true}",
            EventType.DIAPER_WET to "{\"abnormal\":\"true\"}",
            EventType.HEALTH_SYMPTOM to "{\"description\":[]}",
            EventType.MILESTONE_ROLL to "{\"isFirst\":{}}",
            EventType.OTHER_CUSTOM to "{\"name\":[]}",
            EventType.ACTIVITY_PLAY to "{\"data\":[]}"
        ).forEach { (type, extra) -> assertInvalid(gson.toJson(withEvent(type, extra))) }
    }

    @Test
    fun legalOptionalAndUnknownEventFieldsKeepTheirOriginalJson() {
        listOf(
            EventType.HEALTH_MEDICINE to "{\"name\":\"测试\",\"dosage\":null,\"unit\":\"未知旧单位\",\"future\":{\"x\":[1,2]}}",
            EventType.HEALTH_VACCINE to "{\"name\":\"测试\",\"dose\":2,\"site\":null}",
            EventType.DIAPER_WET to "{\"color\":null,\"abnormal\":true}",
            EventType.HEALTH_SYMPTOM to "{\"description\":null}",
            EventType.MILESTONE_ROLL to "{\"isFirst\":false,\"name\":null}",
            EventType.OTHER_CUSTOM to "{\"name\":null,\"description\":\"旧描述\"}",
            EventType.ACTIVITY_PLAY to "{\"data\":{\"future\":[1,{\"flag\":true}]}}"
        ).forEach { (type, extra) ->
            val parsed = BabyBackupParser.parse(gson.toJson(withEvent(type, extra)))
            assertEquals(extra, parsed.data.eventRecords.single().extraData)
        }
    }

    private fun envelope() = BabyBackupEnvelope(
        version = BABY_BACKUP_VERSION,
        createdAt = 1000,
        data = BabyBackupData(listOf(baby.toBackup()), emptyList(), emptyList(), emptyList(), emptyList())
    )

    private fun withEvent(type: Int, extra: String): BabyBackupEnvelope {
        val record = EventRecord(eventId = 1, type = type, time = 1000, extraData = extra).toBackup(baby.stableId)
        return envelope().let { it.copy(data = it.data.copy(eventRecords = listOf(record))) }
    }

    private fun assertInvalid(json: String) {
        val error = assertThrows(BackupImportException::class.java) { BabyBackupParser.parse(json) }
        assertEquals(BackupImportError.INVALID_JSON, error.error)
    }
}
