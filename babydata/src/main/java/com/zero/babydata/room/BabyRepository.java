package com.zero.babydata.room;

import android.content.Context;
import android.os.Handler;
import android.view.ActionMode;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.sqlite.db.SupportSQLiteOpenHelper;

import com.zero.babydata.entity.BabyInfo;
import com.zero.babydata.entity.ChildDailyRecord;
import com.zero.babydata.entity.EventRecord;
import com.zero.babydata.entity.FeedingRecord;
import com.zero.babydata.entity.SleepRecord;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BabyRepository {

    private BabyInfoDao babyInfoDao;
    private FeedingRecordDao feedingRecordDao;
    private SleepRecordDao sleepRecordDao;
    private ChildDailyRecordDao childDailyRecordDao;
    private EventRecordDao eventRecordDao;

    public BabyRepository(Context context) {
        BabyDatabase db = BabyDatabase.getInstance(context);
        babyInfoDao = db.babyInfoDao();
        feedingRecordDao = db.feedingRecordDao();
        sleepRecordDao = db.sleepRecordDao();
        childDailyRecordDao = db.childDailyRecordDao();
        eventRecordDao = db.eventRecordDao();
    }


    private void run(Runnable action, Runnable callback) {
        BabyDatabase.getDatabaseWriteExecutor().execute(() -> {
                    action.run();
                    if (callback != null) {
                        callback.run();
                    }
                }
        );
    }


    // 插入婴儿信息
    public void insertBabyInfo(BabyInfo babyInfo, Runnable callback) {
        Runnable action = new Runnable() {
            @Override
            public void run() {
                babyInfoDao.insertBabyInfo(babyInfo);
            }
        };

        run(action, callback);
    }

    // 插入喂奶记录
    public void insertFeedingRecord(FeedingRecord feedingRecord, Runnable callback) {
        Runnable action = new Runnable() {
            @Override
            public void run() {
                feedingRecordDao.insertFeedingRecord(feedingRecord);
            }
        };
        run(action, callback);
    }

    // 插入睡觉记录
    public void insertSleepRecord(SleepRecord sleepRecord) {
        BabyDatabase.getDatabaseWriteExecutor().execute(() -> sleepRecordDao.insertSleepRecord(sleepRecord));
    }

    public void insertEventRecord(EventRecord record) {
        BabyDatabase.getDatabaseWriteExecutor().execute(() -> eventRecordDao.insertEventRecord(record));
    }

    public void deleteEventRecord(EventRecord record) {
        BabyDatabase.getDatabaseWriteExecutor().execute(() -> eventRecordDao.deleteEventRecord(record));
    }

    // 删除喂奶记录
    public void deleteFeedingRecord(FeedingRecord feedingRecord) {
        BabyDatabase.getDatabaseWriteExecutor().execute(() -> feedingRecordDao.deleteFeedingRecord(feedingRecord));
    }

    // 删除睡觉记录
    public void deleteSleepRecord(SleepRecord sleepRecord) {
        BabyDatabase.getDatabaseWriteExecutor().execute(() -> sleepRecordDao.deleteSleepRecord(sleepRecord));
    }

    // 删除喂奶记录通过 ID
    public void deleteFeedingRecordById(int feedingId) {
        BabyDatabase.getDatabaseWriteExecutor().execute(() -> feedingRecordDao.deleteFeedingRecordById(feedingId));
    }

    // 删除睡觉记录通过 ID
    public void deleteSleepRecordById(int sleepId) {
        BabyDatabase.getDatabaseWriteExecutor().execute(() -> sleepRecordDao.deleteSleepRecordById(sleepId));
    }


    public List<FeedingRecord> getFeedingRecordsForDay(int babyId, long startOfDay, long endOfDay) {
        return feedingRecordDao.getFeedingRecordsForDay(babyId, startOfDay, endOfDay);
    }

    public List<SleepRecord> getSleepRecordsForDay(int babyId, long startOfDay, long endOfDay) {
        return sleepRecordDao.getSleepRecordsForDay(babyId, startOfDay, endOfDay);
    }

    public FeedingRecord getLastFeedingRecord(int babyId) {
        return feedingRecordDao.getLastFeedingRecord(babyId);
    }

    public SleepRecord getLastSleepRecord(int babyId) {
        return sleepRecordDao.getLastSleepRecord(babyId);
    }

    public List<SleepRecord> getRecentSleeps(int babyId, int limit) {
        return sleepRecordDao.getRecentSleeps(babyId, limit);
    }

    public List<FeedingRecord> getRecentFeedings(int babyId, int limit) {
        return feedingRecordDao.getRecentFeedings(babyId, limit);
    }


    // 修改或插入某一天的体重、身高、pic 和 extra
    public void updateOrInsertChildDailyRecord(int babyId, Long recordDate, Float weight, Float height, Float headCircumference, String pic, String extra) {
        BabyDatabase.getDatabaseWriteExecutor().execute(() -> {
            // 先查询是否已有该日期的记录
            ChildDailyRecord existingRecord = childDailyRecordDao.getRecordByDate(babyId, recordDate);

            if (existingRecord != null) {
                // 如果记录存在，则更新体重、身高、pic 和 extra
                if (weight != null) {
                    childDailyRecordDao.updateWeight(babyId, recordDate, weight);
                }
                if (height != null) {
                    childDailyRecordDao.updateHeight(babyId, recordDate, height);
                }

                if (headCircumference != null) {
                    childDailyRecordDao.updateHeight(babyId, recordDate, headCircumference);
                }
                if (pic != null) {
                    childDailyRecordDao.updatePic(babyId, recordDate, pic);
                }
                if (extra != null) {
                    childDailyRecordDao.updateExtra(babyId, recordDate, extra);
                }
            } else {
                // 如果记录不存在，则插入新记录
                ChildDailyRecord newRecord = new ChildDailyRecord();
                newRecord.babyId = babyId;
                newRecord.recordDate = recordDate;
                newRecord.weight = weight;
                newRecord.height = height;
                newRecord.headCircumference = headCircumference;
                newRecord.pic = pic;
                newRecord.extra = extra;

                childDailyRecordDao.insertChildDailyRecord(newRecord);
            }
        });
    }

    // 获取婴儿信息
    public LiveData<BabyInfo> getBabyInfo(int babyId) {
        return new MutableLiveData<>(babyInfoDao.getBabyInfo(babyId));
    }

    public List<BabyInfo> getAllBabyInfo() {
        return babyInfoDao.getAllBabyInfo();
    }

    // 获取所有喂奶记录
    public LiveData<List<FeedingRecord>> getAllFeedingRecords(int babyId) {
        return new MutableLiveData<>(feedingRecordDao.getAllFeedingRecords(babyId));
    }

    // 获取所有睡觉记录
    public LiveData<List<SleepRecord>> getAllSleepRecords(int babyId) {
        return new MutableLiveData<>(sleepRecordDao.getAllSleepRecords(babyId));
    }

}
