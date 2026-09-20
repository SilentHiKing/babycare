package com.zero.babydata

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.babydata.backup.BABY_BACKUP_VERSION
import com.zero.babydata.backup.BackupBaby
import com.zero.babydata.backup.BackupChildDailyRecord
import com.zero.babydata.backup.BackupEventRecord
import com.zero.babydata.backup.BackupSleepRecord
import com.zero.babydata.backup.BabyBackupData
import com.zero.babydata.backup.BabyBackupEnvelope
import com.zero.babydata.backup.BabyBackupExporter
import com.zero.babydata.backup.BabyBackupImporter
import com.zero.babydata.backup.BabyBackupParser
import com.zero.babydata.entity.EventType
import com.zero.babydata.room.BabyDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupImportTest {
    private val stableId = "8668a9e3-406d-478c-af23-6b4ad5327458"

    @Test
    fun mergePreservesExactJsonNumbersAndOriginalTextAcrossParsedRepeatedImports() = withDatabase { db ->
        val importer = BabyBackupImporter(db)
        val exporter = BabyBackupExporter(db)
        val originalInteger = "{\"vendor\":{\"sequence\":9007199254740992}}"
        val changedInteger = "{\"vendor\":{\"sequence\":9007199254740993}}"
        val originalDecimal = "{\"unit\":\"kg\",\"value\":4.000000000000000001}"
        val changedDecimal = "{\"value\":4.000000000000000002,\"unit\":\"kg\"}"
        val integer = BackupEventRecord("event:integer-a", stableId, EventType.DIAPER_WET,
            1000, 0, originalInteger, "", 1000)
        val decimal = BackupEventRecord("event:decimal-a", stableId, EventType.GROWTH_WEIGHT,
            1000, 0, originalDecimal, "", 1000)
        val original = sampleEnvelope().let {
            it.copy(data = it.data.copy(eventRecords = listOf(integer, decimal), childDailyRecords = emptyList()))
        }
        val seeded = importer.importMerge(BabyBackupParser.parse(exporter.toJson(original)), 0)
        assertEquals(2, seeded.eventInserted)
        assertEquals(0, seeded.eventSkipped)

        // 实际协议解析后再与库内记录比较：精确值不同须入库，仅等值指数写法与键顺序可去重。
        val incoming = original.copy(data = original.data.copy(eventRecords = listOf(
            integer.copy(sourceId = "event:integer-b", extraData = changedInteger),
            decimal.copy(sourceId = "event:decimal-b", extraData = changedDecimal),
            decimal.copy(sourceId = "event:decimal-equivalent",
                extraData = "{\"value\":4000000000000000001e-18,\"unit\":\"kg\"}")
        )))
        val incomingJson = exporter.toJson(incoming)
        val parsed = BabyBackupParser.parse(incomingJson)
        assertEquals(incoming.data.eventRecords, parsed.data.eventRecords)
        val merged = importer.importMerge(parsed, 0)
        assertEquals(0, merged.babyInserted)
        assertEquals(1, merged.babyMatched)
        assertEquals(2, merged.eventInserted)
        assertEquals(1, merged.eventSkipped)
        val snapshot = exporter.buildEnvelope().data
        assertEquals(4, snapshot.eventRecords.size)
        assertEquals(setOf(originalInteger, changedInteger, originalDecimal, changedDecimal),
            snapshot.eventRecords.map { it.extraData }.toSet())

        // 再次读同一文件既不能新增，也不能用规范化后的比较副本改写任何原文。
        val repeated = importer.importMerge(BabyBackupParser.parse(incomingJson), 0)
        assertEquals(0, repeated.eventInserted)
        assertEquals(3, repeated.eventSkipped)
        assertEquals(snapshot, exporter.buildEnvelope().data)
    }

    @Test
    fun mergeUsesActualTimeToleranceAcrossMinuteBoundaries() = withDatabase { db ->
        val importer = BabyBackupImporter(db)
        val original = BackupSleepRecord("sleep:1", stableId, 59_500, 119_500, 60_000, "", 200_000)
        val envelope = sampleEnvelope().let {
            it.copy(data = it.data.copy(sleepRecords = listOf(original), childDailyRecords = emptyList()))
        }
        assertEquals(1, importer.importMerge(envelope, 1).sleepInserted)
        val shifted = envelope.copy(data = envelope.data.copy(sleepRecords = listOf(
            original.copy(sourceId = "sleep:2", sleepStart = 60_500, sleepEnd = 120_500, createdAt = 300_000)
        )))
        val merged = importer.importMerge(shifted, 1)
        assertEquals(0, merged.sleepInserted)
        assertEquals(1, merged.sleepSkipped)
        assertEquals(1, BabyBackupExporter(db).buildEnvelope().data.sleepRecords.size)
    }

    @Test
    fun mergePreservesDifferentSameDayRecordsAndRepeatedImportIsIdempotent() = withDatabase { db ->
        val importer = BabyBackupImporter(db)
        val exporter = BabyBackupExporter(db)
        val envelope = sampleEnvelope()
        val first = importer.importMerge(envelope, 0)
        assertEquals(2, first.childDailyInserted)
        val snapshot = exporter.buildEnvelope().data
        assertEquals(listOf(5f, 4f), snapshot.childDailyRecords.map { it.weight })

        val second = importer.importMerge(envelope, 0)
        assertEquals(0, second.childDailyInserted)
        assertEquals(0, second.childDailyUpdated)
        assertEquals(snapshot, exporter.buildEnvelope().data)

        // 导出再合并也不因自增 ID 变化新增重复测量。
        importer.importMerge(BabyBackupParser.parse(exporter.toJson(exporter.buildEnvelope())), 0)
        assertEquals(snapshot, exporter.buildEnvelope().data)

        val conflicting = envelope.copy(data = envelope.data.copy(childDailyRecords = listOf(
            envelope.data.childDailyRecords.first().copy(sourceId = "daily:3", weight = 6f, pic = "第三张")
        )))
        assertEquals(1, importer.importMerge(conflicting, 0).childDailyInserted)
        assertEquals(setOf(4f, 5f, 6f), exporter.buildEnvelope().data.childDailyRecords.map { it.weight }.toSet())
    }

    @Test
    fun failedOverwriteRollsBackAllDeletedAndInsertedRows() = withDatabase { db ->
        val importer = BabyBackupImporter(db)
        val exporter = BabyBackupExporter(db)
        importer.importOverwrite(sampleEnvelope())
        val original = exporter.buildEnvelope().data
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_test_sleep BEFORE INSERT ON Sleep_Records BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END"
        )
        val incoming = sampleEnvelope().let { envelope ->
            envelope.copy(data = envelope.data.copy(sleepRecords = listOf(
                BackupSleepRecord("sleep:1", stableId, 1000, 2000, 1000, "", 1000)
            )))
        }
        val failure = runCatching { importer.importOverwrite(incoming) }
        assertTrue(failure.isFailure)
        assertEquals(original, exporter.buildEnvelope().data)
    }

    private fun sampleEnvelope(): BabyBackupEnvelope = BabyBackupEnvelope(
        version = BABY_BACKUP_VERSION,
        createdAt = 1000,
        data = BabyBackupData(
            babies = listOf(BackupBaby(stableId, "测试", "boy", 0, 0f, 0f, "", "")),
            feedingRecords = emptyList(),
            sleepRecords = emptyList(),
            eventRecords = emptyList(),
            childDailyRecords = listOf(
                BackupChildDailyRecord("daily:2", stableId, 1000, 5f, 51f, 31f, "第二张", "第二条"),
                BackupChildDailyRecord("daily:1", stableId, 1000, 4f, 50f, 30f, "第一张", "第一条")
            )
        )
    )

    private fun withDatabase(block: suspend (BabyDatabase) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, BabyDatabase::class.java).build()
        try {
            runBlocking { block(database) }
        } finally {
            database.close()
        }
    }
}
