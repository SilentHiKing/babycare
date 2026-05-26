package com.zero.babycare.home

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 宝宝记录变更事件总线。
 *
 * 当前 Room DAO 还没有提供 Flow，这里先用轻量事件流把保存记录后的刷新信号传给首页；
 * 后续 DAO Flow 化后，可以删除该总线并由数据库变更直接驱动 DashboardUiState。
 */
object BabyRecordChangeBus {
    private val _changes = MutableSharedFlow<Int>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val changes: SharedFlow<Int> = _changes.asSharedFlow()

    fun notifyChanged(babyId: Int) {
        _changes.tryEmit(babyId)
    }
}
