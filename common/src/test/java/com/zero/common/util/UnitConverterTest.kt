package com.zero.common.util

import org.junit.Assert.assertEquals
import org.junit.Test

class UnitConverterTest {

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
