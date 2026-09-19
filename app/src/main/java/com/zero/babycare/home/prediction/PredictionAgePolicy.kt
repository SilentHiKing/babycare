package com.zero.babycare.home.prediction

import java.util.concurrent.TimeUnit

/** 首页与提醒使用同一月龄约定，避免未知生日被误认为零月龄。 */
object PredictionAgePolicy {
    fun ageMonths(birthDate: Long, now: Long = System.currentTimeMillis()): Int? {
        if (birthDate <= 0L || birthDate > now) return null
        return (TimeUnit.MILLISECONDS.toDays(now - birthDate) / 30).toInt()
    }
}
