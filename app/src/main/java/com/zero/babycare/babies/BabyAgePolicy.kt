package com.zero.babycare.babies

import com.zero.babydata.entity.BabyInfo
import com.zero.common.R
import com.zero.common.util.BabyGender
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** 年龄按本地日历日期计算，与统计页一致；一天不总是 24 小时。 */
internal object BabyAgePolicy {
    fun days(birthDateMillis: Long, today: LocalDate, zone: ZoneId): Long? =
        birthDateMillis.takeIf { it > 0 }?.let {
            ChronoUnit.DAYS.between(Instant.ofEpochMilli(it).atZone(zone).toLocalDate(), today)
        }
}

/** 仅携带渲染参数；列表和侧栏不再自行计算年龄或把缺少生日解释为零天。 */
data class BabyCardUiModel(
    val baby: BabyInfo,
    val gender: String,
    val ageDays: Long?,
    val ageTextRes: Int,
    val ageFullTextRes: Int,
    val birthDateText: String?,
    val selected: Boolean
)

internal data class BabyAgeReference(val date: LocalDate, val zone: ZoneId) {
    companion object {
        fun now(): BabyAgeReference = ZoneId.systemDefault().let { BabyAgeReference(LocalDate.now(it), it) }
    }
}

internal object BabyCardUiMapper {
    fun map(baby: BabyInfo, selectedBabyId: Int?, reference: BabyAgeReference): BabyCardUiModel {
        val days = BabyAgePolicy.days(baby.birthDate, reference.date, reference.zone)
        val fallback = when {
            days == null -> R.string.baby_age_birthdate_missing
            days < 0 -> R.string.baby_age_not_born_yet
            else -> null
        }
        return BabyCardUiModel(
            baby = baby,
            gender = BabyGender.normalize(baby.gender),
            ageDays = days?.takeIf { it >= 0 },
            ageTextRes = fallback ?: R.string.baby_age_days_format,
            ageFullTextRes = fallback ?: R.string.days_born,
            birthDateText = baby.birthDate.takeIf { it > 0 }?.let {
                Instant.ofEpochMilli(it).atZone(reference.zone).toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
            },
            selected = baby.babyId == selectedBabyId
        )
    }
}
