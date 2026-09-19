package com.zero.babydata.backup

import com.zero.babydata.entity.EventRecord
import com.zero.babydata.entity.EventType
import com.zero.babydata.entity.FeedingRecord
import com.zero.babydata.entity.SleepRecord
import com.zero.babydata.entity.MedicineData
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupRecordDeduplicatorTest {
    @Test
    fun medicineLanguageAliasesDeduplicateWithoutChangingUnknownUnitsOrStoredJson() {
        val index = BackupRecordDeduplicator(60_000)
        val record = EventRecord(babyId = 1, type = EventType.HEALTH_MEDICINE, time = 100_000,
            extraData = MedicineData("合成药品", "1", "tablet").toJson())
        index.remember(record)
        listOf("片", "錠", "정").forEach { unit ->
            val legacy = record.copy(time = 100_001, extraData = MedicineData("合成药品", "1", unit).toJson())
            val before = legacy.extraData
            assertFalse(index.add(legacy))
            org.junit.Assert.assertEquals(before, legacy.extraData)
        }
        assertTrue(index.add(record.copy(extraData = MedicineData("合成药品", "1", "spray").toJson())))
        assertTrue(index.add(record.copy(extraData = MedicineData("合成药品", "1", "drop").toJson())))
        assertFalse(index.add(record.copy(extraData = MedicineData("合成药品", "1", "방울").toJson())))
    }

    @Test
    fun recordsOneSecondApartDeduplicateAcrossMinuteBoundaryAndCreationTimes() {
        val index = BackupRecordDeduplicator(60_000)
        val feeding = FeedingRecord(babyId = 1, feedingStart = 59_500, feedingEnd = 119_500,
            feedingDuration = 60_000, feedingAmount = 120, createdAt = 200_000)
        val sleep = SleepRecord(babyId = 1, sleepStart = 59_500, sleepEnd = 119_500,
            sleepDuration = 60_000, createdAt = 200_000)
        val event = EventRecord(babyId = 1, type = EventType.DIAPER_WET, time = 59_500, createdAt = 200_000)
        index.remember(feeding)
        index.remember(sleep)
        index.remember(event)
        assertFalse(index.add(feeding.copy(feedingStart = 60_500, feedingEnd = 120_500, createdAt = 300_000)))
        assertFalse(index.add(sleep.copy(sleepStart = 60_500, sleepEnd = 120_500, createdAt = 300_000)))
        assertFalse(index.add(event.copy(time = 60_500, createdAt = 300_000)))
    }

    @Test
    fun toleranceIncludesBoundaryAndPreservesBeyondBoundaryRecords() {
        val index = BackupRecordDeduplicator(60_000)
        val record = SleepRecord(babyId = 1, sleepStart = 100_000, sleepEnd = 200_000, sleepDuration = 100_000)
        index.remember(record)
        assertFalse(index.add(record.copy(sleepStart = 160_000, sleepEnd = 260_000)))
        assertTrue(index.add(record.copy(sleepStart = 160_001, sleepEnd = 260_001)))
    }

    @Test
    fun zeroToleranceRequiresExactBusinessTimesButIgnoresWriteMetadata() {
        val index = BackupRecordDeduplicator(0)
        val record = SleepRecord(babyId = 1, sleepStart = 1000, sleepEnd = 2000, sleepDuration = 1000)
        index.remember(record)
        assertFalse(index.add(record.copy(sleepId = 99, createdAt = 9000)))
        assertTrue(index.add(record.copy(sleepStart = 1001)))
    }

    @Test
    fun differentBabiesAmountsNotesAndActualDurationsRemainSeparate() {
        val index = BackupRecordDeduplicator(60_000)
        val record = FeedingRecord(babyId = 1, feedingStart = 100_000, feedingEnd = 300_000,
            feedingDuration = 200_000, feedingAmount = 120, note = "原备注")
        index.remember(record)
        assertTrue(index.add(record.copy(babyId = 2)))
        assertTrue(index.add(record.copy(feedingAmount = 150)))
        assertTrue(index.add(record.copy(note = "新备注")))
        assertTrue(index.add(record.copy(feedingDuration = 100_000)))
    }

    @Test
    fun jsonKeyOrderIsIrrelevantButMissingEndTimeIsNotARealEndTime() {
        val index = BackupRecordDeduplicator(60_000)
        val record = EventRecord(babyId = 1, type = EventType.GROWTH_WEIGHT, time = 1000,
            extraData = "{\"unit\":\"kg\",\"value\":4}")
        index.remember(record)
        assertFalse(index.add(record.copy(extraData = "{\"value\":4.0,\"unit\":\"kg\"}")))
        assertTrue(index.add(record.copy(endTime = 2000)))
    }

    @Test
    fun everyExistingRecordRemainsAMatchCandidate() {
        val index = BackupRecordDeduplicator(60_000)
        val record = EventRecord(babyId = 1, type = EventType.DIAPER_WET, time = 60_000)
        index.remember(record)
        index.remember(record.copy(time = 110_000))
        assertFalse(index.add(record.copy(time = 160_000)))
    }

    @Test
    fun skippedImportsCannotExtendToleranceTransitively() {
        val index = BackupRecordDeduplicator(60_000)
        val record = EventRecord(babyId = 1, type = EventType.DIAPER_WET, time = 60_000)
        assertTrue(index.add(record))
        assertFalse(index.add(record.copy(time = 110_000)))
        assertTrue(index.add(record.copy(time = 160_000)))
    }
}
