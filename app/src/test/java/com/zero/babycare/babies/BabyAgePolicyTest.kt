package com.zero.babycare.babies

import com.zero.babydata.entity.BabyInfo
import com.zero.common.R
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BabyAgePolicyTest {
    private val shanghai = ZoneId.of("Asia/Shanghai")

    @Test
    fun midnightAdvancesAgeEvenBeforeTwentyFourHours() {
        val birth = LocalDate.of(2026, 9, 19).atTime(23, 55).atZone(shanghai).toInstant().toEpochMilli()
        assertEquals(1L, BabyAgePolicy.days(birth, LocalDate.of(2026, 9, 20), shanghai))
    }

    @Test
    fun daylightSavingDayStillCountsAsOneCalendarDay() {
        val zone = ZoneId.of("America/New_York")
        val date = LocalDate.of(2026, 3, 8)
        val birth = date.atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(23L, (date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - birth) / 3_600_000)
        assertEquals(1L, BabyAgePolicy.days(birth, date.plusDays(1), zone))
    }

    @Test
    fun sameCalendarDayStartsAtZeroAndUsesTheProvidedZone() {
        val birth = LocalDate.of(2026, 9, 20).atTime(1, 0).atZone(shanghai).toInstant().toEpochMilli()
        val today = LocalDate.of(2026, 9, 20)
        assertEquals(0L, BabyAgePolicy.days(birth, today, shanghai))
        assertEquals(1L, BabyAgePolicy.days(birth, today, ZoneId.of("UTC")))
    }

    @Test
    fun absentAndFutureBirthdaysNeverBecomeZeroOrNegativeAgeLabels() {
        val today = LocalDate.of(2026, 9, 20)
        val reference = BabyAgeReference(today, shanghai)
        val missing = BabyCardUiMapper.map(BabyInfo(babyId = 1), 2, reference)
        assertNull(missing.ageDays)
        assertNull(missing.birthDateText)
        assertEquals(R.string.baby_age_birthdate_missing, missing.ageTextRes)
        assertEquals(R.string.baby_age_birthdate_missing, missing.ageFullTextRes)
        assertFalse(missing.selected)

        val birth = today.plusDays(1).atStartOfDay(shanghai).toInstant().toEpochMilli()
        val future = BabyCardUiMapper.map(BabyInfo(babyId = 2, birthDate = birth), 2, reference)
        assertNull(future.ageDays)
        assertEquals("2026-09-21", future.birthDateText)
        assertEquals(R.string.baby_age_not_born_yet, future.ageTextRes)
        assertEquals(R.string.baby_age_not_born_yet, future.ageFullTextRes)
        assertTrue(future.selected)
    }

    @Test
    fun validBirthdayProvidesTheSameAgeToListAndDrawer() {
        val date = LocalDate.of(2026, 9, 20)
        val birth = date.minusDays(31).atTime(18, 0).atZone(shanghai).toInstant().toEpochMilli()
        val model = BabyCardUiMapper.map(BabyInfo(birthDate = birth), null, BabyAgeReference(date, shanghai))
        assertEquals(31L, model.ageDays)
        assertEquals(R.string.baby_age_days_format, model.ageTextRes)
        assertEquals(R.string.days_born, model.ageFullTextRes)
    }
}
