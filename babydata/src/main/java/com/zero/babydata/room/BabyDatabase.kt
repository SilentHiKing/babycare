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
    version = 3,  // v3: FeedingRecord 添加 feedingAmount, babyMood, feedingLocation 字段
    exportSchema = false
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
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
        }

        @JvmStatic
        fun getDatabaseWriteExecutor(): ExecutorService = databaseWriteExecutor
    }
}
