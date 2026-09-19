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
    }.toTypedArray()

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
