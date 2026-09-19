package com.zero.babycare.home

import com.blankj.utilcode.util.Utils
import com.zero.babycare.reminder.ReminderScheduler
import com.zero.babycare.reminder.ReminderNotifier
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
    const val ALL_BABIES_CHANGED = 0
    private val _changes = MutableSharedFlow<Int>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val changes: SharedFlow<Int> = _changes.asSharedFlow()

    /** 导入后发一次全域失效信号，避免逐宝宝事件被容量为 1 的缓冲区丢弃。 */
    fun notifyAllChanged() {
        ReminderNotifier.clearAll(Utils.getApp())
        _changes.tryEmit(ALL_BABIES_CHANGED)
    }

    fun notifyChanged(babyId: Int) {
        ReminderNotifier.clearForBaby(Utils.getApp(), babyId)
        _changes.tryEmit(babyId)
        ReminderScheduler.ensureScheduled(Utils.getApp())
    }
}
