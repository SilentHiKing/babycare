package com.zero.babycare.reminder

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderDueHandlerTest {

    @Test
    fun `blocked notifications finish without recreating an already due job`() = runBlocking {
        var reschedules = 0
        val retry = ReminderDueHandler.run(
            deliver = { ReminderDeliveryResult.SUPPRESSED },
            reschedule = { reschedules++ }
        )
        assertFalse(retry)
        assertEquals(0, reschedules)
    }

    @Test
    fun `transient delivery failure uses worker backoff without immediate planning`() = runBlocking {
        var reschedules = 0
        val retry = ReminderDueHandler.run(
            deliver = { ReminderDeliveryResult.RETRY },
            reschedule = { reschedules++ }
        )
        assertTrue(retry)
        assertEquals(0, reschedules)
    }

    @Test
    fun `changed due time requests one new plan`() = runBlocking {
        var reschedules = 0
        val retry = ReminderDueHandler.run(
            deliver = { ReminderDeliveryResult.RESCHEDULE },
            reschedule = { reschedules++ }
        )
        assertFalse(retry)
        assertEquals(1, reschedules)
    }

    @Test
    fun `successful delivery leaves other already scheduled reminders alone`() = runBlocking {
        var reschedules = 0
        assertFalse(ReminderDueHandler.run({ ReminderDeliveryResult.DELIVERED }, { reschedules++ }))
        assertEquals(0, reschedules)
    }
}
