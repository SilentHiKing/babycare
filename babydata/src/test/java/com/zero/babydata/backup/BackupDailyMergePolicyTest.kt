package com.zero.babydata.backup

import com.zero.babydata.entity.ChildDailyRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupDailyMergePolicyTest {
    @Test
    fun oldBackupCannotEraseOrReplaceCurrentMeasurements() {
        val current = ChildDailyRecord(1, 2, 1000, 5f, 60f, 40f, "当前照片", "当前备注")
        val old = ChildDailyRecord(99, 2, 1000, 4f, 0f, 0f, "", "")
        assertEquals(current, mergeDailyRecord(current, old))
    }

    @Test
    fun backupFillsOnlyMissingFieldsAndKeepsLocalIdentity() {
        val current = ChildDailyRecord(recordId = 1, babyId = 2, recordDate = 1000, weight = 5f)
        val incoming = ChildDailyRecord(99, 2, 1000, 4f, 60f, 40f, "照片", "备注")
        assertEquals(incoming.copy(recordId = 1, weight = 5f), mergeDailyRecord(current, incoming))
    }

    @Test
    fun conflictingMeasurementsAndPhotosMustRemainSeparate() {
        val current = ChildDailyRecord(weight = 4f, pic = "第一张")
        assertTrue(hasDailyRecordConflict(current, current.copy(weight = 5f)))
        assertTrue(hasDailyRecordConflict(current, current.copy(pic = "第二张")))
        assertFalse(hasDailyRecordConflict(current, current.copy(weight = 0f, height = 50f, pic = "")))
    }
}
