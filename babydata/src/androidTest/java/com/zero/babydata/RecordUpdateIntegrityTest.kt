package com.zero.babydata

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.babydata.backup.BabyBackupExporter
import com.zero.babydata.backup.BabyBackupImporter
import com.zero.babydata.domain.RecordWriteException
import com.zero.babydata.domain.RecordWriteFailure
import com.zero.babydata.entity.BabyInfo
import com.zero.babydata.entity.EventRecord
import com.zero.babydata.entity.FeedingRecord
import com.zero.babydata.entity.SleepRecord
import com.zero.babydata.room.BabyDatabase
import com.zero.babydata.room.BabyRepository
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 用独立库与事务闸门验证覆盖/删除和在途编辑的顺序，不靠定时 sleep 制造竞态。 */
@RunWith(AndroidJUnit4::class)
class RecordUpdateIntegrityTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun existingRecordsCanBeEditedButCannotMoveToAnotherBaby() = database { db ->
        seed(db)
        val repository = BabyRepository(db)
        updateAll(repository, babyId = 1, note = "合成新备注")
        assertEquals("合成新备注", repository.getFeedingRecordById(11)?.note)
        assertEquals("合成新备注", repository.getSleepRecordById(12)?.note)
        assertEquals("合成新备注", repository.getEventRecordById(13)?.note)
        operations(repository, babyId = 2, note = "不应改归属").forEach { operation ->
            deletedFailure(runCatching { operation() })
        }
        assertTrue(repository.getAllFeedingRecords(2).isEmpty())
        assertTrue(repository.getAllSleepRecords(2).isEmpty())
        assertTrue(repository.getAllEventRecords(2).isEmpty())
        assertEquals(1, repository.getFeedingRecordById(11)?.babyId)
        assertEquals(1, repository.getSleepRecordById(12)?.babyId)
        assertEquals(1, repository.getEventRecordById(13)?.babyId)
    }

    @Test
    fun editsQueuedBehindOverwriteFailWithoutChangingRestoredRecords() = database { db ->
        seed(db)
        val repository = BabyRepository(db)
        val exporter = BabyBackupExporter(db)
        val backup = exporter.buildEnvelope()
        val overwritten = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coroutineScope {
            val importing = launch {
                db.withTransaction {
                    BabyBackupImporter(db).importOverwrite(backup)
                    overwritten.complete(Unit)
                    release.await()
                }
            }
            overwritten.await()
            val entered = List(3) { CompletableDeferred<Unit>() }
            val edits = operations(repository, 1, "过期编辑").mapIndexed { index, operation ->
                async {
                    entered[index].complete(Unit)
                    runCatching { operation() }
                }
            }
            entered.forEach { it.await() }
            release.complete(Unit)
            importing.join()
            edits.awaitAll().forEach(::deletedFailure)
        }
        val restored = exporter.buildEnvelope()
        assertEquals(backup.data.babies.sortedBy { it.stableId }, restored.data.babies.sortedBy { it.stableId })
        assertEquals(backup.data.feedingRecords.map { it.copy(sourceId = "") }, restored.data.feedingRecords.map { it.copy(sourceId = "") })
        assertEquals(backup.data.sleepRecords.map { it.copy(sourceId = "") }, restored.data.sleepRecords.map { it.copy(sourceId = "") })
        assertEquals(backup.data.eventRecords.map { it.copy(sourceId = "") }, restored.data.eventRecords.map { it.copy(sourceId = "") })
    }

    @Test
    fun cancellationWhileWaitingForTransactionDoesNotWriteAnOldEdit() = database { db ->
        seed(db)
        val repository = BabyRepository(db)
        coroutineScope {
            val locked = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val holder = launch { db.withTransaction { locked.complete(Unit); release.await() } }
            locked.await()
            val entered = CompletableDeferred<Unit>()
            val edit = launch { entered.complete(Unit); updateAll(repository, 1, "已取消编辑") }
            entered.await()
            edit.cancelAndJoin()
            release.complete(Unit)
            holder.join()
        }
        assertEquals("原记录", repository.getFeedingRecordById(11)?.note)
        assertEquals("原记录", repository.getSleepRecordById(12)?.note)
        assertEquals("原记录", repository.getEventRecordById(13)?.note)
    }

    private suspend fun seed(db: BabyDatabase) {
        db.babyInfoDao().insertBabyInfo(BabyInfo(babyId = 1, name = "合成一"))
        db.babyInfoDao().insertBabyInfo(BabyInfo(babyId = 2, name = "合成二"))
        db.feedingRecordDao().insertFeedingRecord(feeding(1, "原记录"))
        db.sleepRecordDao().insertSleepRecord(sleep(1, "原记录"))
        db.eventRecordDao().insertEventRecord(event(1, "原记录"))
    }

    private fun feeding(babyId: Int, note: String) = FeedingRecord(feedingId = 11, babyId = babyId, feedingStart = 1_000, feedingEnd = 2_000, feedingDuration = 1_000, note = note)
    private fun sleep(babyId: Int, note: String) = SleepRecord(sleepId = 12, babyId = babyId, sleepStart = 1_000, sleepEnd = 2_000, sleepDuration = 1_000, note = note)
    private fun event(babyId: Int, note: String) = EventRecord(eventId = 13, babyId = babyId, type = 301, time = 1_000, note = note)

    private fun operations(repository: BabyRepository, babyId: Int, note: String): List<suspend () -> Unit> = listOf(
        { repository.updateFeedingRecord(feeding(babyId, note)) },
        { repository.updateSleepRecord(sleep(babyId, note)) },
        { repository.updateEventRecord(event(babyId, note)) }
    )

    private suspend fun updateAll(repository: BabyRepository, babyId: Int, note: String) = operations(repository, babyId, note).forEach { it() }
    private fun deletedFailure(result: Result<Unit>) {
        assertTrue(result.exceptionOrNull() is RecordWriteException)
        assertEquals(RecordWriteFailure.RECORD_DELETED, (result.exceptionOrNull() as RecordWriteException).reason)
    }

    private fun database(block: suspend (BabyDatabase) -> Unit) = runBlocking(Dispatchers.IO) {
        val db = Room.inMemoryDatabaseBuilder(context, BabyDatabase::class.java).build()
        try { withTimeout(15_000) { block(db) } } finally { db.close() }
    }
}
