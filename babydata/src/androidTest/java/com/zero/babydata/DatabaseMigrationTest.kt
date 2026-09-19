package com.zero.babydata

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonParser
import com.zero.babydata.backup.BabyBackupExporter
import com.zero.babydata.backup.BabyBackupImporter
import com.zero.babydata.backup.BabyBackupParser
import com.zero.babydata.room.BabyDatabase
import com.zero.babydata.room.DatabaseMigrations
import com.zero.babydata.room.BabyRepository
import com.zero.babydata.domain.RecordWritePayload
import com.zero.babydata.entity.FeedingRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** 使用独立合成数据库验证 Room 真正打开和校验迁移结果，绝不访问应用真实数据库。 */
@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext

    @Test
    fun versionFourKeepsAllRecordsAndSameDayMeasurements() = verifyMigration(4, 4)

    @Test
    fun legacyVersionOneAddsMissingColumnsWithoutDeletingRecords() = verifyMigration(1, 3, legacy = true)

    @Test
    fun legacyVersionTwoAddsMissingColumnsWithoutDeletingRecords() = verifyMigration(2, 3, legacy = true)

    @Test
    fun versionThreeKeepsAllRecords() = verifyMigration(3, 3)

    @Test
    fun resetVersionOnePreservesStableIdentityAndRemovesUniqueDayIndex() = verifyMigration(1, 1)

    @Test
    fun versionFiveAddsEmptyReceiptsAndKeepsEveryExistingRecord() = verifyMigration(5, 5)

    @Test
    fun versionSixAddsIndexesWithoutChangingRecordsReceiptsOrQueryOrder() = verifyIndexMigration(6)

    @Test
    fun versionSevenReplacesStartIndexesWithoutChangingRecordsReceiptsOrQueryOrder() = verifyIndexMigration(7)

    @Test
    fun versionEightAddsCommitRecoveryWithoutChangingExistingData() = verifyIndexMigration(8)

    private fun verifyIndexMigration(sourceVersion: Int) = runBlocking {
        val name = "migration-index-test-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        val requestId = "fc4c3a8e-92bc-4684-b745-cb2cbd36f9fa"
        val savedRecord = FeedingRecord(
            feedingId = 31, babyId = 7, feedingType = 1, feedingStart = 3_000,
            feedingEnd = 15_000, feedingDuration = 12_000, note = "索引迁移喂养", createdAt = 3_000
        )
        val tables = listOf("Baby_Info", "Feeding_Records", "Sleep_Records", "Event_Records", "ChildDailyRecord", "Record_Write_Receipts")
        var originalRows: Map<String, List<List<String?>>> = emptyMap()
        try {
            val schema = instrumentation.context.assets.open("com.zero.babydata.room.BabyDatabase/$sourceVersion.json")
                .bufferedReader().use { JsonParser.parseReader(it).asJsonObject.getAsJsonObject("database") }
            SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
                schema.getAsJsonArray("entities").forEach { element ->
                    val entity = element.asJsonObject
                    val table = entity.get("tableName").asString
                    db.execSQL(entity.get("createSql").asString.replace("\${TABLE_NAME}", table))
                    entity.getAsJsonArray("indices").forEach { index ->
                        db.execSQL(index.asJsonObject.get("createSql").asString.replace("\${TABLE_NAME}", table))
                    }
                }
                for (id in 7..8) {
                    db.execSQL("INSERT INTO Baby_Info VALUES ($id,'${UUID.randomUUID()}','合成$id','boy',0,0,0,'','')")
                }
                // 非时间顺序插入，分别验证开始时间排序、结束时间末条以及宝宝隔离。
                for (n in listOf(3, 1, 2)) {
                    db.execSQL("INSERT INTO Feeding_Records (feedingId,babyId,feedingType,feedingStart,feedingEnd,feedingDuration,feedingDurationBreastLeft,feedingDurationBreastRight,note,createdAt,isFirstTime) VALUES (${n * 10 + 1},7,1,${n * 1000},${n * 5000},${n * 4000},0,0,'索引迁移喂养',${n * 1000},0)")
                    db.execSQL("INSERT INTO Sleep_Records VALUES (${n * 10 + 2},7,${n * 1000},${n * 5000},${n * 4000},'索引迁移睡眠',${n * 1000})")
                    db.execSQL("INSERT INTO Event_Records (eventId,babyId,type,time,endTime,extraData,note,createdAt) VALUES (${n * 10 + 3},7,301,${n * 1000},0,'{}','索引迁移事件',${n * 1000})")
                }
                db.execSQL("INSERT INTO Feeding_Records (feedingId,babyId,feedingType,feedingStart,feedingEnd,feedingDuration,feedingDurationBreastLeft,feedingDurationBreastRight,note,createdAt,isFirstTime) VALUES (41,8,1,9000,10000,1000,0,0,'另一个合成宝宝',9000,0)")
                db.execSQL("INSERT INTO Sleep_Records VALUES (42,8,9000,10000,1000,'另一个合成宝宝',9000)")
                db.execSQL("INSERT INTO Event_Records (eventId,babyId,type,time,endTime,extraData,note,createdAt) VALUES (43,8,301,9000,0,'{}','另一个合成宝宝',9000)")
                db.execSQL("INSERT INTO ChildDailyRecord VALUES (14,7,1000,4,50,30,'合成照片一','原始一')")
                db.execSQL("INSERT INTO ChildDailyRecord VALUES (15,7,1000,5,51,31,'合成照片二','原始二')")
                db.execSQL("INSERT INTO Record_Write_Receipts VALUES (?,7,1,31,?)", arrayOf(requestId, RecordWritePayload.feeding(savedRecord)))
                originalRows = tables.associateWith { table -> readRows(db.rawQuery("SELECT * FROM `$table` ORDER BY 1", null)) }
                db.version = sourceVersion
            }
            val room = Room.databaseBuilder(context, BabyDatabase::class.java, name)
                .addMigrations(*DatabaseMigrations.getAllMigrations()).build()
            try {
                val db = room.openHelper.writableDatabase
                assertEquals(9, db.version)
                for (table in listOf("Baby_Creation_Receipts", "Backup_Import_Commits")) {
                    db.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals(0, cursor.getInt(0))
                    }
                }
                assertEquals(originalRows, tables.associateWith { table -> readRows(db.query("SELECT * FROM `$table` ORDER BY 1")) })
                val indexes = mapOf(
                    "index_Feeding_Records_babyId_feedingStart_feedingEnd" to listOf("babyId", "feedingStart", "feedingEnd"),
                    "index_Feeding_Records_babyId_feedingEnd" to listOf("babyId", "feedingEnd"),
                    "index_Sleep_Records_babyId_sleepStart_sleepEnd" to listOf("babyId", "sleepStart", "sleepEnd"),
                    "index_Sleep_Records_babyId_sleepEnd" to listOf("babyId", "sleepEnd"),
                    "index_Event_Records_babyId_time" to listOf("babyId", "time"),
                    "index_Event_Records_babyId_type_time" to listOf("babyId", "type", "time")
                )
                indexes.forEach { (index, columns) ->
                    val actual = db.query("PRAGMA index_info(`$index`)").use { cursor ->
                        buildList { while (cursor.moveToNext()) add(cursor.getString(2)) }
                    }
                    assertEquals(columns, actual)
                }
                for ((table, start, end) in listOf(
                    Triple("Feeding_Records", "feedingStart", "feedingEnd"),
                    Triple("Sleep_Records", "sleepStart", "sleepEnd")
                )) {
                    val indexNames = db.query("PRAGMA index_list(`$table`)").use { cursor ->
                        buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
                    }
                    assertFalse(indexNames.contains("index_${table}_babyId_$start"))
                    // 只断言使用所需索引，不绑定不同 Android SQLite 版本的 EXPLAIN 完整措辞。
                    for (order in listOf("ASC", "DESC")) {
                        val plan = db.query(SimpleSQLiteQuery(
                            "EXPLAIN QUERY PLAN SELECT * FROM `$table` WHERE babyId = ? AND `$start` <= ? AND `$end` > ? ORDER BY `$start` $order",
                            arrayOf(7, 3_000, 1_500)
                        )).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(3)) } }
                        assertTrue("跨日查询应选择包含结束时间的索引", plan.any {
                            it.contains("index_${table}_babyId_${start}_$end")
                        })
                    }
                }
                val repository = BabyRepository(room)
                assertEquals(listOf(31, 21), repository.getRecentFeedings(7, 2).map { it.feedingId })
                assertEquals(31, repository.getLastFeedingRecord(7)?.feedingId)
                assertEquals(listOf(32, 22), repository.getRecentSleeps(7, 2).map { it.sleepId })
                assertEquals(32, repository.getLastSleepRecord(7)?.sleepId)
                assertEquals(listOf(33, 23), repository.getLatestEventRecordsByType(7, 301, 2).map { it.eventId })
                assertEquals(listOf(11, 21), repository.getFeedingRecordsBetween(7, 1_000, 2_000).map { it.feedingId })
                assertEquals(listOf(12, 22), repository.getSleepRecordsBetween(7, 1_000, 2_000).map { it.sleepId })
                assertEquals(listOf(13, 23), repository.getEventRecordsBetween(7, 1_000, 2_000).map { it.eventId })
                assertEquals(listOf(31, 21, 11), repository.getFeedingRecordsIntersectingDay(7, 1_500, 3_000).map { it.feedingId })
                assertEquals(listOf(12, 22, 32), repository.getSleepRecordsIntersectingRange(7, 1_500, 3_000).map { it.sleepId })
                // 开始时间端点包含，结束时间端点排除；更换索引不能改变跨日边界语义。
                assertEquals(listOf(11), repository.getFeedingRecordsIntersectingDay(7, 0, 1_000).map { it.feedingId })
                assertEquals(listOf(31, 21), repository.getFeedingRecordsIntersectingDay(7, 5_000, 5_000).map { it.feedingId })
                assertEquals(listOf(22, 32), repository.getSleepRecordsIntersectingRange(7, 5_000, 5_000).map { it.sleepId })
                // 升级后仍识别提交过的请求，忽略重试创建时间且不新增任何行。
                assertEquals(31, repository.insertFeedingRecordOnce(savedRecord.copy(createdAt = 99_999), requestId))
                assertEquals(originalRows, tables.associateWith { table -> readRows(db.query("SELECT * FROM `$table` ORDER BY 1")) })
                db.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
            } finally {
                room.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    private fun readRows(cursor: Cursor): List<List<String?>> = cursor.use {
        buildList {
            while (it.moveToNext()) add((0 until it.columnCount).map { column ->
                if (it.isNull(column)) null else it.getString(column)
            })
        }
    }

    private fun verifyMigration(version: Int, schemaVersion: Int, legacy: Boolean = false) {
        val name = "migration-test-${UUID.randomUUID()}.db"
        val originalStableId = "8668a9e3-406d-478c-af23-6b4ad5327458"
        val path = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        try {
            val schema = instrumentation.context.assets
                .open("com.zero.babydata.room.BabyDatabase/$schemaVersion.json")
                .bufferedReader().use { JsonParser.parseReader(it).asJsonObject.getAsJsonObject("database") }
            SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
                schema.getAsJsonArray("entities").forEach { element ->
                    val entity = element.asJsonObject
                    val table = entity.get("tableName").asString
                    var sql = entity.get("createSql").asString.replace("\${TABLE_NAME}", table)
                    if (legacy && table == "Feeding_Records") {
                        listOf("feedingAmount", "babyMood", "feedingLocation").forEach { column ->
                            sql = sql.replace(", `$column` INTEGER", "")
                        }
                    }
                    db.execSQL(sql)
                    entity.getAsJsonArray("indices").forEach { index ->
                        db.execSQL(index.asJsonObject.get("createSql").asString.replace("\${TABLE_NAME}", table))
                    }
                }
                val hasStableId = schemaVersion == 1 || schemaVersion >= 5
                val stableColumn = if (hasStableId) ",stableId" else ""
                val stableValue = if (hasStableId) ",'$originalStableId'" else ""
                db.execSQL("INSERT INTO Baby_Info (babyId,name,gender,birthDate,birthWeight,birthHeight,bloodType,extra$stableColumn) VALUES (7,'测试','boy',0,3200,50,'',''$stableValue)")
                val solidColumns = if (schemaVersion == 1 || schemaVersion >= 4) ",isFirstTime" else ""
                val solidValues = if (solidColumns.isEmpty()) "" else ",0"
                db.execSQL("INSERT INTO Feeding_Records (feedingId,babyId,feedingType,feedingStart,feedingEnd,feedingDuration,feedingDurationBreastLeft,feedingDurationBreastRight,note,createdAt$solidColumns) VALUES (11,7,1,1000,2000,1000,1000,0,'保留喂养',1000$solidValues)")
                db.execSQL("INSERT INTO Sleep_Records VALUES (12,7,1000,2000,1000,'保留睡眠',1000)")
                val extraColumns = if (schemaVersion == 1 || schemaVersion >= 4) ",endTime,extraData" else ""
                val extraValues = if (extraColumns.isEmpty()) "" else ",0,''"
                db.execSQL("INSERT INTO Event_Records (eventId,babyId,type,time,note,createdAt$extraColumns) VALUES (13,7,101,1000,'保留事件',1000$extraValues)")
                db.execSQL("INSERT INTO ChildDailyRecord VALUES (14,7,1000,4,50,30,'第一张','第一条')")
                if (schemaVersion != 1) {
                    db.execSQL("INSERT INTO ChildDailyRecord VALUES (15,7,1000,5,51,31,'第二张','第二条')")
                }
                db.version = version
            }

            val room = Room.databaseBuilder(context, BabyDatabase::class.java, name)
                .addMigrations(*DatabaseMigrations.getAllMigrations())
                .build()
            try {
                    val db = room.openHelper.writableDatabase
                    assertEquals(9, db.version)
                    db.query("SELECT COUNT(*) FROM Record_Write_Receipts").use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals(0, cursor.getInt(0))
                    }
                    runBlocking {
                        val baby = requireNotNull(room.babyInfoDao().getBabyInfo(7))
                        assertEquals("测试", baby.name)
                        assertEquals(0L, baby.birthDate)
                        assertEquals(baby.stableId, UUID.fromString(baby.stableId).toString())
                        if (schemaVersion == 1 || schemaVersion >= 5) assertEquals(originalStableId, baby.stableId)
                        assertEquals("保留喂养", room.feedingRecordDao().getFeedingRecordById(11)?.note)
                        assertEquals("保留睡眠", room.sleepRecordDao().getSleepRecordById(12)?.note)
                        assertEquals("保留事件", room.eventRecordDao().getEventRecordById(13)?.note)
                        val daily = room.childDailyRecordDao().getAllRecordsByBabyId(7)
                        assertEquals(if (schemaVersion == 1) 1 else 2, daily.size)
                        assertEquals(if (schemaVersion == 1) "第一条" else "第二条", daily.first().extra)
                        assertEquals(daily.first(), room.childDailyRecordDao().getRecordByDate(7, 1000))

                        // 未填生日、同日重复测量都必须通过真实导出与覆盖导入往返。
                        val exporter = BabyBackupExporter(room)
                        val backup = exporter.buildEnvelope()
                        val parsed = BabyBackupParser.parse(exporter.toJson(backup))
                        BabyBackupImporter(room).importOverwrite(parsed)
                        val restored = exporter.buildEnvelope()
                        assertEquals(backup.data.babies, restored.data.babies)
                        assertEquals(backup.data.feedingRecords.size, restored.data.feedingRecords.size)
                        assertEquals(backup.data.sleepRecords.size, restored.data.sleepRecords.size)
                        assertEquals(backup.data.eventRecords.size, restored.data.eventRecords.size)
                        assertEquals(
                            backup.data.childDailyRecords.map { it.copy(sourceId = "") },
                            restored.data.childDailyRecords.map { it.copy(sourceId = "") }
                        )
                    }
                    db.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
                    db.query("PRAGMA index_list(ChildDailyRecord)").use { cursor ->
                        while (cursor.moveToNext()) assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("unique")))
                    }
                    assertTrue(db.isOpen)
            } finally {
                room.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
