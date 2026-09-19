package com.zero.babycare.reminder

/** 区分永久抑制、候选变化和临时失败，避免未发送提醒反复生成零延迟任务。 */
internal enum class ReminderDeliveryResult {
    DELIVERED,
    SUPPRESSED,
    RESCHEDULE,
    RETRY
}

internal object ReminderDueHandler {

    /** 只有候选变化或系统时间回拨才重排；系统失败交给 WorkManager 退避重试。 */
    suspend fun run(
        deliver: suspend () -> ReminderDeliveryResult,
        reschedule: () -> Unit
    ): Boolean {
        return when (deliver()) {
            ReminderDeliveryResult.RESCHEDULE -> {
                reschedule()
                false
            }
            ReminderDeliveryResult.RETRY -> true
            ReminderDeliveryResult.DELIVERED,
            ReminderDeliveryResult.SUPPRESSED -> false
        }
    }
}
