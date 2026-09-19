package com.zero.babydata

import androidx.room.Room
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** 仅使用独立合成库；重开数据库验证持久边界，不冒充 Android 杀进程测试。 */
@RunWith(AndroidJUnit4::class)
class RecordWriteReceiptTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private enum class Kind { FEEDING, SLEEP, EVENT }

    @Test
    fun reopenedDatabaseRecognizesAllThreeRequestsDespiteNewIdsAndCreationTimes() = runBlocking {
        val name = "write-receipt-test-${UUID.randomUUID()}.db"
        val tokens = Kind.entries.associateWith { UUID.randomUUID().toString() }
        val ids = mutableMapOf<Kind, Int>()
        var database = Room.databaseBuilder(context, BabyDatabase::class.java, name).build()
        try {
            val babyId = insertBaby(database)
            Kind.entries.forEach { kind ->
                ids[kind] = write(BabyRepository(database), kind, babyId, tokens.getValue(kind))
            }
            database.close()
            database = Room.databaseBuilder(context, BabyDatabase::class.java, name).build()
            val restored = BabyRepository(database)
            Kind.entries.forEach { kind ->
                val repeated = write(restored, kind, babyId, tokens.getValue(kind), createdAt = 90_000, id = 777)
                assertEquals(ids.getValue(kind), repeated)
                assertEquals(1, count(database, kind, babyId))
            }
            assertEquals(1_000L, database.feedingRecordDao().getAllFeedingRecords(babyId).single().createdAt)
            assertEquals(1_000L, database.sleepRecordDao().getAllSleepRecords(babyId).single().createdAt)
            assertEquals(1_000L, database.eventRecordDao().getAllEventRecord(babyId).single().createdAt)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun concurrentSameRequestsWriteOnceWhileNewDraftTokensAllowIntentionalDuplicates() = withDatabase { db ->
        val babyId = insertBaby(db)
        val repository = BabyRepository(db)
        Kind.entries.forEach { kind ->
            val token = UUID.randomUUID().toString()
            val ids = coroutineScope {
                List(12) { async(Dispatchers.IO) { write(repository, kind, babyId, token) } }.awaitAll()
            }
            assertEquals(1, ids.toSet().size)
            assertEquals(1, count(db, kind, babyId))
            write(repository, kind, babyId, UUID.randomUUID().toString())
            assertEquals(2, count(db, kind, babyId))
        }
    }

    @Test
    fun changedPayloadOwnerOrRecordKindCannotReuseACommittedToken() = withDatabase { db ->
        val babyId = insertBaby(db)
        val anotherBabyId = insertBaby(db)
        val repository = BabyRepository(db)
        Kind.entries.forEach { kind ->
            val token = UUID.randomUUID().toString()
            write(repository, kind, babyId, token)
            expectFailure(RecordWriteFailure.CONTENT_CONFLICT) {
                write(repository, kind, babyId, token, note = "更改后的合成草稿")
            }
            expectFailure(RecordWriteFailure.CONTENT_CONFLICT) {
                write(repository, kind, anotherBabyId, token)
            }
            val differentKind = Kind.entries[(kind.ordinal + 1) % Kind.entries.size]
            expectFailure(RecordWriteFailure.CONTENT_CONFLICT) {
                write(repository, differentKind, babyId, token)
            }
            assertEquals(1, count(db, kind, babyId))
        }
    }

    @Test
    fun eventJsonKeyOrderAndNumberFormattingDoNotCreateAConflict() = withDatabase { db ->
        val babyId = insertBaby(db)
        val repository = BabyRepository(db)
        val token = UUID.randomUUID().toString()
        val record = EventRecord(babyId = babyId, type = 301, time = 1_000,
            extraData = """{"value":5.0,"unit":"kg","nested":{"b":2,"a":[1,2]}}""")
        val id = repository.insertEventRecordOnce(record, token)
        val reordered = record.copy(extraData = """{"nested":{"a":[1.0,2],"b":2.0},"unit":"kg","value":5}""")
        assertEquals(id, repository.insertEventRecordOnce(reordered, token.uppercase()))
        expectFailure(RecordWriteFailure.CONTENT_CONFLICT) {
            repository.insertEventRecordOnce(record.copy(extraData = """{"value":6,"unit":"kg"}"""), token)
        }
        expectFailure(RecordWriteFailure.INVALID_REQUEST) {
            repository.insertEventRecordOnce(record.copy(extraData = "{"), UUID.randomUUID().toString())
        }
    }

    @Test
    fun deletionKeepsReceiptsAndOldRequestsCannotResurrectRecords() = withDatabase { db ->
        val babyId = insertBaby(db)
        val repository = BabyRepository(db)
        Kind.entries.forEach { kind ->
            val token = UUID.randomUUID().toString()
            val id = write(repository, kind, babyId, token)
            when (kind) {
                Kind.FEEDING -> repository.deleteFeedingRecordById(id)
                Kind.SLEEP -> repository.deleteSleepRecordById(id)
                Kind.EVENT -> repository.deleteEventRecordById(id)
            }
            assertNotNull(db.recordWriteReceiptDao().getReceipt(token))
            expectFailure(RecordWriteFailure.RECORD_DELETED) { write(repository, kind, babyId, token) }
            expectFailure(RecordWriteFailure.RECORD_DELETED) {
                write(repository, kind, babyId, token, note = "删除后修改的合成草稿")
            }
            assertEquals(0, count(db, kind, babyId))
        }
    }

    @Test
    fun receiptInsertFailureRollsBackTheRecordAndAllowsARealRetry() = withDatabase { db ->
        val babyId = insertBaby(db)
        val repository = BabyRepository(db)
        Kind.entries.forEach { kind ->
            val token = UUID.randomUUID().toString()
            db.openHelper.writableDatabase.execSQL("""
                CREATE TRIGGER reject_test_receipt BEFORE INSERT ON Record_Write_Receipts
                BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END
            """.trimIndent())
            val failure = runCatching { write(repository, kind, babyId, token) }
            assertTrue(failure.isFailure)
            assertEquals(0, count(db, kind, babyId))
            assertNull(db.recordWriteReceiptDao().getReceipt(token))
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_test_receipt")
            write(repository, kind, babyId, token)
            assertEquals(1, count(db, kind, babyId))
        }
    }

    @Test
    fun retryCannotOverwriteLaterEditsToThePreviouslySavedRecord() = withDatabase { db ->
        val babyId = insertBaby(db)
        val repository = BabyRepository(db)
        val token = UUID.randomUUID().toString()
        val id = write(repository, Kind.SLEEP, babyId, token)
        val original = requireNotNull(db.sleepRecordDao().getSleepRecordById(id))
        db.sleepRecordDao().updateSleepRecord(original.copy(note = "后来主动编辑的内容"))
        assertEquals(id, write(repository, Kind.SLEEP, babyId, token))
        assertEquals("后来主动编辑的内容", db.sleepRecordDao().getSleepRecordById(id)?.note)
    }

    @Test
    fun babyDeletionAndOverwriteInvalidateOldTargetsWithoutExportingReceipts() = withDatabase { db ->
        val repository = BabyRepository(db)
        val babyId = insertBaby(db)
        val token = UUID.randomUUID().toString()
        val originalId = write(repository, Kind.SLEEP, babyId, token)
        val exporter = BabyBackupExporter(db)
        val backup = exporter.buildEnvelope()
        assertTrue(!exporter.toJson(backup).contains(token))
        // 覆盖导入失败时，随宝宝级联删除的回执也必须和原记录一起回滚。
        db.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER reject_test_overwrite BEFORE INSERT ON Sleep_Records
            BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END
        """.trimIndent())
        assertTrue(runCatching { BabyBackupImporter(db).importOverwrite(backup) }.isFailure)
        assertNotNull(db.recordWriteReceiptDao().getReceipt(token))
        assertEquals(originalId, write(repository, Kind.SLEEP, babyId, token))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_test_overwrite")
        BabyBackupImporter(db).importOverwrite(backup)
        assertNull(db.recordWriteReceiptDao().getReceipt(token))
        val restoredBaby = db.babyInfoDao().getAllBabyInfo().single()
        assertTrue(restoredBaby.babyId != babyId)
        expectFailure(RecordWriteFailure.BABY_NOT_FOUND) { write(repository, Kind.SLEEP, babyId, token) }
        assertEquals(1, count(db, Kind.SLEEP, restoredBaby.babyId))

        val newToken = UUID.randomUUID().toString()
        write(repository, Kind.FEEDING, restoredBaby.babyId, newToken)
        repository.deleteBabyInfo(restoredBaby)
        assertNull(db.recordWriteReceiptDao().getReceipt(newToken))
        expectFailure(RecordWriteFailure.BABY_NOT_FOUND) {
            write(repository, Kind.FEEDING, restoredBaby.babyId, newToken)
        }
    }

    @Test
    fun invalidRequestIdentityDoesNotWriteAnything() = withDatabase { db ->
        val babyId = insertBaby(db)
        val repository = BabyRepository(db)
        listOf("", "not-a-uuid", "1-1-1-1-1").forEach { token ->
            expectFailure(RecordWriteFailure.INVALID_REQUEST) { write(repository, Kind.FEEDING, babyId, token) }
        }
        expectFailure(RecordWriteFailure.INVALID_REQUEST) {
            write(repository, Kind.FEEDING, 0, UUID.randomUUID().toString())
        }
        assertEquals(0, count(db, Kind.FEEDING, babyId))
    }

    private suspend fun insertBaby(db: BabyDatabase): Int = db.babyInfoDao()
        .insertBabyInfo(BabyInfo(name = "合成测试", gender = "boy")).toInt()

    private suspend fun write(
        repository: BabyRepository,
        kind: Kind,
        babyId: Int,
        token: String,
        note: String = "合成记录",
        createdAt: Long = 1_000,
        id: Int = 0
    ): Int = when (kind) {
        Kind.FEEDING -> repository.insertFeedingRecordOnce(FeedingRecord(
            feedingId = id, babyId = babyId, feedingStart = 1_000, feedingEnd = 2_000,
            feedingDuration = 1_000, note = note, createdAt = createdAt
        ), token)
        Kind.SLEEP -> repository.insertSleepRecordOnce(SleepRecord(
            sleepId = id, babyId = babyId, sleepStart = 1_000, sleepEnd = 2_000,
            sleepDuration = 1_000, note = note, createdAt = createdAt
        ), token)
        Kind.EVENT -> repository.insertEventRecordOnce(EventRecord(
            eventId = id, babyId = babyId, type = 101, time = 1_000,
            extraData = "{}", note = note, createdAt = createdAt
        ), token)
    }

    private suspend fun count(db: BabyDatabase, kind: Kind, babyId: Int): Int = when (kind) {
        Kind.FEEDING -> db.feedingRecordDao().getAllFeedingRecords(babyId).size
        Kind.SLEEP -> db.sleepRecordDao().getAllSleepRecords(babyId).size
        Kind.EVENT -> db.eventRecordDao().getAllEventRecord(babyId).size
    }

    private suspend fun expectFailure(reason: RecordWriteFailure, block: suspend () -> Unit) {
        val error = runCatching { block() }.exceptionOrNull()
        assertTrue("必须返回明确的数据写入失败原因", error is RecordWriteException)
        assertEquals(reason, (error as RecordWriteException).reason)
    }

    private fun withDatabase(block: suspend (BabyDatabase) -> Unit) {
        val database = Room.inMemoryDatabaseBuilder(context, BabyDatabase::class.java).build()
        try {
            runBlocking { block(database) }
        } finally {
            database.close()
        }
    }
}
