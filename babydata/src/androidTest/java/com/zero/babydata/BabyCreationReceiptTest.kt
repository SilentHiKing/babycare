package com.zero.babydata

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.babydata.backup.BabyBackupExporter
import com.zero.babydata.backup.BabyBackupImporter
import com.zero.babydata.domain.BabyWriteException
import com.zero.babydata.domain.BabyWriteFailure
import com.zero.babydata.entity.BabyInfo
import com.zero.babydata.room.BabyDatabase
import com.zero.babydata.room.BabyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** 独立 Room 库验证新建草稿重试；不读取、修改应用中的宝宝。 */
@RunWith(AndroidJUnit4::class)
class BabyCreationReceiptTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun reopenedRetryReturnsOriginalIdAndPreservesLaterEdits() = runBlocking {
        val name = "baby-create-${UUID.randomUUID()}.db"
        val draft = BabyInfo(name = "合成新建", birthHeight = 50f)
        var db = Room.databaseBuilder(context, BabyDatabase::class.java, name).build()
        try {
            val first = BabyRepository(db)
            val id = first.insertBabyInfoOnce(draft)
            assertTrue(first.updateBabyInfoIfPresent(draft.copy(babyId = id, name = "后来修改")))
            db.close()
            db = Room.databaseBuilder(context, BabyDatabase::class.java, name).build()
            val restored = BabyRepository(db)
            assertEquals(id, restored.insertBabyInfoOnce(draft.copy(babyId = 999)))
            assertEquals("后来修改", restored.getAllBabyInfo().single().name)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test
    fun concurrentSameIdentityIsOnceButNewIdentityCanIntentionallyRepeat() = database { db ->
        val repository = BabyRepository(db)
        val draft = BabyInfo(name = "合成资料")
        val ids = coroutineScope { List(12) { async(Dispatchers.IO) { repository.insertBabyInfoOnce(draft.copy()) } }.awaitAll() }
        assertEquals(1, ids.toSet().size)
        repository.insertBabyInfoOnce(draft.copy(stableId = UUID.randomUUID().toString()))
        assertEquals(2, repository.getAllBabyInfo().size)
        failure(BabyWriteFailure.CONTENT_CONFLICT) { repository.insertBabyInfoOnce(draft.copy(name = "更改草稿")) }
        failure(BabyWriteFailure.INVALID_REQUEST) { repository.insertBabyInfoOnce(draft.copy(stableId = "bad")) }
    }

    @Test
    fun deletedOrOverwrittenIdentityCannotBeResurrectedByOldDraft() = database { db ->
        val repository = BabyRepository(db)
        val draft = BabyInfo(name = "合成资料")
        val id = repository.insertBabyInfoOnce(draft)
        val envelope = BabyBackupExporter(db).buildEnvelope()
        repository.deleteBabyInfo(requireNotNull(repository.getBabyInfo(id)))
        failure(BabyWriteFailure.BABY_DELETED) { repository.insertBabyInfoOnce(draft) }
        BabyBackupImporter(db).importOverwrite(envelope)
        assertNotEquals(id, repository.getAllBabyInfo().single().babyId)
        failure(BabyWriteFailure.BABY_DELETED) { repository.insertBabyInfoOnce(draft) }
        assertEquals(1, repository.getAllBabyInfo().size)
    }

    @Test
    fun lowercaseDraftBindsUppercaseHistoryAndRetryPreservesLaterEdits() = database { db ->
        val repository = BabyRepository(db)
        val draft = BabyInfo(stableId = CASE_ID, name = "合成历史资料", birthHeight = 50f)
        val legacy = draft.copy(stableId = CASE_ID.uppercase())
        val originalId = repository.insertBabyInfo(legacy)

        // 新草稿仍使用规范小写；兼容只发生在查找历史宝宝时，不能改写原始身份。
        assertEquals(originalId, repository.insertBabyInfoOnce(draft))
        assertEquals(listOf(legacy), repository.getAllBabyInfo())
        assertEquals(originalId, db.babyCreationReceiptDao().getReceipt(CASE_ID)?.babyId)
        val edited = legacy.copy(name = "后来修改", birthHeight = 52f)
        assertTrue(repository.updateBabyInfoIfPresent(edited))
        assertEquals(originalId, BabyRepository(db).insertBabyInfoOnce(draft.copy()))
        assertEquals(edited, repository.getBabyInfo(originalId))
        failure(BabyWriteFailure.INVALID_REQUEST) {
            repository.insertBabyInfoOnce(draft.copy(stableId = CASE_ID.uppercase()))
        }
    }

    @Test
    fun uppercaseHistoryReceiptStillPreventsResurrectionAfterDeleteAndOverwrite() = database { db ->
        val repository = BabyRepository(db)
        val draft = BabyInfo(stableId = CASE_ID, name = "合成历史资料")
        val legacy = draft.copy(stableId = CASE_ID.uppercase())
        val originalId = repository.insertBabyInfo(legacy)
        assertEquals(originalId, repository.insertBabyInfoOnce(draft))
        val envelope = BabyBackupExporter(db).buildEnvelope()

        repository.deleteBabyInfo(legacy)
        failure(BabyWriteFailure.BABY_DELETED) { repository.insertBabyInfoOnce(draft) }
        assertTrue(repository.getAllBabyInfo().isEmpty())
        BabyBackupImporter(db).importOverwrite(envelope)
        val restored = repository.getAllBabyInfo().single()
        assertNotEquals(originalId, restored.babyId)
        assertEquals(legacy.stableId, restored.stableId)
        // 同 UUID 被备份恢复为新本地 ID，也不能重绑已经提交过的创建请求。
        failure(BabyWriteFailure.BABY_DELETED) { repository.insertBabyInfoOnce(draft) }
        assertEquals(listOf(restored), repository.getAllBabyInfo())
        assertEquals(originalId, db.babyCreationReceiptDao().getReceipt(CASE_ID)?.babyId)
    }

    @Test
    fun conflictingOrAmbiguousCaseHistoryWritesNeitherBabyNorReceipt() = database { db ->
        val repository = BabyRepository(db)
        val draft = BabyInfo(stableId = CASE_ID, name = "合成草稿")
        val conflicting = draft.copy(stableId = CASE_ID.uppercase(), name = "另一份资料")
        repository.insertBabyInfo(conflicting)
        failure(BabyWriteFailure.CONTENT_CONFLICT) { repository.insertBabyInfoOnce(draft) }
        assertEquals(listOf(conflicting), repository.getAllBabyInfo())
        assertNull(db.babyCreationReceiptDao().getReceipt(CASE_ID))

        repository.updateBabyInfo(conflicting.copy(name = draft.name))
        repository.insertBabyInfo(draft.copy(stableId = CASE_ID.replace('a', 'A')))
        val before = repository.getAllBabyInfo()
        // 两条都不是精确小写且业务内容相同，也不能凭顺序选中其中一条。
        failure(BabyWriteFailure.CONTENT_CONFLICT) { repository.insertBabyInfoOnce(draft) }
        assertEquals(before, repository.getAllBabyInfo())
        assertNull(db.babyCreationReceiptDao().getReceipt(CASE_ID))
    }

    @Test
    fun exactLowercaseHistoryTakesPriorityOverCaseVariant() = database { db ->
        val repository = BabyRepository(db)
        val draft = BabyInfo(stableId = CASE_ID, name = "合成精确身份")
        val exact = draft.copy()
        val exactId = repository.insertBabyInfo(exact)
        val other = draft.copy(stableId = CASE_ID.uppercase(), name = "另一历史宝宝")
        repository.insertBabyInfo(other)
        val before = repository.getAllBabyInfo()

        assertEquals(exactId, repository.insertBabyInfoOnce(draft))
        assertEquals(before, repository.getAllBabyInfo())
        assertEquals(exactId, db.babyCreationReceiptDao().getReceipt(CASE_ID)?.babyId)
    }

    @Test
    fun missingOrReusedLocalIdCannotBeUpdatedByAnotherIdentity() = database { db ->
        val repository = BabyRepository(db)
        val draft = BabyInfo(name = "合成资料")
        val id = repository.insertBabyInfoOnce(draft)
        val saved = draft.copy(babyId = id)
        repository.deleteBabyInfo(saved)
        assertFalse(repository.updateBabyInfoIfPresent(saved.copy(name = "旧编辑")))
        val replacement = BabyInfo(babyId = id, name = "另一身份")
        repository.insertBabyInfo(replacement)
        assertFalse(repository.updateBabyInfoIfPresent(saved.copy(name = "旧编辑")))
        assertEquals(replacement, repository.getBabyInfo(id))
    }

    @Test
    fun receiptFailureRollsBackBabyAndAllowsRetry() = database { db ->
        val repository = BabyRepository(db)
        val draft = BabyInfo(name = "合成资料")
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_creation BEFORE INSERT ON Baby_Creation_Receipts BEGIN SELECT RAISE(ABORT,'synthetic'); END")
        try {
            repository.insertBabyInfoOnce(draft)
            fail("回执插入应失败")
        } catch (expected: android.database.sqlite.SQLiteException) {
            assertTrue(repository.getAllBabyInfo().isEmpty())
        }
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_creation")
        assertTrue(repository.insertBabyInfoOnce(draft) > 0)
        assertEquals(1, repository.getAllBabyInfo().size)
    }

    private suspend fun failure(expected: BabyWriteFailure, block: suspend () -> Unit) {
        try { block(); fail("旧创建请求应被拒绝") } catch (error: BabyWriteException) { assertEquals(expected, error.reason) }
    }

    private fun database(block: suspend (BabyDatabase) -> Unit) = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, BabyDatabase::class.java).build()
        try { block(db) } finally { db.close() }
    }

    private companion object {
        const val CASE_ID = "abcdef01-2345-4678-9abc-def012345678"
    }
}
