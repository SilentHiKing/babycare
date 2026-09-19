package com.zero.babycare.babies

import com.zero.babycare.navigation.NavTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BabyDeletionNavigationTest {
    @Test
    fun deletingFromEditorReturnsToItsOriginalList() {
        val editor = NavTarget.BabyInfo.edit(7, NavTarget.AllChildren)
        assertEquals(NavTarget.AllChildren, BabyDeletionNavigation.destination(7, editor, editor, true))
    }

    @Test
    fun deletingLastBabyLeavesEditorInCreateMode() {
        val editor = NavTarget.BabyInfo.edit(7)
        assertEquals(NavTarget.BabyInfo.create(), BabyDeletionNavigation.destination(7, editor, editor, false))
    }

    @Test
    fun completionDoesNotReplaceAnotherPageOrAnotherEditingTarget() {
        val editor = NavTarget.BabyInfo.edit(7)
        assertNull(BabyDeletionNavigation.destination(7, editor, NavTarget.Settings(), true))
        assertNull(BabyDeletionNavigation.destination(7, editor, NavTarget.BabyInfo.edit(9), true))
        assertNull(BabyDeletionNavigation.destination(9, editor, editor, true))
    }

    @Test
    fun listDeletionOnlyRefreshesDataWithoutRedirecting() {
        assertNull(BabyDeletionNavigation.destination(7, NavTarget.AllChildren, NavTarget.AllChildren, true))
        assertNull(BabyDeletionNavigation.destination(7, NavTarget.AllChildren, NavTarget.AllChildren, false))
    }
}
