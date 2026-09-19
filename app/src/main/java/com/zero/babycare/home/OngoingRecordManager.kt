package com.zero.babycare.home

import com.zero.common.mmkv.MMKVStore
import com.blankj.utilcode.util.Utils
import com.zero.babycare.reminder.ReminderNotifier
import com.zero.babycare.reminder.ReminderType
import com.zero.babycare.reminder.ReminderScheduler
import com.zero.common.util.TimerClock
import com.zero.common.util.TimerClockSnapshot
import java.util.UUID

/**
 * 进行中记录的持久化单一事实源。
 *
 * 每个宝宝、每种记录类型各自保存完整会话；页面、Dashboard 与提醒都从同一会话推导状态，
 * 避免只保存开始时间导致暂停、换宝宝或进程重启后状态漂移。
 */
object OngoingRecordManager {

    private const val KEY_SESSION_PREFIX = "ongoing_session_"
    private const val KEY_TRACKED_BABY_IDS = "ongoing_session_baby_ids"

    private fun sessionKey(babyId: Int, type: RecordType): String {
        return "$KEY_SESSION_PREFIX${type.name.lowercase()}_$babyId"
    }

    private fun getTrackedBabyIds(): MutableSet<Int> {
        return MMKVStore.get(
            KEY_TRACKED_BABY_IDS,
            IntArray::class.java,
            decrypt = true
        )?.toMutableSet() ?: mutableSetOf()
    }

    private fun saveTrackedBabyIds(ids: Set<Int>) {
        if (ids.isEmpty()) {
            MMKVStore.remove(KEY_TRACKED_BABY_IDS)
        } else {
            MMKVStore.put(KEY_TRACKED_BABY_IDS, ids.toIntArray(), encrypt = true)
        }
    }

    @Synchronized
    private fun readSession(babyId: Int, type: RecordType): OngoingSession? {
        val stored = MMKVStore.get(
            sessionKey(babyId, type),
            OngoingSession::class.java,
            decrypt = true
        )?.takeIf {
            it.babyId == babyId && it.type == type && it.startAt > 0L
        } ?: return null
        val identified = if (stored.sessionId.isNullOrBlank()) stored.copy(sessionId = UUID.randomUUID().toString()) else stored
        val restored = identified.reconcileClock(TimerClock.snapshot())
        // 旧会话补锚点、跨启动恢复或自动日期校正只落盘一次，普通读取不改变保存身份。
        if (restored != stored) MMKVStore.put(sessionKey(babyId, type), restored, encrypt = true)
        return restored
    }

    @Synchronized
    private fun saveSession(session: OngoingSession) {
        MMKVStore.put(
            sessionKey(session.babyId, session.type),
            session,
            encrypt = true
        )
        val ids = getTrackedBabyIds()
        if (ids.add(session.babyId)) saveTrackedBabyIds(ids)
    }

    @Synchronized
    private fun removeSession(babyId: Int, type: RecordType): OngoingSession? {
        val session = readSession(babyId, type)
        MMKVStore.remove(sessionKey(babyId, type))
        if (readSession(babyId, RecordType.FEEDING) == null &&
            readSession(babyId, RecordType.SLEEP) == null
        ) {
            val ids = getTrackedBabyIds()
            if (ids.remove(babyId)) saveTrackedBabyIds(ids)
        }
        return session
    }

    fun startSleep(babyId: Int, startTime: Long = System.currentTimeMillis(), automaticStart: Boolean = false) {
        if (babyId <= 0 || startTime <= 0L) return
        saveSession(OngoingSession.running(babyId, RecordType.SLEEP, startTime,
            automaticStart = automaticStart, now = TimerClock.snapshot()))
        ReminderNotifier.clearForBaby(Utils.getApp(), babyId, ReminderType.SLEEP)
    }

    fun pauseSleep(babyId: Int, endTime: Long, activeDurationMs: Long, expectedSessionId: String? = null) {
        updatePaused(babyId, RecordType.SLEEP, endTime, activeDurationMs, expectedSessionId)
    }

    fun resumeSleep(babyId: Int, expectedSessionId: String? = null) {
        updateRunning(babyId, RecordType.SLEEP, expectedSessionId)
    }

    fun endSleep(babyId: Int): Long? = removeSession(babyId, RecordType.SLEEP)?.startAt

    fun cancelSleep(babyId: Int) {
        cancelSession(babyId, RecordType.SLEEP)
    }

    fun getOngoingSleepStart(babyId: Int): Long? = getSleepSession(babyId)?.startAt

    fun getSleepSession(babyId: Int): OngoingSession? = readSession(babyId, RecordType.SLEEP)

    fun updateSleepStart(babyId: Int, startTime: Long, expectedSessionId: String? = null) {
        updateStart(babyId, RecordType.SLEEP, startTime, expectedSessionId)
    }

    fun isSleeping(babyId: Int): Boolean = getSleepSession(babyId) != null

    fun startFeeding(
        babyId: Int,
        feedingType: Int = 0,
        startTime: Long = System.currentTimeMillis(),
        automaticStart: Boolean = false
    ) {
        if (babyId <= 0 || startTime <= 0L) return
        saveSession(
            OngoingSession.running(
                babyId = babyId,
                type = RecordType.FEEDING,
                startAt = startTime,
                feedingType = feedingType,
                automaticStart = automaticStart,
                now = TimerClock.snapshot()
            )
        )
        ReminderNotifier.clearForBaby(Utils.getApp(), babyId, ReminderType.FEEDING)
    }

    fun pauseFeeding(babyId: Int, endTime: Long, activeDurationMs: Long, expectedSessionId: String? = null) {
        updatePaused(babyId, RecordType.FEEDING, endTime, activeDurationMs, expectedSessionId)
    }

    fun resumeFeeding(babyId: Int, expectedSessionId: String? = null) {
        updateRunning(babyId, RecordType.FEEDING, expectedSessionId)
    }

    fun endFeeding(babyId: Int): Long? = removeSession(babyId, RecordType.FEEDING)?.startAt

    fun cancelFeeding(babyId: Int) {
        cancelSession(babyId, RecordType.FEEDING)
    }

    /** 用户放弃会话后重新评估之前被抑制的提醒；保存/覆盖清理仍由各自事务善后调度。 */
    internal fun cancelSession(
        babyId: Int,
        type: RecordType,
        reschedule: () -> Unit = { ReminderScheduler.ensureScheduled(Utils.getApp()) }
    ) {
        if (removeSession(babyId, type) != null) reschedule()
    }

    fun getOngoingFeedingStart(babyId: Int): Long? = getFeedingSession(babyId)?.startAt

    fun getFeedingSession(babyId: Int): OngoingSession? = readSession(babyId, RecordType.FEEDING)

    fun getOngoingFeedingType(babyId: Int): Int = getFeedingSession(babyId)?.feedingType ?: 0

    fun updateFeedingStart(babyId: Int, startTime: Long, expectedSessionId: String? = null) {
        updateStart(babyId, RecordType.FEEDING, startTime, expectedSessionId)
    }

    @Synchronized
    fun updateFeedingType(babyId: Int, feedingType: Int, expectedSessionId: String? = null) {
        val session = getFeedingSession(babyId) ?: return
        if (expectedSessionId != null && session.sessionId != expectedSessionId) return
        saveSession(session.copy(feedingType = feedingType))
    }

    fun isFeeding(babyId: Int): Boolean = getFeedingSession(babyId) != null

    fun getCurrentStatus(babyId: Int): OngoingStatus {
        val sleep = getSleepSession(babyId)
        val feeding = getFeedingSession(babyId)
        return when {
            sleep != null && feeding != null -> {
                if (sleep.startAt >= feeding.startAt) OngoingStatus.SLEEPING else OngoingStatus.FEEDING
            }
            sleep != null -> OngoingStatus.SLEEPING
            feeding != null -> OngoingStatus.FEEDING
            else -> OngoingStatus.IDLE
        }
    }

    /** 数据域覆盖或退出时清除全部会话，不依赖可能损坏的追踪 ID 列表。 */
    @Synchronized
    fun clearAll() {
        MMKVStore.removeByPrefix(KEY_SESSION_PREFIX)
        MMKVStore.remove(KEY_TRACKED_BABY_IDS)
    }

    /** 与新会话写入共用锁，清旧宝宝时不能覆盖追踪列表里刚加入的新宝宝。 */
    @Synchronized
    fun clearBaby(babyId: Int) {
        MMKVStore.remove(sessionKey(babyId, RecordType.FEEDING))
        MMKVStore.remove(sessionKey(babyId, RecordType.SLEEP))
        val ids = getTrackedBabyIds()
        if (ids.remove(babyId)) saveTrackedBabyIds(ids)
    }

    @Synchronized
    private fun updateStart(babyId: Int, type: RecordType, startTime: Long, expectedSessionId: String?) {
        if (startTime <= 0L) return
        val session = readSession(babyId, type) ?: return
        if (expectedSessionId != null && session.sessionId != expectedSessionId) return
        if (session.startAt == startTime) {
            // 确认原值也是手工选择；仅改变日期来源，不能重算累计或重建会话身份。
            if (session.automaticStart) saveSession(session.copy(automaticStart = false))
            return
        }
        val now = TimerClock.snapshot()
        val updatedResumeAt = if (
            session.status == SessionStatus.RUNNING && session.activeDurationMs == 0L
        ) {
            startTime
        } else {
            session.resumedAt
        }
        saveSession(session.copy(
            startAt = startTime,
            resumedAt = if (updatedResumeAt != session.resumedAt) now.wallTimeMs else session.resumedAt,
            activeDurationMs = if (updatedResumeAt != session.resumedAt) (now.wallTimeMs - startTime).coerceAtLeast(0L)
                else session.activeDurationMs,
            clockAnchor = if (updatedResumeAt != session.resumedAt) now else session.clockAnchor,
            automaticStart = false
        ))
    }

    /** 用绑定的本机会话身份原子对齐自动日期；独立时钟采样不得制造毫秒级保存归属差异。 */
    @Synchronized
    fun synchronizeAutomaticStart(
        babyId: Int,
        type: RecordType,
        expectedSessionId: String,
        startTime: Long,
        now: TimerClockSnapshot
    ) {
        if (expectedSessionId.isBlank() || startTime <= 0L) return
        val session = readSession(babyId, type) ?: return
        if (session.sessionId != expectedSessionId || !session.automaticStart) return
        // 暂停后继续也允许同步其原身份；调用方只在运行/暂停转换时发出此事件。
        val shift = startTime - session.startAt
        saveSession(session.copy(
            startAt = startTime,
            endAt = session.endAt?.plus(shift),
            activeDurationMs = session.elapsedDuration(now),
            resumedAt = if (session.status == SessionStatus.RUNNING) now.wallTimeMs else 0L,
            clockAnchor = now
        ))
    }

    @Synchronized
    private fun updatePaused(
        babyId: Int,
        type: RecordType,
        endTime: Long,
        activeDurationMs: Long,
        expectedSessionId: String?
    ) {
        val session = readSession(babyId, type) ?: return
        if (expectedSessionId != null && session.sessionId != expectedSessionId) return
        // 即使墙钟回拨让手工日期暂时无效，也必须保存暂停事实，不能恢复成仍在运行。
        saveSession(session.paused(endTime, activeDurationMs, TimerClock.snapshot()))
    }

    @Synchronized
    private fun updateRunning(babyId: Int, type: RecordType, expectedSessionId: String?) {
        val session = readSession(babyId, type) ?: return
        if (expectedSessionId != null && session.sessionId != expectedSessionId) return
        saveSession(session.resumed(TimerClock.snapshot()))
    }

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
        val sessionId: String? = null
    ) {
        fun elapsedDuration(): Long = elapsedDuration(TimerClock.snapshot())

        /** 显式墙钟入口保留给纯策略调用；生产运行态传完整快照。 */
        fun elapsedDuration(now: Long): Long = elapsedDuration(TimerClockSnapshot(now))

        fun elapsedDuration(now: TimerClockSnapshot): Long {
            val accumulated = activeDurationMs.coerceAtLeast(0L)
            if (status != SessionStatus.RUNNING) {
                return if (type == RecordType.SLEEP) (endAt?.minus(startAt) ?: 0L).coerceAtLeast(accumulated)
                    else accumulated
            }
            val anchor = clockAnchor ?: TimerClockSnapshot(resumedAt.takeIf { it > 0L } ?: startAt)
            // 睡眠按完整入睡区间，喂养仅累计有效运行段，恢复与页面继续/最终保存保持一致。
            val base = if (type == RecordType.SLEEP) (anchor.wallTimeMs - startAt).coerceAtLeast(accumulated)
                else accumulated
            return base + now.elapsedSince(anchor)
        }

        /** 自动运行起点随系统校时整体平移；暂停或手工输入的历史日期保持原值。 */
        fun reconcileClock(now: TimerClockSnapshot, resuming: Boolean = false): OngoingSession {
            // 暂停快照还承担提交身份，读取时不能因启动缓存就绪而改变它；继续时再补锚点。
            if (status == SessionStatus.PAUSED && !resuming) return this
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

        /** 喂养从累计段继续；睡眠包含暂停间隔，恢复后不能比保存的完整区间短。 */
        fun resumed(now: TimerClockSnapshot): OngoingSession {
            val session = reconcileClock(now, resuming = true)
            return session.copy(
                endAt = null,
                status = SessionStatus.RUNNING,
                activeDurationMs = if (type == RecordType.SLEEP)
                    (now.wallTimeMs - session.startAt).coerceAtLeast(session.activeDurationMs)
                    else session.activeDurationMs,
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

    enum class SessionStatus { RUNNING, PAUSED }

    enum class OngoingStatus { IDLE, SLEEPING, FEEDING }
}
