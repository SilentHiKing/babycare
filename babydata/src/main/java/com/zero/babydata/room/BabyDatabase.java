package com.zero.babydata.room;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

import com.zero.babydata.entity.BabyInfo;
import com.zero.babydata.entity.ChildDailyRecord;
import com.zero.babydata.entity.EventRecord;
import com.zero.babydata.entity.FeedingRecord;
import com.zero.babydata.entity.SleepRecord;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Database(entities = {BabyInfo.class, FeedingRecord.class, SleepRecord.class, ChildDailyRecord.class, EventRecord.class}, version = 1)
public abstract class BabyDatabase extends RoomDatabase {

    private static BabyDatabase instance;

    public abstract BabyInfoDao babyInfoDao();

    public abstract FeedingRecordDao feedingRecordDao();

    public abstract SleepRecordDao sleepRecordDao();

    public abstract ChildDailyRecordDao childDailyRecordDao();

    public abstract EventRecordDao eventRecordDao();

    public static synchronized BabyDatabase getInstance(Context context) {
        if (instance == null) {
            instance = Room.databaseBuilder(context.getApplicationContext(),
                            BabyDatabase.class, "baby_database")
                    .fallbackToDestructiveMigration()  // Handle migration automatically
                    .build();
        }
        return instance;
    }

    private static final ExecutorService databaseWriteExecutor = Executors.newFixedThreadPool(4);

    public static ExecutorService getDatabaseWriteExecutor() {
        return databaseWriteExecutor;
    }
}
