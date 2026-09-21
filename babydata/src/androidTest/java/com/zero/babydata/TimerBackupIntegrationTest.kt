package com.zero.babydata

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.babydata.backup.BabyBackupExporter
import com.zero.babydata.backup.BabyBackupImporter
import com.zero.babydata.backup.BabyBackupParser
import com.zero.babydata.domain.TimerMetadata
import com.zero.babydata.domain.TimerMetadataCodec
import com.zero.babydata.domain.TimerPart
import com.zero.babydata.domain.TimerSegment
import com.zero.babydata.domain.TimerStatus
import com.zero.babydata.entity.BabyInfo
import com.zero.babydata.entity.EventRecord
import com.zero.babydata.entity.EventType
import com.zero.babydata.entity.FeedingRecord
import com.zero.babydata.entity.SleepRecord
import com.zero.babydata.room.BabyDatabase
import com.zero.babydata.room.BabyRepository
import com.zero.common.util.TimerClockSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

/** 独立内存库串起真实导出、解析、去重与落库；不读取用户数据库。 */
@RunWith(AndroidJUnit4::class)
class TimerBackupIntegrationTest {
    @Test
    fun completedSegmentsRoundTripPreservesDayTotalsAndNeverRestoresRunningState() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, BabyDatabase::class.java).build()
        try {
            val babyId = db.babyInfoDao().insertBabyInfo(BabyInfo(name = "合成计时宝宝")).toInt()
            val zone = ZoneId.of("UTC")
            val date = LocalDate.of(2026, 9, 20)
            val midnight = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val minute = 60_000L
            val first = TimerSegment(midnight - 10 * minute, midnight - 5 * minute, 5 * minute, TimerPart.LEFT)
            val second = TimerSegment(midnight + 5 * minute, midnight + 20 * minute, 15 * minute, TimerPart.RIGHT)
            val source = TimerMetadata(segments = listOf(first, second),
                clockAnchor = TimerClockSnapshot(midnight, 1_000, 8, "synthetic-device"), automaticStart = true)
            val sleepSource = source.copy(segments = source.segments.map { it.copy(part = TimerPart.SLEEP) })
            val eventSource = source.copy(segments = source.segments.map { it.copy(part = TimerPart.ACTIVITY) })
            db.feedingRecordDao().insertFeedingRecord(FeedingRecord(babyId = babyId,
                feedingStart = first.startAt, feedingEnd = second.endAt!!, feedingDuration = 20 * minute,
                feedingDurationBreastLeft = 5 * minute, feedingDurationBreastRight = 15 * minute,
                timerSessionId = "synthetic-feed", timerRevision = 4, timerData = TimerMetadataCodec.encode(source)))
            db.sleepRecordDao().insertSleepRecord(SleepRecord(babyId = babyId,
                sleepStart = first.startAt, sleepEnd = second.endAt!!, sleepDuration = 20 * minute,
                timerSessionId = "synthetic-sleep", timerRevision = 4, timerData = TimerMetadataCodec.encode(sleepSource)))
            db.eventRecordDao().insertEventRecord(EventRecord(babyId = babyId, type = EventType.ACTIVITY_TUMMY_TIME,
                time = first.startAt, endTime = second.endAt!!, activeDurationMs = 20 * minute,
                timerSessionId = "synthetic-event", timerRevision = 4, timerData = TimerMetadataCodec.encode(eventSource)))
            // 结束待保存与运行中都是设备上的未完成事实，不能混入普通历史备份。
            db.sleepRecordDao().insertSleepRecord(SleepRecord(babyId = babyId, sleepStart = midnight,
                timerStatus = TimerStatus.RUNNING.code, timerSessionId = "unfinished-sleep", timerSlot = 1))
            db.feedingRecordDao().insertFeedingRecord(FeedingRecord(babyId = babyId, feedingStart = midnight,
                timerStatus = TimerStatus.STOPPED.code, timerSessionId = "unfinished-feed", timerSlot = 1))
            db.eventRecordDao().insertEventRecord(EventRecord(babyId = babyId, type = EventType.ACTIVITY_OUTDOOR,
                time = midnight, timerStatus = TimerStatus.PAUSED.code, timerSessionId = "unfinished-event",
                timerSlot = EventType.ACTIVITY_OUTDOOR))

            val exporter = BabyBackupExporter(db)
            val envelope = exporter.buildEnvelope()
            assertEquals(1, envelope.data.feedingRecords.size)
            assertEquals(1, envelope.data.sleepRecords.size)
            assertEquals(1, envelope.data.eventRecords.size)
            val json = exporter.toJson(envelope)
            assertFalse(json.contains("synthetic-device"))
            assertFalse(json.contains("unfinished-"))
            assertFalse(json.contains("timerSessionId"))
            val parsed = BabyBackupParser.parse(json)
            val importer = BabyBackupImporter(db)
            importer.importOverwrite(parsed)
            val restoredBaby = db.babyInfoDao().getAllBabyInfo().single()
            val repository = BabyRepository(db)
            val before = repository.getDaySummary(restoredBaby.babyId, date.minusDays(1), zone)
            val after = repository.getDaySummary(restoredBaby.babyId, date, zone)
            assertEquals(1, before.feedingCount)
            assertEquals(1, before.sleepCount)
            assertEquals(0, after.feedingCount)
            assertEquals(0, after.sleepCount)
            assertEquals(5, before.feedingTotalMinutes)
            assertEquals(15, after.feedingTotalMinutes)
            assertEquals(5, before.sleepTotalMinutes)
            assertEquals(15, after.sleepTotalMinutes)
            val restored = db.sleepRecordDao().getAllSleepRecords(restoredBaby.babyId).single()
            assertNull(restored.timerSessionId)
            assertNull(restored.timerSlot)
            assertEquals(TimerStatus.COMPLETED.code, restored.timerStatus)
            assertEquals(sleepSource.copy(clockAnchor = null, automaticStart = false), TimerMetadataCodec.decode(restored.timerData))
            assertEquals(20 * minute, db.eventRecordDao().getAllEventRecord(restoredBaby.babyId).single().activeDurationMs)

            val repeated = importer.importMerge(parsed, 0)
            assertEquals(0, repeated.feedingInserted + repeated.sleepInserted + repeated.eventInserted)
            assertEquals(3, repeated.feedingSkipped + repeated.sleepSkipped + repeated.eventSkipped)
            // 首尾与总量相同，暂停分布不同仍是不同内容，不能因容差被误删。
            val changedSleep = parsed.data.sleepRecords.single().let { record ->
                record.copy(timing = record.timing!!.copy(segments = listOf(
                    first.copy(endAt = midnight, durationMs = 10 * minute, part = TimerPart.SLEEP),
                    second.copy(startAt = midnight + 10 * minute, durationMs = 10 * minute, part = TimerPart.SLEEP)
                )))
            }
            val changed = parsed.copy(data = parsed.data.copy(sleepRecords = listOf(changedSleep)))
            assertEquals(1, importer.importMerge(BabyBackupParser.parse(exporter.toJson(changed)), 1).sleepInserted)
            assertEquals(0, importer.importMerge(changed, 1).sleepInserted)
        } finally {
            db.close()
        }
    }
}
