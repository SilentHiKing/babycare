package com.zero.babydata.backup

import com.google.gson.Gson
import com.zero.babydata.domain.DurationBasis
import com.zero.babydata.domain.TimerMetadata
import com.zero.babydata.domain.TimerMetadataCodec
import com.zero.babydata.domain.TimerPart
import com.zero.babydata.domain.TimerSegment
import com.zero.babydata.domain.TimerStatus
import com.zero.babydata.entity.BabyInfo
import com.zero.babydata.entity.EventRecord
import com.zero.babydata.entity.EventType
import com.zero.babydata.entity.FeedingRecord
import com.zero.babydata.entity.FeedingType
import com.zero.babydata.entity.SleepRecord
import com.zero.common.util.TimerClockSnapshot
import org.junit.Assert.*
import org.junit.Test

class TimerBackupTest {
    private val baby = BabyInfo(name = "合成宝宝")
    private val gson = Gson()
    private val timing = TimerMetadata(segments = listOf(
        TimerSegment(1_000, 2_000, 1_000, TimerPart.SLEEP),
        TimerSegment(4_000, 7_000, 3_000, TimerPart.SLEEP)
    ))

    @Test
    fun completedSegmentsRoundTripWithoutDeviceClockOrSessionIdentity() {
        val local = SleepRecord(sleepId = 4, babyId = 1, sleepStart = 1_000, sleepEnd = 7_000,
            sleepDuration = 4_000, timerSessionId = "local-device-session", timerRevision = 8,
            timerData = TimerMetadataCodec.encode(timing.copy(
                clockAnchor = TimerClockSnapshot(7_000, 55_000, 2, "private-process"), automaticStart = true)))
        val backup = local.toBackup(baby.stableId)
        assertEquals(timing, backup.timing)
        val json = BackupJsonEncoder.encode(envelope(backup))
        assertFalse(json.contains("clockAnchor"))
        assertFalse(json.contains("local-device-session"))
        val restored = BabyBackupParser.parse(json).data.sleepRecords.single().toEntity(9)
        assertEquals(timing, TimerMetadataCodec.decode(restored.timerData))
        assertEquals(4_000L, restored.sleepDuration)
        assertEquals(TimerStatus.COMPLETED.code, restored.timerStatus)
        assertNull(restored.timerSessionId)
        assertNull(restored.timerSlot)
        assertEquals(0L, restored.timerRevision)
    }

    @Test
    fun inconsistentOpenWrongKindOrDeviceBoundSegmentsCannotBeImported() {
        val valid = SleepRecord(sleepStart = 1_000, sleepEnd = 7_000, sleepDuration = 4_000,
            timerData = TimerMetadataCodec.encode(timing)).toBackup(baby.stableId)
        listOf(
            valid.copy(sleepDuration = 6_000),
            valid.copy(sleepEnd = 8_000),
            valid.copy(timing = timing.copy(segments = timing.segments.map { it.copy(part = TimerPart.LEFT) })),
            valid.copy(timing = timing.copy(segments = listOf(TimerSegment(1_000, part = TimerPart.SLEEP)))),
            valid.copy(timing = timing.copy(clockAnchor = TimerClockSnapshot(7_000))),
            valid.copy(timing = timing.copy(automaticStart = true)),
            valid.copy(timing = timing.copy(requiresReview = true)),
            valid.copy(timing = timing.copy(segments = listOf(
                TimerSegment(1_000, 5_000, 4_000, TimerPart.SLEEP),
                TimerSegment(4_000, 7_000, 3_000, TimerPart.SLEEP))))
        ).forEach { record ->
            val error = assertThrows(BackupImportException::class.java) {
                BabyBackupParser.parse(gson.toJson(envelope(record)))
            }
            assertEquals(BackupImportError.INVALID_JSON, error.error)
        }
        val oldVersion = assertThrows(BackupImportException::class.java) {
            BabyBackupParser.parse(gson.toJson(envelope(valid).copy(version = 1)))
        }
        assertEquals(BackupImportError.UNSUPPORTED_VERSION, oldVersion.error)
    }

    @Test
    fun sameTotalWithDifferentPauseLocationIsNotDiscardedByMergeTolerance() {
        val record = SleepRecord(babyId = 1, sleepStart = 1_000, sleepEnd = 7_000, sleepDuration = 4_000,
            timerData = TimerMetadataCodec.encode(timing))
        val different = record.copy(timerData = TimerMetadataCodec.encode(timing.copy(segments = listOf(
            TimerSegment(1_000, 3_000, 2_000, TimerPart.SLEEP),
            TimerSegment(5_000, 7_000, 2_000, TimerPart.SLEEP)
        ))))
        val index = BackupRecordDeduplicator(60_000)
        index.remember(record)
        assertTrue(index.add(different))
        assertFalse(index.add(different.copy()))
        // JSON 字段顺序和本机锚点不改变完成的业务片段。
        assertFalse(index.add(record.copy(timerData = TimerMetadataCodec.encode(timing.copy(
            clockAnchor = TimerClockSnapshot(7_000), automaticStart = true)))))
    }

    @Test
    fun unmeasuredFeedingAndMeasuredActivityKeepTheirDistinctDurationSources() {
        val feeding = FeedingRecord(feedingType = FeedingType.FORMULA.type, feedingStart = 1_000, feedingEnd = 0, feedingAmount = 90,
            timerData = TimerMetadataCodec.encode(TimerMetadata(basis = DurationBasis.UNMEASURED)))
            .toBackup(baby.stableId)
        val eventTiming = timing.copy(segments = timing.segments.map { it.copy(part = TimerPart.ACTIVITY) })
        val event = EventRecord(type = EventType.ACTIVITY_TUMMY_TIME, time = 1_000, endTime = 7_000,
            activeDurationMs = 4_000, timerData = TimerMetadataCodec.encode(eventTiming)).toBackup(baby.stableId)
        val data = envelope().data.copy(feedingRecords = listOf(feeding), eventRecords = listOf(event))
        val parsed = BabyBackupParser.parse(BackupJsonEncoder.encode(envelope().copy(data = data)))
        assertEquals(DurationBasis.UNMEASURED, parsed.data.feedingRecords.single().timing?.basis)
        assertEquals(4_000L, parsed.data.eventRecords.single().activeDurationMs)
        assertEquals(eventTiming, parsed.data.eventRecords.single().timing)
    }

    private fun envelope(sleep: BackupSleepRecord? = null) = BabyBackupEnvelope(
        BABY_BACKUP_VERSION, 10_000, data = BabyBackupData(listOf(baby.toBackup()), emptyList(),
            listOfNotNull(sleep), emptyList(), emptyList())
    )
}
