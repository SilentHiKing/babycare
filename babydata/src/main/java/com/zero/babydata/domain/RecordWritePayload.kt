package com.zero.babydata.domain

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.zero.babydata.entity.EventRecord
import com.zero.babydata.entity.BabyInfo
import com.zero.babydata.entity.FeedingRecord
import com.zero.babydata.entity.SleepRecord
import java.security.MessageDigest
import java.util.UUID

/** 固定字段顺序的业务摘要，不依赖反射字段名，避免混淆或创建时间变化破坏跨进程重试。 */
internal object RecordWritePayload {
    const val FEEDING = 1
    const val SLEEP = 2
    const val EVENT = 3
    private val gson = Gson()

    fun requestId(raw: String, babyId: Int): String {
        if (babyId <= 0) throw RecordWriteException(RecordWriteFailure.INVALID_REQUEST)
        val normalized = runCatching { UUID.fromString(raw).toString() }.getOrNull()
        if (normalized == null || !normalized.equals(raw, ignoreCase = true)) {
            throw RecordWriteException(RecordWriteFailure.INVALID_REQUEST)
        }
        return normalized
    }

    fun feeding(record: FeedingRecord): String = fingerprint(listOf(
        record.feedingType, record.feedingStart, record.feedingEnd, record.feedingDuration,
        record.feedingDurationBreastLeft, record.feedingDurationBreastRight, record.note,
        record.feedingAmount, record.babyMood, record.feedingLocation, record.solidFoodType,
        record.foodName, record.isFirstTime, record.timerData
    ))

    fun baby(record: BabyInfo): String = fingerprint(listOf(
        record.name, record.gender, record.birthDate, record.birthWeight, record.birthHeight,
        record.bloodType, record.extra
    ))

    fun sleep(record: SleepRecord): String = fingerprint(listOf(
        record.sleepStart, record.sleepEnd, record.sleepDuration, record.note, record.timerData
    ))

    fun event(record: EventRecord): String {
        val extra = if (record.extraData.isBlank()) null else runCatching {
            canonical(JsonParser.parseString(record.extraData))
        }.getOrElse { throw RecordWriteException(RecordWriteFailure.INVALID_REQUEST) }
        return fingerprint(listOf(record.type, record.time, record.endTime, extra, record.note, record.activeDurationMs, record.timerData))
    }

    private fun fingerprint(values: List<Any?>): String {
        val bytes = canonical(gson.toJsonTree(values)).toByteArray(Charsets.UTF_8)
        // 前缀固定摘要协议版本；未来业务字段变化时必须显式兼容已有回执。
        return "v1:" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    /** JSON 对象键顺序和数值写法不改变含义；数组顺序、字符串和 null 仍严格区分。 */
    private fun canonical(element: JsonElement): String = when {
        element.isJsonNull -> "null"
        element.isJsonObject -> element.asJsonObject.entrySet().sortedBy { it.key }
            .joinToString(prefix = "{", postfix = "}") { (key, value) ->
                gson.toJson(key) + ":" + canonical(value)
            }
        element.isJsonArray -> element.asJsonArray.joinToString(prefix = "[", postfix = "]") { canonical(it) }
        element.asJsonPrimitive.isNumber -> element.asBigDecimal.stripTrailingZeros().toString()
        else -> element.toString()
    }
}
