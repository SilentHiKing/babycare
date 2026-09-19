package com.zero.babycare.home.prediction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.TimeUnit

class PredictionAgePolicyTest {
    @Test
    fun `missing or future birthdays remain unknown instead of newborn`() {
        val now = TimeUnit.DAYS.toMillis(100)
        assertNull(PredictionAgePolicy.ageMonths(0, now))
        assertNull(PredictionAgePolicy.ageMonths(-1, now))
        assertNull(PredictionAgePolicy.ageMonths(now + 1, now))
        assertEquals(0, PredictionAgePolicy.ageMonths(now, now))
        assertEquals(2, PredictionAgePolicy.ageMonths(now - TimeUnit.DAYS.toMillis(65), now))
    }
}
