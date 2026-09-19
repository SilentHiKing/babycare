package com.zero.babydata

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.babydata.backup.*
import com.zero.babydata.entity.BabyInfo
import com.zero.babydata.room.BabyDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** 同事务收据是导入是否提交的判据，重开库后不需要再次执行导入。 */
@RunWith(AndroidJUnit4::class)
class BackupCommitJournalTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun committedReportAndOldIdsSurviveReopenAndConsumptionDoesNotDropPendingCleanup() = runBlocking {
        val name = "backup-commit-${UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(context, BabyDatabase::class.java, name).build()
        try {
            val oldId = db.babyInfoDao().insertBabyInfo(BabyInfo(name = "合成宝宝")).toInt()
            val envelope = BabyBackupExporter(db).buildEnvelope()
            val id = UUID.randomUUID().toString()
            val report = BabyBackupImporter(db).importOverwrite(envelope, id)
            db.close()
            db = Room.databaseBuilder(context, BabyDatabase::class.java, name).build()
            val journal = BackupImportJournal(db)
            val committed = requireNotNull(journal.oldestUnconsumed())
            assertEquals(id, committed.commitId)
            assertEquals(report, committed.report)
            assertEquals(listOf(oldId), committed.replacedBabyIds)
            assertEquals(1, db.babyInfoDao().getAllBabyInfo().size)
            journal.consumeReport(id)
            assertNull(journal.oldestUnconsumed())
            assertEquals(1, journal.pendingCleanup().size)
            journal.markCleanupComplete(id)
            assertTrue(journal.pendingCleanup().isEmpty())
            // 备份协议仍只有业务记录，不包含本机操作回执或提交表。
            val exported = BabyBackupExporter(db).toJson(BabyBackupExporter(db).buildEnvelope())
            assertFalse(exported.contains(id))
            assertFalse(exported.contains("replacedBabyIds"))
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test
    fun journalFailureRollsBackOverwriteAndLeavesNoFalseSuccessRecord() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, BabyDatabase::class.java).build()
        try {
            db.babyInfoDao().insertBabyInfo(BabyInfo(name = "合成宝宝"))
            val before = db.babyInfoDao().getAllBabyInfo()
            val envelope = BabyBackupExporter(db).buildEnvelope()
            val id = UUID.randomUUID().toString()
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_commit BEFORE INSERT ON Backup_Import_Commits BEGIN SELECT RAISE(ABORT,'synthetic'); END")
            try { BabyBackupImporter(db).importOverwrite(envelope, id); fail("收据写入应失败") }
            catch (expected: android.database.sqlite.SQLiteException) { /* 事务必须回滚业务行和提交记录。 */ }
            assertEquals(before, db.babyInfoDao().getAllBabyInfo())
            assertNull(BackupImportJournal(db).get(id))
        } finally { db.close() }
    }
}
