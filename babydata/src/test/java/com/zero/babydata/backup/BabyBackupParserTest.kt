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
