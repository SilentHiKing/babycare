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
import com.zero.babydata.entity.RecordWriteReceipt
import com.zero.babydata.entity.BabyCreationReceipt
import com.zero.babydata.entity.BackupImportCommit

@Database(
    entities = [
        BabyInfo::class,
        FeedingRecord::class,
        SleepRecord::class,
        ChildDailyRecord::class,
        EventRecord::class,
        RecordWriteReceipt::class,
        BabyCreationReceipt::class,
        BackupImportCommit::class
    ],
    version = 9,
    exportSchema = true
)
abstract class BabyDatabase : RoomDatabase() {

    abstract fun babyInfoDao(): BabyInfoDao
    abstract fun feedingRecordDao(): FeedingRecordDao
    abstract fun sleepRecordDao(): SleepRecordDao
    abstract fun childDailyRecordDao(): ChildDailyRecordDao
    abstract fun eventRecordDao(): EventRecordDao
    abstract fun recordWriteReceiptDao(): RecordWriteReceiptDao
    abstract fun babyCreationReceiptDao(): BabyCreationReceiptDao
    abstract fun backupImportCommitDao(): BackupImportCommitDao

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
