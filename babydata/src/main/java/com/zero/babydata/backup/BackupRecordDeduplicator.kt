package com.zero.babydata.backup

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.zero.babydata.entity.EventRecord
import com.zero.babydata.entity.EventType
import com.zero.babydata.domain.MedicineUnit
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
        // 精确结构键忽略对象键顺序，保留原始数值有效位；列表键也避免文案含 # 时碰撞。
        key = listOf("event", record.babyId, record.type, jsonContent(record.extraData, record.type), record.note),
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

    private fun jsonContent(raw: String, type: Int): Any = if (raw.isBlank()) "" else {
        runCatching {
            // 老版本已经落库的深层/超长扩展也不能在合并哈希时递归溢出；异常原文按字符串比较。
            BackupJsonLimits.validate(raw, maxValues = BackupJsonLimits.MAX_EXTRA_VALUES)
            val json = JsonParser.parseString(raw).also { json ->
                if (type == EventType.HEALTH_MEDICINE && json.isJsonObject) {
                    val unit = json.asJsonObject.get("unit")
                    if (unit?.isJsonPrimitive == true && unit.asJsonPrimitive.isString) {
                        // 只规范比较副本；升级后的稳定代码与旧备份语言标签代表同一单位。
                        MedicineUnit.fromStored(unit.asString)?.let { json.asJsonObject.addProperty("unit", it.code) }
                    }
                }
            }
            ParsedJsonContent(exactJsonValue(json))
        }.getOrElse { raw.trim() }
    }

    /** 解析成功与异常原文分开标记，JSON 字符串不能与空内容或旧损坏原文误判相同。 */
    private data class ParsedJsonContent(val value: Any?)

    /**
     * JsonPrimitive 的数字相等/哈希会经过 Double，足以吞掉合法的大整数与高精度小数。
     * 已经限制深度和节点数后再转结构键；BigDecimal 只去无意义尾零，不改实体里的原文。
     */
    private fun exactJsonValue(json: JsonElement): Any? = when {
        json.isJsonNull -> null
        json.isJsonObject -> json.asJsonObject.entrySet().associate { (key, value) -> key to exactJsonValue(value) }
        json.isJsonArray -> json.asJsonArray.map(::exactJsonValue)
        json.asJsonPrimitive.isNumber -> json.asBigDecimal.stripTrailingZeros()
        json.asJsonPrimitive.isBoolean -> json.asBoolean
        else -> json.asString
    }
}
