package com.zero.babycare.babies

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CurrentBabySelectionTest {
    @Test
    fun legacySelectionSurvivesUpgradeWhenNewSelectionIsMissingOrInvalid() {
        assertEquals(7, CurrentBabySelection.resolveId(listOf(9, 7), null, null, "{\"babyId\":7}"))
        assertEquals(7, CurrentBabySelection.resolveId(listOf(9, 7), null, 99, "{\"babyId\":7}"))
    }

    @Test
    fun newerStoredSelectionIsNotOverriddenByStaleLegacyCache() {
        assertEquals(9, CurrentBabySelection.resolveId(listOf(9, 7), null, 9, "{\"babyId\":7}"))
    }

    @Test
    fun explicitCurrentSelectionWinsAndInvalidLegacyIdsAreIgnored() {
        assertEquals(9, CurrentBabySelection.resolveId(listOf(9, 7), 9, 9, "{\"babyId\":7}"))
        listOf("broken", "null", "{}", "{\"babyId\":99}", "{\"babyId\":-1}").forEach { json ->
            assertEquals(7, CurrentBabySelection.resolveId(listOf(9, 7), null, 7, json))
        }
    }

    @Test
    fun missingSelectionFallsBackOnlyToAnExistingBaby() {
        assertEquals(9, CurrentBabySelection.resolveId(listOf(9, 7), 99, 99, null))
        assertNull(CurrentBabySelection.resolveId(emptyList(), null, 7, "{\"babyId\":7}"))
    }
}
