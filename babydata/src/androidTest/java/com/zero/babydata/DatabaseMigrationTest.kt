package com.zero.babydata

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonParser
import com.zero.babydata.entity.BabyInfo
import com.zero.babydata.room.BabyDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** 未上线版本按约定重建；只操作独立合成库，永不打开应用数据库。 */
@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun versionTenCreatesTimerColumnsAndKeepsDataOnReopen() = runBlocking {
        val name = "timer-schema-${UUID.randomUUID()}.db"
        try {
            var database = BabyDatabase.createBuilder(context, name).build()
            val babyId = try {
                assertEquals(10, database.openHelper.writableDatabase.version)
                for (table in listOf("Feeding_Records", "Sleep_Records", "Event_Records")) {
                    val columns = mutableSetOf<String>()
                    database.openHelper.writableDatabase.query("PRAGMA table_info(`$table`)").use { cursor ->
                        while (cursor.moveToNext()) columns += cursor.getString(cursor.getColumnIndexOrThrow("name"))
                    }
                    assertTrue(columns.containsAll(listOf("timerStatus", "timerSessionId", "timerRevision", "timerData", "timerSlot")))
                }
                database.babyInfoDao().insertBabyInfo(BabyInfo(name = "合成宝宝")).toInt()
            } finally { database.close() }
            database = BabyDatabase.createBuilder(context, name).build()
            try { assertEquals(babyId, database.babyInfoDao().getAllBabyInfo().single().babyId) }
            finally { database.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test
    fun versionNineIsExplicitlyRebuiltButFutureVersionsAreNotSilentlyErased() = runBlocking {
        val name = "timer-rebuild-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        try {
            val schema = instrumentation.context.assets.open("com.zero.babydata.room.BabyDatabase/9.json")
                .bufferedReader().use { JsonParser.parseReader(it).asJsonObject.getAsJsonObject("database") }
            SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
                schema.getAsJsonArray("entities").forEach { element ->
                    val entity = element.asJsonObject
                    val table = entity.get("tableName").asString
                    db.execSQL(entity.get("createSql").asString.replace("\${TABLE_NAME}", table))
                    // 无二级索引的表在导出 schema 中省略 indices，而非输出空数组。
                    entity.getAsJsonArray("indices")?.forEach { index ->
                        db.execSQL(index.asJsonObject.get("createSql").asString.replace("\${TABLE_NAME}", table))
                    }
                }
                schema.getAsJsonArray("setupQueries").forEach { db.execSQL(it.asString) }
                db.execSQL("INSERT INTO Baby_Info VALUES (7,?,'合成旧宝宝','boy',0,0,0,'','')", arrayOf(UUID.randomUUID().toString()))
                db.version = 9
            }
            val database = BabyDatabase.createBuilder(context, name).build()
            try {
                assertEquals(10, database.openHelper.writableDatabase.version)
                assertTrue(database.babyInfoDao().getAllBabyInfo().isEmpty())
            } finally { database.close() }
            // 清库白名单不能覆盖未来版本；模拟未知高版本，验证打开失败且原文件版本未改。
            SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READWRITE).use { it.version = 11 }
            val future = BabyDatabase.createBuilder(context, name).build()
            try { assertTrue(runCatching { future.openHelper.writableDatabase }.isFailure) }
            finally { future.close() }
            SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY).use { assertEquals(11, it.version) }
        } finally { context.deleteDatabase(name) }
    }
}
