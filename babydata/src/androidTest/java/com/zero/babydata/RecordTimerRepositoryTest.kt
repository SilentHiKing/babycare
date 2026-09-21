package com.zero.babydata

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.babydata.domain.*
import com.zero.babydata.entity.*
import com.zero.babydata.room.BabyDatabase
import com.zero.babydata.room.BabyRepository
import com.zero.common.util.TimerClockSnapshot
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

/** 独立 Room 合成库覆盖真实事务与约束，不写应用宝宝、计时或提醒状态。 */
@RunWith(AndroidJUnit4::class)
class RecordTimerRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun clock(time: Long) = TimerClockSnapshot(time, time, 1, "timer-test")

    @Test
    fun concurrentStartsReuseOneSlotButBabiesAndActivityTypesStayIndependent() = runBlocking {
        withDatabase { db, repository, baby ->
            val timers = repository.timers
            val starts = coroutineScope {
                (1..8).map { async(Dispatchers.IO) { timers.startFeeding(FeedingRecord(babyId = baby), clock(10_000)) } }.awaitAll()
            }
            assertEquals(1, starts.map { it.ref }.toSet().size)
            val otherBaby = db.babyInfoDao().insertBabyInfo(BabyInfo(name = "另一个合成宝宝")).toInt()
            assertNotEquals(starts.first().ref.sessionId,
                timers.startFeeding(FeedingRecord(babyId = otherBaby), clock(10_000)).ref.sessionId)
            timers.startSleep(SleepRecord(babyId = baby), clock(10_000))
            timers.startEvent(EventRecord(babyId = baby, type = EventType.ACTIVITY_TUMMY_TIME), clock(10_000))
            timers.startEvent(EventRecord(babyId = baby, type = EventType.CARE_BATH), clock(10_000))
            assertEquals(2, db.eventRecordDao().getUnfinished(baby).size)
            val duplicate = requireNotNull(starts.first().feeding).copy(feedingId = 0, timerSessionId = UUID.randomUUID().toString())
            assertTrue(runCatching { db.feedingRecordDao().insertFeedingRecord(duplicate) }.isFailure)
            assertEquals(0L, db.feedingRecordDao().countForBackup())
            assertTrue(repository.getAllFeedingRecords(baby).isEmpty())
            assertTrue(repository.getAllSleepRecords(baby).isEmpty())
            assertTrue(repository.getAllEventRecords(baby).isEmpty())
        }
    }

    @Test
    fun pauseStopFinalizeAndRetryKeepSameRecordAndExactSideTotals() = runBlocking {
        withDatabase { db, repository, baby ->
            val timers = repository.timers
            val started = timers.startFeeding(FeedingRecord(babyId = baby, createdAt = 7), clock(10_000), TimerPart.LEFT)
            val paused = timers.pause(started.ref, clock(20_000))
            val resumed = timers.resume(paused.ref, clock(40_000), TimerPart.RIGHT)
            val stopped = timers.stop(resumed.ref, clock(55_000))
            assertEquals(TimerStatus.STOPPED, stopped.status)
            assertEquals(stopped, timers.stop(stopped.ref, clock(99_000)))
            assertTrue(repository.getAllFeedingRecords(baby).isEmpty())
            val payload = requireNotNull(stopped.feeding).copy(note = "合成备注", feedingAmount = 80)
            val completed = timers.finalize(stopped.ref, feeding = payload)
            assertEquals(started.ref.recordId, completed.ref.recordId)
            assertEquals(25_000L, completed.durationMs)
            assertEquals(10_000L, completed.feeding?.feedingDurationBreastLeft)
            assertEquals(15_000L, completed.feeding?.feedingDurationBreastRight)
            assertEquals(completed, timers.finalize(stopped.ref, feeding = payload))
            assertEquals(1, repository.getAllFeedingRecords(baby).size)
            assertEquals(1, receiptCount(db))
            assertNull(timers.findUnfinished(TimerRecordKind.FEEDING, baby))
            assertTimerFailure(TimerFailure.INVALID_TRANSITION) {
                timers.updateDraft(completed.ref, feeding = payload.copy(note = "不能从草稿旁路修改已完成记录"))
            }
            assertEquals("合成备注", repository.getFeedingRecordById(completed.ref.recordId)?.note)
            val next = timers.startFeeding(FeedingRecord(babyId = baby), clock(60_000))
            assertNotEquals(started.ref.sessionId, next.ref.sessionId)
            assertTimerFailure(TimerFailure.INVALID_TRANSITION) { timers.discard(completed.ref) }
            assertEquals(next.ref, timers.findUnfinished(TimerRecordKind.FEEDING, baby)?.ref)
        }
    }

    @Test
    fun reopenedStoppedRecordSurvivesFinalizeFailureAndRejectsLateCommands() = runBlocking {
        val name = "timer-transaction-${UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(context, BabyDatabase::class.java, name).build()
        try {
            val baby = db.babyInfoDao().insertBabyInfo(BabyInfo(name = "合成宝宝")).toInt()
            var repository = BabyRepository(db)
            val started = repository.timers.startSleep(SleepRecord(babyId = baby, note = "停止前输入"), clock(10_000))
            val stopped = repository.timers.stop(started.ref, clock(20_000))
            assertTimerFailure(TimerFailure.STALE_REVISION) { repository.timers.editStart(started.ref, 11_000, clock(30_000)) }
            db.close()
            db = Room.databaseBuilder(context, BabyDatabase::class.java, name).build()
            repository = BabyRepository(db)
            assertEquals(stopped, repository.timers.findUnfinished(TimerRecordKind.SLEEP, baby))
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_timer_receipt BEFORE INSERT ON Record_Write_Receipts BEGIN SELECT RAISE(ABORT,'synthetic failure'); END")
            assertTrue(runCatching { repository.timers.finalize(stopped.ref) }.isFailure)
            val retained = repository.timers.findUnfinished(TimerRecordKind.SLEEP, baby)
            assertEquals(stopped, retained)
            assertEquals(0, receiptCount(db))
            assertTrue(repository.getAllSleepRecords(baby).isEmpty())
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_timer_receipt")
            val completed = repository.timers.finalize(stopped.ref)
            assertEquals(stopped.ref.recordId, completed.ref.recordId)
            assertEquals("停止前输入", completed.sleep?.note)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test
    fun completedHistoryEditsPreserveSegmentsAndUnmeasuredFeedsRemainPredictionSamples() = runBlocking {
        withDatabase { _, repository, baby ->
            val timers = repository.timers
            var snapshot = timers.startFeeding(FeedingRecord(babyId = baby), clock(10_000), TimerPart.LEFT)
            snapshot = timers.pause(snapshot.ref, clock(20_000))
            snapshot = timers.resume(snapshot.ref, clock(40_000), TimerPart.RIGHT)
            snapshot = timers.stop(snapshot.ref, clock(50_000))
            snapshot = timers.finalize(snapshot.ref)
            val original = requireNotNull(snapshot.feeding)
            repository.updateFeedingRecord(original.copy(note = "只改备注"))
            val noteEdited = requireNotNull(repository.getFeedingRecordById(original.feedingId))
            assertEquals(original.timerData, noteEdited.timerData)
            assertEquals(20_000L, noteEdited.feedingDuration)
            repository.updateFeedingRecord(noteEdited.copy(feedingStart = 15_000))
            assertEquals(15_000L, repository.getFeedingRecordById(original.feedingId)?.feedingDuration)
            assertTimerFailure(TimerFailure.STALE_REVISION) { repository.updateFeedingRecord(noteEdited.copy(note = "迟到输入")) }
            val unmeasured = FeedingRecord(babyId = baby, feedingType = FeedingType.FORMULA.type,
                feedingStart = 60_000, feedingAmount = 90,
                timerData = TimerMetadataCodec.encode(TimerMetadata(basis = DurationBasis.UNMEASURED)))
            val id = repository.insertFeedingRecordOnce(unmeasured, UUID.randomUUID().toString())
            val saved = requireNotNull(repository.getFeedingRecordById(id))
            assertNull(RecordDurationCalculator.feedingDuration(saved))
            assertEquals(id, repository.getLastFeedingRecord(baby)?.feedingId)
            assertTrue(repository.getRecentCompletedMilkFeedings(baby, 10).any { it.feedingId == id })
            repository.updateFeedingRecord(saved.copy(note = "未计时只改备注"))
            val unchangedBasis = requireNotNull(repository.getFeedingRecordById(id))
            assertEquals(saved.timerData, unchangedBasis.timerData)
            assertNull(RecordDurationCalculator.feedingDuration(unchangedBasis))
            repository.updateFeedingRecord(unchangedBasis.copy(feedingEnd = 70_000, feedingDuration = 1_000))
            val suppliedEnd = requireNotNull(repository.getFeedingRecordById(id))
            assertEquals(DurationBasis.MANUAL_INTERVAL, TimerMetadataCodec.decode(suppliedEnd.timerData)?.basis)
            assertEquals(10_000L, suppliedEnd.feedingDuration)
            repository.updateFeedingRecord(suppliedEnd.copy(note = "手工区间只改备注"))
            assertEquals(suppliedEnd.timerData, repository.getFeedingRecordById(id)?.timerData)
            val manualTotal = unmeasured.copy(feedingStart = 80_000, feedingEnd = 100_000, feedingDuration = 5_000,
                timerData = TimerMetadataCodec.encode(TimerMetadata(basis = DurationBasis.MANUAL_TOTAL)))
            val manualId = repository.insertFeedingRecordOnce(manualTotal, UUID.randomUUID().toString())
            val manualSaved = requireNotNull(repository.getFeedingRecordById(manualId))
            repository.updateFeedingRecord(manualSaved.copy(note = "手工总量只改备注"))
            val manualEdited = requireNotNull(repository.getFeedingRecordById(manualId))
            assertEquals(manualSaved.timerData, manualEdited.timerData)
            assertEquals(5_000L, manualEdited.feedingDuration)
            // 独立的已有片段记录仍保留真实暂停分布，补填手工记录不能影响其来源。
            assertEquals(DurationBasis.SEGMENTED,
                TimerMetadataCodec.decode(repository.getFeedingRecordById(original.feedingId)?.timerData)?.basis)
        }
    }

    @Test
    fun activityAndSleepSkipFullyPausedDayAndRetainOriginalTimelineIdentity() = runBlocking {
        withDatabase { _, repository, baby ->
            val zone = ZoneId.of("UTC")
            val firstDay = LocalDate.of(2026, 1, 1)
            val start = firstDay.atTime(23, 50).atZone(zone).toInstant().toEpochMilli()
            val firstEnd = firstDay.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val resume = firstDay.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
            var sleep = repository.timers.startSleep(SleepRecord(babyId = baby), clock(start))
            sleep = repository.timers.pause(sleep.ref, clock(firstEnd))
            sleep = repository.timers.resume(sleep.ref, clock(resume))
            sleep = repository.timers.stop(sleep.ref, clock(resume + 900_000))
            sleep = repository.timers.finalize(sleep.ref)
            var activity = repository.timers.startEvent(EventRecord(babyId = baby, type = EventType.ACTIVITY_TUMMY_TIME), clock(start))
            activity = repository.timers.pause(activity.ref, clock(firstEnd))
            activity = repository.timers.resume(activity.ref, clock(resume))
            activity = repository.timers.stop(activity.ref, clock(resume + 900_000))
            activity = repository.timers.finalize(activity.ref)
            assertTrue(repository.getTimelineRecords(baby, firstDay.plusDays(1), zone).isEmpty())
            assertEquals(setOf(firstDay, firstDay.plusDays(2)), repository.getDatesWithRecords(baby, YearMonth.of(2026, 1), zone))
            val timeline = repository.getTimelineRecords(baby, firstDay.plusDays(2), zone)
            assertEquals(sleep.ref.recordId, timeline.filterIsInstance<BabyRepository.TimelineRecord.Sleep>().single().record.sleepId)
            assertEquals(activity.ref.recordId, timeline.filterIsInstance<BabyRepository.TimelineRecord.Event>().single().record.eventId)
            assertEquals(15, repository.getDaySummary(baby, firstDay.plusDays(2), zone).sleepTotalMinutes)
            // 零时长首段仍是一条原日记录；不能只因没有有效秒数而隐藏它的日历入口。
            val zeroDay = firstDay.plusDays(4)
            val zeroStart = zeroDay.atStartOfDay(zone).toInstant().toEpochMilli()
            var zero = repository.timers.startEvent(EventRecord(babyId = baby, type = EventType.CARE_BATH), clock(zeroStart))
            zero = repository.timers.pause(zero.ref, clock(zeroStart))
            zero = repository.timers.resume(zero.ref, clock(zeroStart + 172_800_000))
            zero = repository.timers.stop(zero.ref, clock(zeroStart + 172_860_000))
            repository.timers.finalize(zero.ref)
            assertEquals(setOf(firstDay, firstDay.plusDays(2), zeroDay, zeroDay.plusDays(2)),
                repository.getDatesWithRecords(baby, YearMonth.of(2026, 1), zone))
            assertTrue(repository.getTimelineRecords(baby, zeroDay.plusDays(1), zone).isEmpty())
        }
    }

    @Test
    fun switchingSideAfterClockChangePersistsReviewAndCompletedZeroTailKeepsActualEnd() = runBlocking {
        withDatabase { _, repository, baby ->
            val timers = repository.timers
            val started = timers.startFeeding(FeedingRecord(babyId = baby), clock(10_000), TimerPart.LEFT)
            val uncertain = timers.switchPart(started.ref, TimerPart.RIGHT,
                TimerClockSnapshot(30_000, 20_000, 1, "timer-test"))
            assertEquals(TimerStatus.PAUSED, uncertain.status)
            assertTrue(uncertain.metadata.requiresReview)
            assertEquals(10_000L, uncertain.durationMs)
            assertEquals(uncertain, timers.findUnfinished(TimerRecordKind.FEEDING, baby))
            assertTimerFailure(TimerFailure.NEEDS_REVIEW) { timers.resume(uncertain.ref, clock(40_000)) }
            val reviewed = timers.acknowledgeReview(uncertain.ref, clock(40_000))
            assertEquals(20_000L, reviewed.durationMs)
            assertFalse(reviewed.metadata.requiresReview)
            val resumed = timers.resume(reviewed.ref, clock(50_000), TimerPart.RIGHT)
            val stopped = timers.stop(resumed.ref, clock(50_000))
            assertEquals(50_000L, stopped.endAt)
            assertEquals(30_000L, TimerCalculations.lastEffectiveEnd(stopped.metadata))
            val completed = timers.finalize(stopped.ref)
            assertEquals(50_000L, completed.feeding?.feedingEnd)
            assertEquals(20_000L, completed.durationMs)
            // 迟到的旧停止/继续命令不能重新开段或覆盖完成后的资料。
            assertTimerFailure(TimerFailure.STALE_REVISION) { timers.resume(reviewed.ref, clock(60_000)) }
        }
    }

    private suspend fun withDatabase(block: suspend (BabyDatabase, BabyRepository, Int) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(context, BabyDatabase::class.java).build()
        try {
            val baby = db.babyInfoDao().insertBabyInfo(BabyInfo(name = "合成宝宝")).toInt()
            block(db, BabyRepository(db), baby)
        } finally { db.close() }
    }

    private fun receiptCount(db: BabyDatabase): Int = db.openHelper.writableDatabase
        .query("SELECT COUNT(*) FROM Record_Write_Receipts").use { it.moveToFirst(); it.getInt(0) }

    private suspend fun assertTimerFailure(reason: TimerFailure, block: suspend () -> Any?) {
        val failure = runCatching { block() }.exceptionOrNull()
        assertTrue(failure is TimerOperationException)
        assertEquals(reason, (failure as TimerOperationException).reason)
    }
}
