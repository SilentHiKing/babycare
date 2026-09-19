package com.zero.babycare.reminder

/** 撤回和最终发布共用锁，查询期间失效的任务不能在撤回之后补发旧通知。 */
internal class ReminderDeliveryGate {
    internal data class Ticket(val babyId: Int, val type: ReminderType, val all: Long, val local: Long)

    private var generation = 0L
    private val versions = mutableMapOf<Pair<Int, ReminderType>, Long>()

    @Synchronized
    fun capture(babyId: Int, type: ReminderType) =
        Ticket(babyId, type, generation, versions[babyId to type] ?: 0L)

    @Synchronized
    fun <T> ifCurrent(ticket: Ticket, action: () -> T): T? {
        if (ticket.all != generation || ticket.local != (versions[ticket.babyId to ticket.type] ?: 0L)) return null
        return action()
    }

    @Synchronized
    fun invalidate(babyId: Int? = null, type: ReminderType? = null, action: () -> Unit) {
        if (babyId == null) {
            generation++
            versions.clear()
        } else {
            (type?.let(::listOf) ?: ReminderType.entries).forEach { recordType ->
                val key = babyId to recordType
                versions[key] = (versions[key] ?: 0L) + 1L
            }
        }
        action()
    }
}
