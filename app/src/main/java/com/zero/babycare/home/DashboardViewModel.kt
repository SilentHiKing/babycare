package com.zero.babycare.home

import com.blankj.utilcode.util.StringUtils
import com.zero.babycare.home.bean.DashboardEntity
import com.zero.babycare.home.bean.DashboardEntity.Companion.TYPE_INFO
import com.zero.babycare.home.bean.DashboardEntity.Companion.TYPE_NEXT
import com.zero.babycare.home.bean.DashboardEntity.Companion.TYPE_TITLE
import com.zero.babydata.domain.BabyDataHelper.repository
import com.zero.babydata.entity.BabyInfo
import com.zero.babydata.entity.FeedingRecord
import com.zero.babydata.entity.SleepRecord
import com.zero.common.ext.msToSmartMinutes
import com.zero.common.util.DateUtils
import com.zero.common.util.DateUtils.getTimeOfDayMillis
import com.zero.components.base.vm.BaseViewModel
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong


class DashboardViewModel() : BaseViewModel() {

    fun insert(babyInfo: BabyInfo, callback: Runnable) {
        safeLaunch {
            repository.insertBabyInfo(babyInfo, callback)
        }
    }

    fun request(babyId: Int?, date: Date) {
        safeLaunch {
            if (babyId == null) {
                return@safeLaunch emptyList()
            }
            val (startOfDay, endOfDay) = DateUtils.getDayRange(date)


            // 2️⃣ 查询数据库
            val feedings = repository.getFeedingRecordsForDay(babyId, startOfDay, endOfDay)
            val sleeps = repository.getSleepRecordsForDay(babyId, startOfDay, endOfDay)

            // 3️⃣ 喂养统计
            val feedingCount = feedings.size
            val totalFeedingDurationL = if (feedingCount > 0) {
                feedings.sumOf { it.feedingEnd - it.feedingStart }
            } else 0L
            val totalFeedingDuration = totalFeedingDurationL.msToSmartMinutes()
            val avgFeedingDuration = (if (feedingCount == 0) 0 else totalFeedingDurationL / feedingCount).msToSmartMinutes()
            val feedingPair = getDayNightFeedingDuration(feedings)

            // 4️⃣ 睡眠统计
            val sleepCount = sleeps.size
            val totalSleepDurationL = if (sleepCount > 0) {
                sleeps.sumOf { it.sleepEnd - it.sleepStart }
            } else 0L


            val totalSleepDuration = TimeUnit.MILLISECONDS.toMinutes(totalSleepDurationL).toDouble()
            val avgSleepDuration = if (sleepCount == 0) 0 else
                TimeUnit.MILLISECONDS.toMinutes(totalSleepDurationL / sleepCount).toDouble()
            val sleepPair = getDayNightSleepDuration(sleeps)

            mutableListOf<DashboardEntity>().apply {

                add(DashboardEntity(TYPE_TITLE).apply {
                    title = StringUtils.getString(com.zero.common.R.string.today)
                })
                add(DashboardEntity(TYPE_INFO).apply {
                    title = StringUtils.getString(com.zero.common.R.string.feeding)
                    content = String.format(
                        StringUtils.getString(com.zero.common.R.string.countAndTotalTime),
                        feedingCount,
                        totalFeedingDuration,
                        feedingPair.first,
                        feedingPair.second
                    )
                    desc = String.format(
                        StringUtils.getString(com.zero.common.R.string.avgTime),
                        avgFeedingDuration
                    )
                })
                add(DashboardEntity(TYPE_INFO).apply {
                    title = StringUtils.getString(com.zero.common.R.string.sleeping)
                    content = String.format(
                        StringUtils.getString(com.zero.common.R.string.countAndTotalTime),
                        sleepCount,
                        totalSleepDuration,
                        sleepPair.first,
                        sleepPair.second
                    )
                    desc = String.format(
                        StringUtils.getString(com.zero.common.R.string.avgTime),
                        avgSleepDuration
                    )
                })

                add(DashboardEntity(TYPE_TITLE).apply {
                    title = StringUtils.getString(com.zero.common.R.string.next)
                })
                add(DashboardEntity(TYPE_NEXT).apply {
                    title = StringUtils.getString(com.zero.common.R.string.feeding)
                    content = date2String("", predictNextFeedingTime(babyId)) + "${
                        "%.1f".format(predictNextFeedingDuration(babyId))
                    } ${com.zero.common.R.string.min}"
                    desc = String.format(
                        StringUtils.getString(com.zero.common.R.string.preGap),
                        getTimeSinceLastFeeding(babyId)
                    )
                })

                add(DashboardEntity(TYPE_NEXT).apply {
                    title = StringUtils.getString(com.zero.common.R.string.sleeping)
                    content = date2String("", predictNextSleepTime(babyId)) + "${
                        "%.1f".format(predictNextSleepDuration(babyId))
                    } ${com.zero.common.R.string.min}"
                    desc = String.format(
                        StringUtils.getString(com.zero.common.R.string.preGap),
                        getTimeSinceLastSleep(babyId)
                    )
                })
            }

        }
    }


    // ✅ 1. 上次喂奶距现在多久（分钟）
    fun getTimeSinceLastFeeding(babyId: Int): Long? {
        val lastFeeding = repository.getLastFeedingRecord(babyId)
        return lastFeeding?.let {
            val diff = Date().time - it.feedingEnd
            TimeUnit.MILLISECONDS.toMinutes(diff)
        }
    }

    // ✅ 2. 上次睡觉距现在多久（分钟）
    fun getTimeSinceLastSleep(babyId: Int): Long? {
        val lastSleep = repository.getLastSleepRecord(babyId)
        return lastSleep?.let {
            val diff = Date().time - it.sleepEnd
            TimeUnit.MILLISECONDS.toMinutes(diff)
        }
    }

    // ✅ 3. 白天 vs 夜间 睡眠时长
    fun getDayNightSleepDuration(sleeps: List<SleepRecord>): Pair<Double, Double> {
        val dayStart = getTimeOfDayMillis(6, 0)   // 早上6点
        val nightStart = getTimeOfDayMillis(18, 0) // 晚上6点

        var daySleep = 0L
        var nightSleep = 0L

        for (s in sleeps) {
            val start = s.sleepStart % TimeUnit.DAYS.toMillis(1)
            val end = s.sleepEnd % TimeUnit.DAYS.toMillis(1)
            val duration = s.sleepEnd - s.sleepStart

            // 若在 6:00 ~ 18:00 为白天，否则为夜间
            if (start in dayStart..nightStart) daySleep += duration else nightSleep += duration
        }

        return Pair(
            TimeUnit.MILLISECONDS.toMinutes(daySleep).toDouble(),
            TimeUnit.MILLISECONDS.toMinutes(nightSleep).toDouble()
        )
    }

    // ✅ 4. 白天 vs 夜间 喂奶时长
    fun getDayNightFeedingDuration(feedings: List<FeedingRecord>): Pair<String, String> {
        val dayStart = getTimeOfDayMillis(6, 0)
        val nightStart = getTimeOfDayMillis(18, 0)

        var dayFeed = 0L
        var nightFeed = 0L

        for (f in feedings) {
            val start = f.feedingStart % TimeUnit.DAYS.toMillis(1)
            val duration = f.feedingEnd - f.feedingStart
            if (start in dayStart..nightStart) dayFeed += duration else nightFeed += duration
        }

        return Pair(
            dayFeed.msToSmartMinutes(),
            nightFeed.msToSmartMinutes()

        )
    }

    /**
     * 预测下一次喂奶时间
     */
    fun predictNextFeedingTime(babyId: Int, recentCount: Int = 8): Date? {
        val records = repository.getRecentFeedings(babyId, recentCount)
        if (records.size < 2) return null

        val (dayIntervals, nightIntervals) = splitIntervalsByPeriod(records.map {
            FeedingLikeRecord(
                it.feedingStart,
                it.feedingEnd
            )
        })

        // 当前时间段判断
        val nowHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val avgInterval = if (nowHour in 6..18)
            weightedAverage(dayIntervals).takeIf { it > 0 } ?: weightedAverage(nightIntervals)
        else
            weightedAverage(nightIntervals).takeIf { it > 0 } ?: weightedAverage(dayIntervals)

        val lastEnd = records.last().feedingEnd
        return Date(lastEnd + avgInterval)
    }

    fun date2String(info: String, date: Date?): String {
        val format = java.text.SimpleDateFormat("HH:mm", Locale.getDefault())
        return "${
            date?.let {
                "${info}:${format.format(it)}"
            } ?: StringUtils.getString(com.zero.common.R.string.cannotPredict)
        }".trimIndent()
    }

    /**
     * 预测下一次睡觉时间
     */
    fun predictNextSleepTime(babyId: Int, recentCount: Int = 8): Date? {
        val records = repository.getRecentSleeps(babyId, recentCount)
        if (records.size < 2) return null

        val (dayIntervals, nightIntervals) = splitIntervalsByPeriod(records.map {
            FeedingLikeRecord(it.sleepStart, it.sleepEnd)
        })

        val nowHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val avgInterval = if (nowHour in 6..18)
            weightedAverage(dayIntervals).takeIf { it > 0 } ?: weightedAverage(nightIntervals)
        else
            weightedAverage(nightIntervals).takeIf { it > 0 } ?: weightedAverage(dayIntervals)

        val lastEnd = records.last().sleepEnd
        return Date(lastEnd + avgInterval)
    }


    /** ✅ 新增：预测下次喂奶时长（分钟） */
    fun predictNextFeedingDuration(babyId: Int, recentCount: Int = 8): Double {
        val records = repository.getRecentFeedings(babyId, recentCount)
        if (records.isEmpty()) return 0.0

        val durations = records.map { it.feedingEnd - it.feedingStart }
            .filter { it in TimeUnit.MINUTES.toMillis(5)..TimeUnit.HOURS.toMillis(2) } // 过滤异常
        val avg = weightedAverage(durations)
        return TimeUnit.MILLISECONDS.toMinutes(avg).toDouble()
    }

    /** ✅ 新增：预测下次睡眠时长（分钟） */
    fun predictNextSleepDuration(babyId: Int, recentCount: Int = 8): Double {
        val records = repository.getRecentSleeps(babyId, recentCount)
        if (records.isEmpty()) return 0.0

        val durations = records.map { it.sleepEnd - it.sleepStart }
            .filter { it in TimeUnit.MINUTES.toMillis(20)..TimeUnit.HOURS.toMillis(12) } // 过滤异常
        val avg = weightedAverage(durations)
        return TimeUnit.MILLISECONDS.toMinutes(avg).toDouble()
    }

    /**
     * 根据时间段拆分间隔（白天 vs 夜间）
     */
    private fun splitIntervalsByPeriod(records: List<FeedingLikeRecord>): Pair<List<Long>, List<Long>> {
        val dayIntervals = mutableListOf<Long>()
        val nightIntervals = mutableListOf<Long>()

        for (i in 0 until records.size - 1) {
            val curr = records[i]
            val next = records[i + 1]
            val interval = next.start - curr.end
            if (interval <= 0) continue

            val hour = Calendar.getInstance().apply {
                timeInMillis = curr.start
            }.get(Calendar.HOUR_OF_DAY)

            if (hour in 6..18) dayIntervals += interval else nightIntervals += interval
        }

        return Pair(dayIntervals, nightIntervals)
    }

    /**
     * 最近几次加权平均（最近权重更高）
     */
    private fun weightedAverage(values: List<Long>): Long {
        if (values.isEmpty()) return 0
        val weights = (values.size downTo 1).toList()
        val totalWeight = weights.sum()
        val weightedSum = values.zip(weights).sumOf { (v, w) -> v * w }
        return (weightedSum / totalWeight.toDouble()).roundToLong()
    }

    /**
     * 内部辅助类，统一处理喂奶/睡眠记录的时间
     */
    private data class FeedingLikeRecord(
        val start: Long,
        val end: Long
    )


}