package com.zero.babydata.room

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.zero.babydata.entity.BabyInfo
import com.zero.babydata.entity.ChildDailyRecord
import com.zero.babydata.entity.EventRecord
import com.zero.babydata.entity.FeedingRecord
import com.zero.babydata.entity.SleepRecord
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Database(
    entities = [
        BabyInfo::class,
        FeedingRecord::class,
        SleepRecord::class,
        ChildDailyRecord::class,
        EventRecord::class
    ],
    version = 4,  // v4: EventRecord 添加 endTime, extraData 字段
    exportSchema = true  // 商业应用建议开启，便于追踪数据库变更
)
abstract class BabyDatabase : RoomDatabase() {

    abstract fun babyInfoDao(): BabyInfoDao
    abstract fun feedingRecordDao(): FeedingRecordDao
    abstract fun sleepRecordDao(): SleepRecordDao
    abstract fun childDailyRecordDao(): ChildDailyRecordDao
    abstract fun eventRecordDao(): EventRecordDao

    companion object {
        @Volatile
        private var instance: BabyDatabase? = null

        private val databaseWriteExecutor: ExecutorService = Executors.newFixedThreadPool(4)

        @JvmStatic
        fun getInstance(context: Context): BabyDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    BabyDatabase::class.java,
                    "baby_database"
                )
                    .allowMainThreadQueries()
                    // 商业应用禁止使用 fallbackToDestructiveMigration()
                    // 必须通过 Migration 保护用户数据
                    .addMigrations(*DatabaseMigrations.getAllMigrations())
                    .build()
                    .also { instance = it }
            }
        }

        @JvmStatic
        fun getDatabaseWriteExecutor(): ExecutorService = databaseWriteExecutor
    }
}
