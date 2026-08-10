package com.zero.babycare.home

import com.blankj.utilcode.util.Utils
import com.zero.babycare.reminder.ReminderScheduler
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 宝宝记录变更事件总线。
 *
 * 保存、修改或删除记录提交后，由该事件同时刷新首页派生状态并重排持久化提醒任务。
 */
object BabyRecordChangeBus {
    private val _changes = MutableSharedFlow<Int>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val changes: SharedFlow<Int> = _changes.asSharedFlow()

    fun notifyChanged(babyId: Int) {
        _changes.tryEmit(babyId)
        ReminderScheduler.ensureScheduled(Utils.getApp())
    }
}
