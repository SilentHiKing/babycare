package com.zero.babydata.room

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.blankj.utilcode.util.LogUtils
import java.util.UUID

/** 保留原始行和外键关系，兼容历史版本及曾错误重置版本号的新结构 v1。 */
object DatabaseMigrations {

    fun getAllMigrations(): Array<Migration> = (1..4).map { version ->
        object : Migration(version, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                LogUtils.i("数据库迁移开始：版本 $version → 5")
                migrateToVersionFive(db)
                LogUtils.i("数据库迁移完成：版本 5，历史记录已保留")
            }
        }
    }.plus(object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            LogUtils.i("数据库迁移开始：版本 5 → 6")
            // 回执只保存摘要和本机 ID，不修改历史记录，也不进入备份协议。
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS Record_Write_Receipts (
                    requestId TEXT NOT NULL PRIMARY KEY,
                    babyId INTEGER NOT NULL,
                    recordType INTEGER NOT NULL,
                    recordId INTEGER NOT NULL,
                    payloadHash TEXT NOT NULL,
                    FOREIGN KEY(babyId) REFERENCES Baby_Info(babyId) ON UPDATE NO ACTION ON DELETE CASCADE
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS index_Record_Write_Receipts_babyId ON Record_Write_Receipts(babyId)")
            LogUtils.i("数据库迁移完成：版本 6，保存回执已启用")
        }
    }).plus(object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            LogUtils.i("数据库迁移开始：版本 6 → 7")
            // 只添加查询索引，不重建表、不改写记录或幂等回执，旧版本仍沿原链无损升级。
            db.execSQL("CREATE INDEX IF NOT EXISTS index_Feeding_Records_babyId_feedingStart ON Feeding_Records(babyId, feedingStart)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_Feeding_Records_babyId_feedingEnd ON Feeding_Records(babyId, feedingEnd)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_Sleep_Records_babyId_sleepStart ON Sleep_Records(babyId, sleepStart)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_Sleep_Records_babyId_sleepEnd ON Sleep_Records(babyId, sleepEnd)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_Event_Records_babyId_time ON Event_Records(babyId, time)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_Event_Records_babyId_type_time ON Event_Records(babyId, type, time)")
            LogUtils.i("数据库迁移完成：版本 7，历史查询索引已启用")
        }
    }).plus(object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            LogUtils.i("数据库迁移开始：版本 7 → 8")
            // 先建立覆盖过滤条件的索引，再删除被其前缀替代的旧索引；记录和回执保持原样。
            db.execSQL("CREATE INDEX IF NOT EXISTS index_Feeding_Records_babyId_feedingStart_feedingEnd ON Feeding_Records(babyId, feedingStart, feedingEnd)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_Sleep_Records_babyId_sleepStart_sleepEnd ON Sleep_Records(babyId, sleepStart, sleepEnd)")
            db.execSQL("DROP INDEX IF EXISTS index_Feeding_Records_babyId_feedingStart")
            db.execSQL("DROP INDEX IF EXISTS index_Sleep_Records_babyId_sleepStart")
            LogUtils.i("数据库迁移完成：版本 8，跨日查询索引已优化")
        }
    }).toTypedArray()

    private fun migrateToVersionFive(db: SupportSQLiteDatabase) {
        // 同为 v1 的两种结构不能按版本号猜测，逐列补齐也兼容 v2/v3 的历史迁移。
        addColumnIfMissing(db, "Feeding_Records", "feedingAmount", "INTEGER")
        addColumnIfMissing(db, "Feeding_Records", "babyMood", "INTEGER")
        addColumnIfMissing(db, "Feeding_Records", "feedingLocation", "INTEGER")
        addColumnIfMissing(db, "Feeding_Records", "solidFoodType", "INTEGER")
        addColumnIfMissing(db, "Feeding_Records", "foodName", "TEXT")
        addColumnIfMissing(db, "Feeding_Records", "isFirstTime", "INTEGER NOT NULL DEFAULT 0")
        addColumnIfMissing(db, "Event_Records", "endTime", "INTEGER NOT NULL DEFAULT 0")
        addColumnIfMissing(db, "Event_Records", "extraData", "TEXT NOT NULL DEFAULT ''")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_Event_Records_type ON Event_Records(type)")

        addColumnIfMissing(db, "Baby_Info", "stableId", "TEXT NOT NULL DEFAULT ''")
        // 只补新引入的空身份；已有稳定身份必须保留，否则后续备份合并会创建重复宝宝。
        val missingIdentityIds = mutableListOf<Int>()
        db.query("SELECT babyId FROM Baby_Info WHERE stableId = ''").use { cursor ->
            while (cursor.moveToNext()) missingIdentityIds += cursor.getInt(0)
        }
        missingIdentityIds.forEach { babyId ->
            db.execSQL(
                "UPDATE Baby_Info SET stableId = ? WHERE babyId = ?",
                arrayOf<Any>(UUID.randomUUID().toString(), babyId)
            )
        }
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_Baby_Info_stableId ON Baby_Info(stableId)")

        // 保留旧版本的同日多次测量，不覆盖、不删行；正常写入继续由 DAO 事务避免重复创建。
        db.execSQL("DROP INDEX IF EXISTS index_ChildDailyRecord_babyId_recordDate")
    }

    private fun addColumnIfMissing(
        db: SupportSQLiteDatabase,
        table: String,
        column: String,
        definition: String
    ) {
        val exists = db.query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            var found = false
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == column) found = true
            }
            found
        }
        if (!exists) db.execSQL("ALTER TABLE `$table` ADD COLUMN `$column` $definition")
    }
}
