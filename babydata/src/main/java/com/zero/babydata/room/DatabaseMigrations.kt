package com.zero.babydata.room

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 数据库迁移管理
 * 
 * 商业应用必须保护用户数据，禁止使用 fallbackToDestructiveMigration()
 * 所有数据库变更必须通过 Migration 实现
 */
object DatabaseMigrations {

    /**
     * v1 -> v2: 初始迁移（如果需要）
     * 根据实际情况补充
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // v2 的变更内容（如果有）
            // 如果 v2 没有结构变更，可以留空
        }
    }

    /**
     * v2 -> v3: FeedingRecord 添加新字段
     * - feedingAmount: 喂奶量（毫升）
     * - babyMood: 宝宝状态
     * - feedingLocation: 喂奶场景
     */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 添加 feedingAmount 字段（可空 INTEGER）
            db.execSQL("ALTER TABLE Feeding_Records ADD COLUMN feedingAmount INTEGER DEFAULT NULL")
            
            // 添加 babyMood 字段（可空 INTEGER）
            db.execSQL("ALTER TABLE Feeding_Records ADD COLUMN babyMood INTEGER DEFAULT NULL")
            
            // 添加 feedingLocation 字段（可空 INTEGER）
            db.execSQL("ALTER TABLE Feeding_Records ADD COLUMN feedingLocation INTEGER DEFAULT NULL")
        }
    }
    
    /**
     * v3 -> v4: EventRecord 扩展 + FeedingRecord 辅食字段
     * EventRecord:
     * - endTime: 结束时间（用于有时长的事件）
     * - extraData: JSON格式扩展数据
     * - type 字段添加索引
     * FeedingRecord:
     * - solidFoodType: 辅食类型
     * - foodName: 食物名称
     * - isFirstTime: 是否首次尝试
     */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // ==================== EventRecord ====================
            // 添加 endTime 字段
            db.execSQL("ALTER TABLE Event_Records ADD COLUMN endTime INTEGER NOT NULL DEFAULT 0")
            
            // 添加 extraData 字段
            db.execSQL("ALTER TABLE Event_Records ADD COLUMN extraData TEXT NOT NULL DEFAULT ''")
            
            // 为 type 添加索引（加速按类型查询）
            db.execSQL("CREATE INDEX IF NOT EXISTS index_Event_Records_type ON Event_Records(type)")
            
            // ==================== FeedingRecord ====================
            // 添加 solidFoodType 字段（辅食类型）
            db.execSQL("ALTER TABLE Feeding_Records ADD COLUMN solidFoodType INTEGER DEFAULT NULL")
            
            // 添加 foodName 字段（食物名称）
            db.execSQL("ALTER TABLE Feeding_Records ADD COLUMN foodName TEXT DEFAULT NULL")
            
            // 添加 isFirstTime 字段（是否首次尝试）
            db.execSQL("ALTER TABLE Feeding_Records ADD COLUMN isFirstTime INTEGER NOT NULL DEFAULT 0")
        }
    }

    /**
     * 获取所有迁移
     */
    fun getAllMigrations(): Array<Migration> = arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4
    )
}

