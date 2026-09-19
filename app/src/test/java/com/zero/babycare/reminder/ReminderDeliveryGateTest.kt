package com.zero.babycare.reminder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReminderDeliveryGateTest {
    @Test
    fun `record invalidation rejects old candidate but preserves other babies`() {
        val gate = ReminderDeliveryGate()
        val first = gate.capture(1, ReminderType.FEEDING)
        val second = gate.capture(2, ReminderType.FEEDING)
        gate.invalidate(1) {}
        assertNull(gate.ifCurrent(first) { "旧通知" })
        assertEquals("另一宝宝", gate.ifCurrent(second) { "另一宝宝" })
        assertEquals("新候选", gate.ifCurrent(gate.capture(1, ReminderType.FEEDING)) { "新候选" })
    }

    @Test
    fun `starting one timer does not invalidate the other reminder type`() {
        val gate = ReminderDeliveryGate()
        val feeding = gate.capture(1, ReminderType.FEEDING)
        val sleep = gate.capture(1, ReminderType.SLEEP)
        gate.invalidate(1, ReminderType.FEEDING) {}
        assertNull(gate.ifCurrent(feeding) { true })
        assertEquals(true, gate.ifCurrent(sleep) { true })
    }

    @Test
    fun `turning reminders off then on cannot revive an earlier candidate`() {
        val gate = ReminderDeliveryGate()
        val pending = gate.capture(1, ReminderType.SLEEP)
        gate.invalidate {}
        assertNull(gate.ifCurrent(pending) { true })
        assertEquals(true, gate.ifCurrent(gate.capture(1, ReminderType.SLEEP)) { true })
    }
}
