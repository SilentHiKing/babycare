package com.zero.babydata.backup

import com.zero.babydata.entity.BabyInfo
import com.zero.babydata.entity.ChildDailyRecord
import com.zero.babydata.entity.EventRecord
import com.zero.babydata.entity.FeedingRecord
import com.zero.babydata.entity.SleepRecord

/** 当前备份协议版本；协议 DTO 与 Room Entity 独立演进。 */
const val BABY_BACKUP_VERSION = 1

enum class BackupImportStrategy {
    OVERWRITE,
    MERGE
}

data class BackupImportReport(
    val strategy: BackupImportStrategy,
    val babyInserted: Int,
    val babyMatched: Int,
    val babyDuplicateInBackup: Int,
    val feedingInserted: Int,
    val feedingSkipped: Int,
    val sleepInserted: Int,
    val sleepSkipped: Int,
    val eventInserted: Int,
    val eventSkipped: Int,
    val childDailyInserted: Int,
    val childDailyUpdated: Int
)

data class BabyBackupEnvelope(
    val version: Int,
    val createdAt: Long,
    val appVersion: String? = null,
    val data: BabyBackupData
)

data class BabyBackupData(
    val babies: List<BackupBaby>,
    val feedingRecords: List<BackupFeedingRecord>,
    val sleepRecords: List<BackupSleepRecord>,
    val eventRecords: List<BackupEventRecord>,
    val childDailyRecords: List<BackupChildDailyRecord>
)

data class BackupBaby(
    val stableId: String,
    val name: String,
    val gender: String,
    val birthDate: Long,
    val birthWeight: Float,
    val birthHeight: Float,
    val bloodType: String,
    val extra: String
)

data class BackupFeedingRecord(
    val sourceId: String,
    val babyStableId: String,
    val feedingType: Int,
    val feedingStart: Long,
    val feedingEnd: Long,
    val feedingDuration: Long,
    val feedingDurationBreastLeft: Long,
    val feedingDurationBreastRight: Long,
    val note: String,
    val createdAt: Long,
    val feedingAmount: Int?,
    val babyMood: Int?,
    val feedingLocation: Int?,
    val solidFoodType: Int?,
    val foodName: String?,
    val isFirstTime: Boolean
)

data class BackupSleepRecord(
    val sourceId: String,
    val babyStableId: String,
    val sleepStart: Long,
    val sleepEnd: Long,
    val sleepDuration: Long,
    val note: String,
    val createdAt: Long
)

data class BackupEventRecord(
    val sourceId: String,
    val babyStableId: String,
    val type: Int,
    val time: Long,
    val endTime: Long,
    val extraData: String,
    val note: String,
    val createdAt: Long
)

data class BackupChildDailyRecord(
    val sourceId: String,
    val babyStableId: String,
    val recordDate: Long,
    val weight: Float,
    val height: Float,
    val headCircumference: Float,
    val pic: String,
    val extra: String
)

internal fun BabyInfo.toBackup(): BackupBaby = BackupBaby(
    stableId = stableId,
    name = name,
    gender = gender,
    birthDate = birthDate,
    birthWeight = birthWeight,
    birthHeight = birthHeight,
    bloodType = bloodType,
    extra = extra
)

internal fun BackupBaby.toEntity(): BabyInfo = BabyInfo(
    babyId = 0,
    stableId = stableId,
    name = name,
    gender = gender,
    birthDate = birthDate,
    birthWeight = birthWeight,
    birthHeight = birthHeight,
    bloodType = bloodType,
    extra = extra
)

internal fun FeedingRecord.toBackup(babyStableId: String): BackupFeedingRecord =
    BackupFeedingRecord(
        sourceId = "$babyStableId:feeding:$feedingId",
        babyStableId = babyStableId,
        feedingType = feedingType,
        feedingStart = feedingStart,
        feedingEnd = feedingEnd,
        feedingDuration = feedingDuration,
        feedingDurationBreastLeft = feedingDurationBreastLeft,
        feedingDurationBreastRight = feedingDurationBreastRight,
        note = note,
        createdAt = createdAt,
        feedingAmount = feedingAmount,
        babyMood = babyMood,
        feedingLocation = feedingLocation,
        solidFoodType = solidFoodType,
        foodName = foodName,
        isFirstTime = isFirstTime
    )

internal fun BackupFeedingRecord.toEntity(babyId: Int): FeedingRecord = FeedingRecord(
    babyId = babyId,
    feedingType = feedingType,
    feedingStart = feedingStart,
    feedingEnd = feedingEnd,
    feedingDuration = feedingDuration,
    feedingDurationBreastLeft = feedingDurationBreastLeft,
    feedingDurationBreastRight = feedingDurationBreastRight,
    note = note,
    createdAt = createdAt,
    feedingAmount = feedingAmount,
    babyMood = babyMood,
    feedingLocation = feedingLocation,
    solidFoodType = solidFoodType,
    foodName = foodName,
    isFirstTime = isFirstTime
)

internal fun SleepRecord.toBackup(babyStableId: String): BackupSleepRecord = BackupSleepRecord(
    sourceId = "$babyStableId:sleep:$sleepId",
    babyStableId = babyStableId,
    sleepStart = sleepStart,
    sleepEnd = sleepEnd,
    sleepDuration = sleepDuration,
    note = note,
    createdAt = createdAt
)

internal fun BackupSleepRecord.toEntity(babyId: Int): SleepRecord = SleepRecord(
    babyId = babyId,
    sleepStart = sleepStart,
    sleepEnd = sleepEnd,
    sleepDuration = sleepDuration,
    note = note,
    createdAt = createdAt
)

internal fun EventRecord.toBackup(babyStableId: String): BackupEventRecord = BackupEventRecord(
    sourceId = "$babyStableId:event:$eventId",
    babyStableId = babyStableId,
    type = type,
    time = time,
    endTime = endTime,
    extraData = extraData,
    note = note,
    createdAt = createdAt
)

internal fun BackupEventRecord.toEntity(babyId: Int): EventRecord = EventRecord(
    babyId = babyId,
    type = type,
    time = time,
    endTime = endTime,
    extraData = extraData,
    note = note,
    createdAt = createdAt
)

internal fun ChildDailyRecord.toBackup(babyStableId: String): BackupChildDailyRecord =
    BackupChildDailyRecord(
        sourceId = "$babyStableId:daily:$recordId",
        babyStableId = babyStableId,
        recordDate = recordDate,
        weight = weight,
        height = height,
        headCircumference = headCircumference,
        pic = pic,
        extra = extra
    )

internal fun BackupChildDailyRecord.toEntity(babyId: Int): ChildDailyRecord = ChildDailyRecord(
    babyId = babyId,
    recordDate = recordDate,
    weight = weight,
    height = height,
    headCircumference = headCircumference,
    pic = pic,
    extra = extra
)
