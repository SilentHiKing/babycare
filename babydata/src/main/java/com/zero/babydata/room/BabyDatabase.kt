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

@Database(
    entities = [
        BabyInfo::class,
        FeedingRecord::class,
        SleepRecord::class,
        ChildDailyRecord::class,
        EventRecord::class
    ],
    version = 5,
    exportSchema = true
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

        @JvmStatic
        fun getInstance(context: Context): BabyDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    BabyDatabase::class.java,
                    "baby_database"
                )
                    .addMigrations(*DatabaseMigrations.getAllMigrations())
                    .build()
                    .also { instance = it }
            }
        }
    }
}
