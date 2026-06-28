package com.zero.babycare.home.record.event

import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.zero.babydata.entity.TemperatureData
import com.zero.common.R

/**
 * 体温状态解析器。
 *
 * 页面展示必须复用数据层已有的测量部位阈值，避免额温、耳温、腋温等不同场景
 * 被固定阈值误判；高烧提示继续沿用现有产品阈值，保证原有提醒强度不被放大。
 */
object EventTemperatureStatusResolver {
    private const val LOW_TEMPERATURE_THRESHOLD_CELSIUS = 36.0
    private const val HIGH_FEVER_THRESHOLD_CELSIUS = 38.5

    fun resolve(value: Double, location: String): EventTemperatureStatus {
        val data = TemperatureData(value = value, location = location)
        // 先处理偏低，再复用数据层按测量部位定义的发热阈值，避免低温被归入“正常”。
        if (value < LOW_TEMPERATURE_THRESHOLD_CELSIUS) {
            return EventTemperatureStatus.LowTemperature
        }
        if (!data.isFever()) {
            return EventTemperatureStatus.Normal
        }
        return if (value > HIGH_FEVER_THRESHOLD_CELSIUS) {
            EventTemperatureStatus.HighFever
        } else {
            EventTemperatureStatus.LowFever
        }
    }
}

enum class EventTemperatureStatus(
    @param:StringRes val labelResId: Int,
    @param:ColorRes val colorResId: Int,
    @param:DrawableRes val iconResId: Int,
    @param:StringRes val warningResId: Int?
) {
    LowTemperature(
        labelResId = R.string.temperature_low,
        colorResId = R.color.temp_low,
        iconResId = R.drawable.ic_info_circle,
        warningResId = R.string.temperature_low_warning
    ),
    Normal(
        labelResId = R.string.temperature_normal,
        colorResId = R.color.temp_normal,
        iconResId = R.drawable.ic_check,
        warningResId = null
    ),
    LowFever(
        labelResId = R.string.temperature_low_fever,
        colorResId = R.color.temp_warning,
        iconResId = R.drawable.ic_info_circle,
        warningResId = R.string.temperature_warning
    ),
    HighFever(
        labelResId = R.string.temperature_high_fever,
        colorResId = R.color.temp_danger,
        iconResId = R.drawable.ic_alert_triangle,
        warningResId = R.string.temperature_danger
    )
}
