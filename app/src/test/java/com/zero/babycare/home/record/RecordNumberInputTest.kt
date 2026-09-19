package com.zero.babycare.home.record

import com.google.gson.Gson
import com.zero.babydata.entity.GrowthData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordNumberInputTest {
    @Test
    fun unchangedRoundedOuncesKeepOriginalMillilitresAfterDraftSerialization() {
        val original = RecordNumberInput("oz").withOriginal("3.4", 100)
        val restored = Gson().fromJson(Gson().toJson(original), RecordNumberInput::class.java)
        assertEquals("oz", restored.unit)
        assertEquals(100, restored.feedingAmount("3.4"))
        assertEquals(118, restored.feedingAmount("4"))
        assertNull(restored.feedingAmount(""))
    }

    @Test
    fun growthKeepsExactOriginalValueAndUnitUntilInputChanges() {
        val examples = listOf(
            RecordNumberInput("kg").withOriginal("7.2", 7.25, "kg") to GrowthData(7.25, "kg"),
            RecordNumberInput("lb").withOriginal("16", 7250, "g") to GrowthData(7250.0, "g"),
            RecordNumberInput("in").withOriginal("20.2", 512.3, "mm") to GrowthData(512.3, "mm")
        )
        examples.forEach { (input, expected) ->
            val restored = Gson().fromJson(Gson().toJson(input), RecordNumberInput::class.java)
            assertEquals(expected, restored.growth(requireNotNull(input.originalText)))
            assertEquals(GrowthData(9.25, input.unit), restored.growth("9.25"))
            assertNull(restored.growth(""))
        }
    }

    @Test
    fun subMinuteBreastDurationCanBePreservedOrExplicitlyCleared() {
        val restored = Gson().fromJson(
            Gson().toJson(RecordNumberInput("min").withOriginal("0", 30_123L)),
            RecordNumberInput::class.java
        )
        assertEquals(30_123L, restored.durationMillis("0"))
        assertEquals(0L, restored.durationMillis(""))
        assertEquals(60_000L, restored.durationMillis("1"))
        assertNull(RecordNumberInput("kg").growth("NaN"))
        assertNull(RecordNumberInput("kg").growth("Infinity"))
    }
}
