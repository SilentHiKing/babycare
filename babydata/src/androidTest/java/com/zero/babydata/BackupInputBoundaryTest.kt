package com.zero.babydata

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.babydata.backup.BackupExportError
import com.zero.babydata.backup.BackupExportException
import com.zero.babydata.backup.BackupImportException
import com.zero.babydata.backup.BackupEventRecord
import com.zero.babydata.backup.BackupJsonLimits
import com.zero.babydata.backup.BabyBackupExporter
import com.zero.babydata.backup.BabyBackupImporter
import com.zero.babydata.entity.BabyInfo
import com.zero.babydata.entity.EventType
import com.zero.babydata.room.BabyDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

/** 仅使用独立内存 Room；超量导出与非法导入都不得读取/改变用户数据库。 */
@RunWith(AndroidJUnit4::class)
class BackupInputBoundaryTest {
    @Test fun oversizedDatabaseIsRejectedBeforeLoadingEntityRows() = runBlocking {
        val queries = CopyOnWriteArrayList<String>()
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, BabyDatabase::class.java)
            .setQueryCallback({ sql, _ -> queries.add(sql) }, Executor { it.run() }).build()
        try {
            val id = db.babyInfoDao().insertBabyInfo(BabyInfo(name = "合成边界宝宝")).toInt()
            // 直接在 SQLite 生成合成行，避免测试自己先分配二十万实体掩盖导出内存行为。
            db.openHelper.writableDatabase.execSQL(
                "WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < ${BackupJsonLimits.MAX_RECORDS}) " +
                    "INSERT INTO Sleep_Records(babyId,sleepStart,sleepEnd,sleepDuration,note,createdAt) " +
                    "SELECT ?,n,n+1,1,'',1 FROM seq", arrayOf(id)
            )
            queries.clear()
            val failure = runCatching { BabyBackupExporter(db).buildEnvelope() }.exceptionOrNull()
            assertTrue(failure is BackupExportException)
            assertEquals(BackupExportError.LIMIT_EXCEEDED, (failure as BackupExportException).error)
            assertTrue(queries.any { it.contains("COUNT(*)", ignoreCase = true) })
            assertFalse("超量时不能执行读取全部实体的 SELECT", queries.any { it.trimStart().startsWith("SELECT *", ignoreCase = true) })
            assertEquals(BackupJsonLimits.MAX_RECORDS.toLong(), db.sleepRecordDao().countForBackup())
        } finally { db.close() }
    }

    @Test fun invalidKnownPayloadsAndDeepJsonCannotChangeMergeOrOverwriteData() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, BabyDatabase::class.java).build()
        try {
            val baby = BabyInfo(name = "原合成宝宝")
            db.babyInfoDao().insertBabyInfo(baby)
            val exporter = BabyBackupExporter(db)
            val original = exporter.buildEnvelope()
            val nested = "{\"x\":" + "[".repeat(5_000) + "0" + "]".repeat(5_000) + "}"
            listOf(
                EventType.DIAPER_WET to nested,
                EventType.HEALTH_MEDICINE to "{\"name\":\"测试\",\"dosage\":[]}",
                EventType.HEALTH_VACCINE to "{\"name\":\"测试\",\"dose\":2147483648}"
            ).forEach { (type, extra) ->
                val incoming = original.copy(data = original.data.copy(eventRecords = listOf(
                    BackupEventRecord("synthetic:1", baby.stableId, type, 1000, 0, extra, "", 1000)
                )))
                val importer = BabyBackupImporter(db)
                assertTrue(runCatching { importer.importMerge(incoming, 0) }.exceptionOrNull() is BackupImportException)
                assertTrue(runCatching { importer.importOverwrite(incoming) }.exceptionOrNull() is BackupImportException)
                assertEquals(original.data, exporter.buildEnvelope().data)
            }
        } finally { db.close() }
    }
}
