package com.zero.babydata.backup

import com.zero.babydata.entity.BabyInfo
import com.zero.babydata.entity.EventRecord
import com.zero.babydata.entity.EventType
import com.zero.babydata.entity.FeedingRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupJsonEncoderTest {
    private val baby = BabyInfo(name = "宝宝".repeat(201))
    private fun envelope() = BabyBackupEnvelope(BABY_BACKUP_VERSION, 1000, data = BabyBackupData(
        babies = listOf(baby.toBackup()), feedingRecords = emptyList(), sleepRecords = emptyList(),
        eventRecords = emptyList(), childDailyRecords = emptyList()
    ))

    @Test fun longLocalTextRoundTripsWithoutTruncation() {
        val feeding = FeedingRecord(feedingId = 1, feedingStart = 1000, feedingEnd = 2000,
            feedingDuration = 1000, note = "长备注".repeat(4000), foodName = "食物".repeat(201))
        val extra = "{\"description\":\"" + "长描述".repeat(34_000) + "\",\"future\":{\"x\":[1,2]}}"
        val event = EventRecord(eventId = 1, type = EventType.HEALTH_SYMPTOM, time = 1000, extraData = extra)
        val original = envelope().let { it.copy(data = it.data.copy(
            feedingRecords = listOf(feeding.toBackup(baby.stableId)),
            eventRecords = listOf(event.toBackup(baby.stableId))
        )) }
        assertEquals(original, BabyBackupParser.parse(BackupJsonEncoder.encode(original)))
    }

    @Test fun invalidDataAndOverLimitExportHaveDistinctFailures() {
        val invalid = envelope().let { it.copy(data = it.data.copy(babies = listOf(baby.copy(birthDate = -1).toBackup()))) }
        assertEquals(BackupExportError.INVALID_DATA,
            assertThrows(BackupExportException::class.java) { BackupJsonEncoder.encode(invalid) }.error)
        val tooMany = envelope().let { it.copy(data = it.data.copy(babies = List(BackupJsonLimits.MAX_RECORDS + 1) { baby.toBackup() })) }
        assertEquals(BackupExportError.LIMIT_EXCEEDED,
            assertThrows(BackupExportException::class.java) { BackupJsonEncoder.encode(tooMany) }.error)
        val tooLarge = envelope().let { it.copy(data = it.data.copy(babies = listOf(baby.copy(name = "x".repeat(BackupJsonLimits.MAX_JSON_CHARS)).toBackup()))) }
        assertEquals(BackupExportError.LIMIT_EXCEEDED,
            assertThrows(BackupExportException::class.java) { BackupJsonEncoder.encode(tooLarge) }.error)
    }

    @Test fun boundedWriterRejectsBeforeAppendingAnyPartOfOverflowingChunk() {
        val writer = BackupJsonEncoder.LimitedWriter(10)
        writer.write("12345678")
        writer.write(charArrayOf('9', '0'), 0, 2)
        assertEquals("1234567890", writer.toString())
        assertThrows(BackupExportException::class.java) { writer.write("overflow") }
        assertEquals("1234567890", writer.toString())
        assertThrows(BackupExportException::class.java) { writer.write('x'.code) }
        assertEquals("1234567890", writer.toString())
    }
}
