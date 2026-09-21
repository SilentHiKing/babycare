package com.zero.babycare.home

import com.zero.babydata.domain.*
import com.zero.babydata.entity.FeedingRecord
import com.zero.babydata.entity.SleepRecord
import com.zero.common.util.TimerClock
import com.zero.common.util.TimerClockSnapshot
import java.util.UUID

/** 首页与提醒的只读投影。Room 业务行是唯一事实源，所有查询由调用者在协程中执行。 */
object OngoingRecordManager {
    suspend fun getSession(babyId: Int, type: RecordType): OngoingSession? =
        BabyDataHelper.repository.timers.findUnfinished(
            if (type == RecordType.FEEDING) TimerRecordKind.FEEDING else TimerRecordKind.SLEEP, babyId
        )?.let(::fromSnapshot)
    suspend fun getFeedingSession(babyId: Int) = getSession(babyId, RecordType.FEEDING)
    suspend fun getSleepSession(babyId: Int) = getSession(babyId, RecordType.SLEEP)
    suspend fun isFeeding(babyId: Int) = getFeedingSession(babyId) != null
    suspend fun isSleeping(babyId: Int) = getSleepSession(babyId) != null
    suspend fun getCurrentStatus(babyId: Int): OngoingStatus {
        val feeding = getFeedingSession(babyId)
        val sleep = getSleepSession(babyId)
        return when {
            sleep != null && (feeding == null || sleep.startAt >= feeding.startAt) -> OngoingStatus.SLEEPING
            feeding != null -> OngoingStatus.FEEDING
            else -> OngoingStatus.IDLE
        }
    }
    /** 被替换宝宝的计时行随外键级联删除；没有第二份状态需要清理。 */
    fun clearBaby(babyId: Int) = Unit
    fun fromSnapshot(value: TimerRecordSnapshot) = OngoingSession(
        babyId = value.ref.babyId,
        type = if (value.ref.kind == TimerRecordKind.FEEDING) RecordType.FEEDING else RecordType.SLEEP,
        startAt = value.startAt, endAt = value.endAt,
        status = when (value.status) {
            TimerStatus.RUNNING -> SessionStatus.RUNNING
            TimerStatus.PAUSED -> SessionStatus.PAUSED
            else -> SessionStatus.STOPPED
        },
        feedingType = value.feedingType, activeDurationMs = value.durationMs,
        clockAnchor = value.metadata.clockAnchor, automaticStart = value.metadata.automaticStart,
        sessionId = value.ref.sessionId, pendingFeeding = value.feeding, pendingSleep = value.sleep,
        snapshot = value
    )

    data class OngoingSession(
        val babyId: Int,
        val type: RecordType,
        val startAt: Long,
        val endAt: Long? = null,
        val status: SessionStatus,
        val feedingType: Int = 0,
        val activeDurationMs: Long = 0L,
        val resumedAt: Long = 0L,
        val clockAnchor: TimerClockSnapshot? = null,
        // 旧 JSON 未包含此字段时为 false；不能将历史手工日期当作自动计时起点挪动。
        val automaticStart: Boolean = false,
        // 仅用于本机会话归属；旧 JSON 在首次读取时补一次，日期校正不改变此身份。
        val sessionId: String? = null,
        // 冻结记录与会话同键同步落盘；只用于本机提交恢复，不进入 Room schema 或备份协议。
        val pendingSave: Boolean = false,
        val pendingFeeding: FeedingRecord? = null,
        val pendingSleep: SleepRecord? = null,
        val snapshot: TimerRecordSnapshot? = null
    ) {
        fun elapsedDuration(): Long = elapsedDuration(TimerClock.snapshot())

        /** 显式墙钟入口保留给纯策略调用；生产运行态传完整快照。 */
        fun elapsedDuration(now: Long): Long = elapsedDuration(TimerClockSnapshot(now))

        fun elapsedDuration(now: TimerClockSnapshot): Long {
            snapshot?.let { return TimerCalculations.totalDuration(it.metadata, now) }
            val accumulated = activeDurationMs.coerceAtLeast(0L)
            if (status != SessionStatus.RUNNING) return accumulated
            return accumulated + now.elapsedSince(clockAnchor ?: TimerClockSnapshot(resumedAt.takeIf { it > 0L } ?: startAt))
        }

        /** 自动运行起点随系统校时整体平移；暂停或手工输入的历史日期保持原值。 */
        fun reconcileClock(now: TimerClockSnapshot, resuming: Boolean = false): OngoingSession {
            // 暂停快照还承担提交身份，读取时不能因启动缓存就绪而改变它；继续时再补锚点。
            if (snapshot != null || (status != SessionStatus.RUNNING && !resuming)) return this
            if (clockAnchor != null && clockAnchor.bootCount == null && now.bootCount != null &&
                !now.processId.isNullOrEmpty() && now.processId == clockAnchor.processId) {
                // IO 初始化可能晚于首次开始计时；仅同进程可信锚点可补开机标识，供下一进程恢复。
                return copy(clockAnchor = clockAnchor.copy(bootCount = now.bootCount)).reconcileClock(now, resuming)
            }
            val anchor = clockAnchor ?: TimerClockSnapshot(
                if (status == SessionStatus.RUNNING) resumedAt.takeIf { it > 0L } ?: startAt
                else endAt ?: startAt
            )
            val shift = if (automaticStart && (status == SessionStatus.RUNNING || resuming))
                now.automaticWallShiftSince(anchor) else 0L
            val shifted = if (shift == 0L) this else copy(
                startAt = startAt + shift,
                endAt = endAt?.plus(shift),
                resumedAt = if (resumedAt > 0L) resumedAt + shift else 0L,
                clockAnchor = anchor.copy(wallTimeMs = anchor.wallTimeMs + shift)
            )
            if (clockAnchor != null && now.sharesMonotonicClock(anchor)) return shifted
            // 同次开机身份未知或确已重启时只能用非负墙钟差；保留已有累计后重新建立锚点。
            return shifted.copy(
                activeDurationMs = elapsedDuration(now),
                resumedAt = if (status == SessionStatus.RUNNING) now.wallTimeMs else 0L,
                clockAnchor = now
            )
        }

        /** 暂停转换始终落盘，时钟异常也不能把已停止的 UI 恢复成运行态。 */
        fun paused(endTime: Long, activeDuration: Long, now: TimerClockSnapshot): OngoingSession {
            val session = reconcileClock(now)
            return session.copy(
                endAt = endTime,
                status = SessionStatus.PAUSED,
                // 页面提交单调计时的确定快照，或用户明确修改的区间；不能再采样添入暂停后的时间。
                activeDurationMs = activeDuration.coerceAtLeast(0L),
                resumedAt = 0L,
                clockAnchor = now
            )
        }

        /** 所有记录都从累计有效段继续，暂停间隔不计时。 */
        fun resumed(now: TimerClockSnapshot): OngoingSession {
            val session = reconcileClock(now, resuming = true)
            return session.copy(
                endAt = null,
                status = SessionStatus.RUNNING,
                activeDurationMs = session.activeDurationMs,
                resumedAt = now.wallTimeMs,
                clockAnchor = now
            )
        }

        companion object {
            fun running(
                babyId: Int,
                type: RecordType,
                startAt: Long,
                feedingType: Int = 0,
                automaticStart: Boolean = false,
                now: TimerClockSnapshot? = null
            ) = OngoingSession(
                babyId = babyId,
                type = type,
                startAt = startAt,
                status = SessionStatus.RUNNING,
                feedingType = feedingType,
                activeDurationMs = now?.let { (it.wallTimeMs - startAt).coerceAtLeast(0L) } ?: 0L,
                resumedAt = now?.wallTimeMs ?: startAt,
                clockAnchor = now,
                automaticStart = automaticStart,
                sessionId = UUID.randomUUID().toString()
            )
        }
    }

    enum class RecordType { FEEDING, SLEEP }

    enum class SessionStatus { RUNNING, PAUSED, STOPPED }

    enum class OngoingStatus { IDLE, SLEEPING, FEEDING }
}
