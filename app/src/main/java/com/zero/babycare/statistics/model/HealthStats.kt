package com.zero.babycare.statistics.model

import java.time.LocalDate

/**
 * 健康与疫苗统计
 */
data class HealthStats(
    val startDate: LocalDate,
    val endDate: LocalDate,
    val temperatureCount: Int,
    val medicineCount: Int,
    val doctorCount: Int,
    val symptomCount: Int,
    val vaccineCount: Int,
    val lastVaccineTime: Long?
) {
    val totalHealthCount: Int
        get() = temperatureCount + medicineCount + doctorCount + symptomCount
}
