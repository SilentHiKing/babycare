package com.zero.babycare.statistics.standard

import android.content.Context
import com.google.gson.Gson
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * WHO 生长标准数据提供器
 * - 读取本地 assets 中的 LMS 数据
 * - 根据年龄与测量值计算百分位
 */
class WhoGrowthStandardProvider(private val context: Context) {

    private val gson = Gson()
    private val cache = mutableMapOf<String, WhoLmsTable>()

    /**
     * 获取 LMS 表
     */
    fun getTable(indicator: WhoIndicator, sex: WhoSex): WhoLmsTable? {
        if (sex == WhoSex.UNKNOWN) return null
        val assetName = getAssetName(indicator, sex)
        val cached = cache[assetName]
        if (cached != null) return cached
        val table = readTableFromAssets(assetName) ?: return null
        cache[assetName] = table
        return table
    }

    /**
     * 计算百分位
     */
    fun computePercentile(
        indicator: WhoIndicator,
        sex: WhoSex,
        ageDays: Int,
        ageMonths: Double,
        value: Double
    ): Int? {
        val table = getTable(indicator, sex) ?: return null
        val age = when (table.unit) {
            AgeUnit.DAY -> ageDays.toDouble()
            AgeUnit.MONTH -> ageMonths
        }
        val minAge = table.rows.firstOrNull()?.age ?: return null
        val maxAge = table.rows.lastOrNull()?.age ?: return null
        if (age < minAge || age > maxAge) return null
        val lms = interpolateLms(table.rows, age) ?: return null
        val z = computeZScore(value, lms)
        val percentile = (normalCdf(z) * 100f).roundToInt()
        return percentile.coerceIn(1, 99)
    }

    private fun getAssetName(indicator: WhoIndicator, sex: WhoSex): String {
        val prefix = when (indicator) {
            WhoIndicator.WEIGHT -> "wfa"
            WhoIndicator.LENGTH_HEIGHT -> "lhfa"
            WhoIndicator.HEAD -> "hcfa"
        }
        val suffix = if (sex == WhoSex.BOY) "boys" else "girls"
        return "who_growth/${prefix}_${suffix}.json"
    }

    private fun readTableFromAssets(assetName: String): WhoLmsTable? {
        return try {
            context.assets.open(assetName).use { input ->
                val content = input.bufferedReader().use { it.readText() }
                val json = gson.fromJson(content, WhoLmsTableJson::class.java)
                val unit = if (json.unit == "day") AgeUnit.DAY else AgeUnit.MONTH
                WhoLmsTable(unit = unit, rows = json.rows)
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 线性插值 LMS 参数
     */
    private fun interpolateLms(rows: List<WhoLmsRow>, age: Double): WhoLmsRow? {
        if (rows.isEmpty()) return null
        if (age <= rows.first().age) return rows.first()
        if (age >= rows.last().age) return rows.last()

        var low = 0
        var high = rows.size - 1
        while (low <= high) {
            val mid = (low + high) / 2
            val midAge = rows[mid].age
            if (midAge == age) return rows[mid]
            if (midAge < age) {
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        val index = low.coerceIn(1, rows.size - 1)
        val left = rows[index - 1]
        val right = rows[index]
        val ratio = if (right.age == left.age) 0.0 else (age - left.age) / (right.age - left.age)
        return WhoLmsRow(
            age = age,
            l = left.l + (right.l - left.l) * ratio,
            m = left.m + (right.m - left.m) * ratio,
            s = left.s + (right.s - left.s) * ratio
        )
    }

    /**
     * LMS 转 z 分数
     */
    private fun computeZScore(value: Double, lms: WhoLmsRow): Double {
        return if (abs(lms.l) < 1e-6) {
            ln(value / lms.m) / lms.s
        } else {
            ((value / lms.m).pow(lms.l) - 1) / (lms.l * lms.s)
        }
    }

    /**
     * 正态分布 CDF 近似
     * 采用 Abramowitz & Stegun 7.1.26 近似公式
     */
    private fun normalCdf(z: Double): Double {
        val t = 1.0 / (1.0 + 0.2316419 * abs(z))
        val d = 0.3989423 * exp(-z * z / 2.0)
        val prob = d * t * (
            0.3193815 + t * (-0.3565638 + t * (1.781478 + t * (-1.821256 + t * 1.330274)))
            )
        return if (z > 0) 1 - prob else prob
    }
}

/**
 * WHO 指标类型
 */
enum class WhoIndicator {
    WEIGHT,
    LENGTH_HEIGHT,
    HEAD
}

/**
 * WHO 性别枚举
 */
enum class WhoSex {
    BOY,
    GIRL,
    UNKNOWN
}

/**
 * 年龄单位
 */
enum class AgeUnit {
    DAY,
    MONTH
}

/**
 * LMS 行数据
 */
data class WhoLmsRow(
    val age: Double,
    val l: Double,
    val m: Double,
    val s: Double
)

/**
 * LMS 表
 */
data class WhoLmsTable(
    val unit: AgeUnit,
    val rows: List<WhoLmsRow>
)

private data class WhoLmsTableJson(
    val unit: String,
    val rows: List<WhoLmsRow>
)
