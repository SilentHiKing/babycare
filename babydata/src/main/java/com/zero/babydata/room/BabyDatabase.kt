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
    version = 10,
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
                instance ?: createBuilder(context, "baby_database").build()
                    .also { instance = it }
            }
        }

        /** 项目未上线，仅清理旧实验版本；未来版本缺迁移必须报错，不能继续静默清库。 */
        internal fun createBuilder(context: Context, name: String): RoomDatabase.Builder<BabyDatabase> =
            Room.databaseBuilder(context.applicationContext, BabyDatabase::class.java, name)
                .fallbackToDestructiveMigrationFrom(true, 1, 2, 3, 4, 5, 6, 7, 8, 9)
    }
}
