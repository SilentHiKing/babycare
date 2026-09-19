package com.zero.babydata

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonParser
import com.zero.babydata.backup.BabyBackupExporter
import com.zero.babydata.backup.BabyBackupImporter
import com.zero.babydata.backup.BabyBackupParser
import com.zero.babydata.room.BabyDatabase
import com.zero.babydata.room.DatabaseMigrations
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
                val stableColumn = if (schemaVersion == 1) ",stableId" else ""
                val stableValue = if (schemaVersion == 1) ",'$originalStableId'" else ""
                db.execSQL("INSERT INTO Baby_Info (babyId,name,gender,birthDate,birthWeight,birthHeight,bloodType,extra$stableColumn) VALUES (7,'测试','boy',0,3200,50,'',''$stableValue)")
                val solidColumns = if (schemaVersion == 1 || schemaVersion == 4) ",isFirstTime" else ""
                val solidValues = if (solidColumns.isEmpty()) "" else ",0"
                db.execSQL("INSERT INTO Feeding_Records (feedingId,babyId,feedingType,feedingStart,feedingEnd,feedingDuration,feedingDurationBreastLeft,feedingDurationBreastRight,note,createdAt$solidColumns) VALUES (11,7,1,1000,2000,1000,1000,0,'保留喂养',1000$solidValues)")
                db.execSQL("INSERT INTO Sleep_Records VALUES (12,7,1000,2000,1000,'保留睡眠',1000)")
                val extraColumns = if (schemaVersion == 1 || schemaVersion == 4) ",endTime,extraData" else ""
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
                    assertEquals(5, db.version)
                    runBlocking {
                        val baby = requireNotNull(room.babyInfoDao().getBabyInfo(7))
                        assertEquals("测试", baby.name)
                        assertEquals(0L, baby.birthDate)
                        assertEquals(baby.stableId, UUID.fromString(baby.stableId).toString())
                        if (schemaVersion == 1) assertEquals(originalStableId, baby.stableId)
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
