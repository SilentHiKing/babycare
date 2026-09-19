package com.zero.babycare.babyinfo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BirthMeasurementValidationTest {
    @Test
    fun malformedNonFiniteAndNonPositiveInputsCannotBeSaved() {
        listOf("", ".", "NaN", "Infinity", "1e309", "-1", "0").forEach { input ->
            assertNull(input, parsePositiveMeasurement(input) { it.toFloat() })
        }
    }

    @Test
    fun unitConversionOverflowIsRejected() {
        assertNull(parsePositiveMeasurement("1e38") { (it * 1000).toFloat() })
    }

    @Test
    fun validMeasurementRetainsStorageUnitConversion() {
        assertEquals(3250f, parsePositiveMeasurement("3.25") { (it * 1000).toFloat() })
    }
}
