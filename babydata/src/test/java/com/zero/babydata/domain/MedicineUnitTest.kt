package com.zero.babydata.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MedicineUnitTest {
    @Test
    fun knownLabelsFromAllFiveLanguagesResolveToStableCodes() {
        mapOf(
            "ml" to listOf("ml", "毫升", "ミリリットル", "밀리리터"),
            "mg" to listOf("mg", "毫克", "ミリグラム", "밀리그램"),
            "drop" to listOf("滴", "drop", "방울"),
            "tablet" to listOf("片", "tablet", "錠", "정")
        ).forEach { (code, aliases) ->
            aliases.forEach { assertEquals(code, MedicineUnit.canonicalValue(it)) }
        }
    }

    @Test
    fun unknownAndMissingUnitsAreNotGuessed() {
        assertEquals("spray", MedicineUnit.canonicalValue("spray"))
        assertEquals("  自定义单位  ", MedicineUnit.canonicalValue("  自定义单位  "))
        assertNull(MedicineUnit.canonicalValue(null))
        assertEquals("", MedicineUnit.canonicalValue(""))
    }
}
