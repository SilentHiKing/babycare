package com.zero.babydata.backup

import com.google.gson.JsonParser
import com.zero.babydata.entity.EventRecord
import com.zero.babydata.entity.FeedingRecord
import com.zero.babydata.entity.SleepRecord
import java.util.TreeMap

/** 按真实时间差去重；创建时间属于写入元数据，不改变同一条照护记录的业务含义。 */
internal class BackupRecordDeduplicator(toleranceMillis: Long) {
    private val tolerance = toleranceMillis.coerceAtLeast(0)
    private val records = mutableMapOf<List<Any?>, TreeMap<Long, MutableList<List<Long>>>>()

    fun remember(record: FeedingRecord) { registerFeeding(record, checkDuplicate = false) }
    fun remember(record: SleepRecord) { registerSleep(record, checkDuplicate = false) }
    fun remember(record: EventRecord) { registerEvent(record, checkDuplicate = false) }

    fun add(record: FeedingRecord): Boolean = registerFeeding(record, checkDuplicate = true)
    fun add(record: SleepRecord): Boolean = registerSleep(record, checkDuplicate = true)
    fun add(record: EventRecord): Boolean = registerEvent(record, checkDuplicate = true)

    private fun registerFeeding(record: FeedingRecord, checkDuplicate: Boolean): Boolean = register(
        key = listOf(
            "feeding", record.babyId, record.feedingType, record.feedingAmount,
            record.babyMood, record.feedingLocation, record.solidFoodType,
            record.foodName.orEmpty(), record.isFirstTime, record.note
        ),
        times = listOf(
            record.feedingStart, record.feedingEnd, record.feedingDuration,
            record.feedingDurationBreastLeft, record.feedingDurationBreastRight
        ),
        checkDuplicate = checkDuplicate
    )

    private fun registerSleep(record: SleepRecord, checkDuplicate: Boolean): Boolean = register(
        key = listOf("sleep", record.babyId, record.note),
        times = listOf(record.sleepStart, record.sleepEnd, record.sleepDuration),
        checkDuplicate = checkDuplicate
    )

    private fun registerEvent(record: EventRecord, checkDuplicate: Boolean): Boolean = register(
        // JsonElement 按对象内容比较，键顺序不应制造重复事件；列表键也避免文案含 # 时碰撞。
        key = listOf("event", record.babyId, record.type, jsonContent(record.extraData), record.note),
        times = listOf(record.time, record.endTime),
        checkDuplicate = checkDuplicate
    )

    private fun register(key: List<Any?>, times: List<Long>, checkDuplicate: Boolean): Boolean {
        val byTime = records.getOrPut(key) { TreeMap() }
        val anchor = times.first()
        val lower = if (anchor < Long.MIN_VALUE + tolerance) Long.MIN_VALUE else anchor - tolerance
        val upper = if (anchor > Long.MAX_VALUE - tolerance) Long.MAX_VALUE else anchor + tolerance
        // 先按开始时间缩小候选范围，长年备份不需要与全部同类记录逐条比较。
        if (checkDuplicate && byTime.subMap(lower, true, upper, true).values.any { candidates ->
                candidates.any { candidate ->
                    times.indices.all { index -> withinTolerance(candidate[index], times[index]) }
                }
            }
        ) return false
        byTime.getOrPut(anchor) { mutableListOf() }.add(times)
        return true
    }

    private fun withinTolerance(first: Long, second: Long): Boolean {
        // 0 表示未记录结束时间/未使用某侧，不能被容差当成一个真实时间或非零时长。
        if (first <= 0L || second <= 0L) return first == second
        return if (first >= second) first - second <= tolerance else second - first <= tolerance
    }

    private fun jsonContent(raw: String): Any = if (raw.isBlank()) "" else {
        runCatching { JsonParser.parseString(raw) }.getOrElse { raw.trim() }
    }
}
