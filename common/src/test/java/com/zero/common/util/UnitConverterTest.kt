package com.zero.common.util

import org.junit.Assert.assertEquals
import org.junit.Test

class UnitConverterTest {

    @Test
    fun `all supported growth weight units normalize to the same kilograms`() {
        val measurements = listOf("kg" to 0.45359237, "g" to 453.59237, "lb" to 1.0, "oz" to 16.0)
        measurements.forEach { (unit, value) ->
            assertEquals(0.45359237, UnitConverter.weightToDisplay(value, unit, "kg"), 0.00000001)
            assertEquals(1.0, UnitConverter.weightToDisplay(value, unit, "lb"), 0.00000001)
        }
    }

    @Test
    fun `growth weight conversion round trips grams and mass ounces`() {
        assertEquals(3500.0, UnitConverter.weightToDisplay(3.5, "kg", "g"), 0.000001)
        val ounces = UnitConverter.weightToDisplay(3.5, "kg", "oz")
        assertEquals(3.5, UnitConverter.weightToDisplay(ounces, "oz", "kg"), 0.000001)
    }

    @Test
    fun `all supported length units normalize to the same centimeters`() {
        val measurements = listOf("cm" to 50.8, "mm" to 508.0, "in" to 20.0)
        measurements.forEach { (unit, value) ->
            assertEquals(50.8, UnitConverter.heightToDisplay(value, unit, "cm"), 0.000001)
            assertEquals(20.0, UnitConverter.heightToDisplay(value, unit, "in"), 0.000001)
            assertEquals(508.0, UnitConverter.heightToDisplay(value, unit, "mm"), 0.000001)
        }
    }

    @Test
    fun `birth weight keeps gram storage while displaying kg or lb`() {
        val storageGrams = 3500.0

        assertEquals(
            3.5,
            UnitConverter.birthWeightToDisplay(storageGrams, UnitConfig.WEIGHT_UNIT_KG),
            0.0001
        )
        assertEquals(
            7.71617,
            UnitConverter.birthWeightToDisplay(storageGrams, UnitConfig.WEIGHT_UNIT_LB),
            0.0001
        )
    }

    @Test
    fun `birth weight input converts back to gram storage`() {
        assertEquals(
            3500f,
            UnitConverter.birthWeightToStorageGrams(3.5, UnitConfig.WEIGHT_UNIT_KG),
            0.001f
        )
        assertEquals(
            3500f,
            UnitConverter.birthWeightToStorageGrams(7.71617, UnitConfig.WEIGHT_UNIT_LB),
            0.01f
        )
    }

    @Test
    fun `input decimal keeps useful precision without trailing zeros`() {
        assertEquals("3.25", UnitConverter.formatInputDecimal(3.25))
        assertEquals("50", UnitConverter.formatInputDecimal(50.0))
    }
}
