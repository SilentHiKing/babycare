package com.zero.common.util

import kotlin.math.abs

/** 墙钟负责日期，单调时钟负责经过时长；可空字段兼容旧草稿与旧会话 JSON。 */
data class TimerClockSnapshot(
    val wallTimeMs: Long,
    val elapsedRealtimeMs: Long? = null,
    val bootCount: Int? = null,
    val processId: String? = null
) {
    fun sharesMonotonicClock(anchor: TimerClockSnapshot): Boolean {
        if (elapsedRealtimeMs == null || anchor.elapsedRealtimeMs == null ||
            elapsedRealtimeMs < anchor.elapsedRealtimeMs) return false
        return (bootCount != null && bootCount >= 0 && bootCount == anchor.bootCount) ||
            (!processId.isNullOrEmpty() && processId == anchor.processId)
    }

    /** 跨重启/旧数据无法确定真实耗时，保守使用非负墙钟差，绝不能抵消已累计时长。 */
    fun elapsedSince(anchor: TimerClockSnapshot): Long = if (sharesMonotonicClock(anchor)) {
        requireNotNull(elapsedRealtimeMs) - requireNotNull(anchor.elapsedRealtimeMs)
    } else {
        (wallTimeMs - anchor.wallTimeMs).coerceAtLeast(0L)
    }

    /** 仅供自动计时的日期锚点校正；手工填写的历史日期不能调用此平移。 */
    fun automaticWallShiftSince(anchor: TimerClockSnapshot): Long {
        val shift = wallTimeMs - anchor.wallTimeMs - elapsedSince(anchor)
        // 两次系统采样可能相差毫秒，不能因此反复改写日期或改变会话保存身份。
        return if (abs(shift) >= 1_000L) shift else 0L
    }
}
