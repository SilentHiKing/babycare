package com.zero.babycare.home

import com.zero.common.mmkv.MMKVStore
import com.blankj.utilcode.util.Utils
import com.zero.babycare.reminder.ReminderNotifier
import com.zero.babycare.reminder.ReminderType

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

    private fun readSession(babyId: Int, type: RecordType): OngoingSession? {
        return MMKVStore.get(
            sessionKey(babyId, type),
            OngoingSession::class.java,
            decrypt = true
        )?.takeIf {
            it.babyId == babyId && it.type == type && it.startAt > 0L
        }
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

    fun startSleep(babyId: Int, startTime: Long = System.currentTimeMillis()) {
        if (babyId <= 0 || startTime <= 0L) return
        saveSession(OngoingSession.running(babyId, RecordType.SLEEP, startTime))
        ReminderNotifier.clearForBaby(Utils.getApp(), babyId, ReminderType.SLEEP)
    }

    fun pauseSleep(babyId: Int, endTime: Long, activeDurationMs: Long) {
        updatePaused(babyId, RecordType.SLEEP, endTime, activeDurationMs)
    }

    fun resumeSleep(babyId: Int) {
        updateRunning(babyId, RecordType.SLEEP)
    }

    fun endSleep(babyId: Int): Long? = removeSession(babyId, RecordType.SLEEP)?.startAt

    fun cancelSleep(babyId: Int) {
        removeSession(babyId, RecordType.SLEEP)
    }

    fun getOngoingSleepStart(babyId: Int): Long? = getSleepSession(babyId)?.startAt

    fun getSleepSession(babyId: Int): OngoingSession? = readSession(babyId, RecordType.SLEEP)

    fun updateSleepStart(babyId: Int, startTime: Long) {
        updateStart(babyId, RecordType.SLEEP, startTime)
    }

    fun isSleeping(babyId: Int): Boolean = getSleepSession(babyId) != null

    fun startFeeding(
        babyId: Int,
        feedingType: Int = 0,
        startTime: Long = System.currentTimeMillis()
    ) {
        if (babyId <= 0 || startTime <= 0L) return
        saveSession(
            OngoingSession.running(
                babyId = babyId,
                type = RecordType.FEEDING,
                startAt = startTime,
                feedingType = feedingType
            )
        )
        ReminderNotifier.clearForBaby(Utils.getApp(), babyId, ReminderType.FEEDING)
    }

    fun pauseFeeding(babyId: Int, endTime: Long, activeDurationMs: Long) {
        updatePaused(babyId, RecordType.FEEDING, endTime, activeDurationMs)
    }

    fun resumeFeeding(babyId: Int) {
        updateRunning(babyId, RecordType.FEEDING)
    }

    fun endFeeding(babyId: Int): Long? = removeSession(babyId, RecordType.FEEDING)?.startAt

    fun cancelFeeding(babyId: Int) {
        removeSession(babyId, RecordType.FEEDING)
    }

    fun getOngoingFeedingStart(babyId: Int): Long? = getFeedingSession(babyId)?.startAt

    fun getFeedingSession(babyId: Int): OngoingSession? = readSession(babyId, RecordType.FEEDING)

    fun getOngoingFeedingType(babyId: Int): Int = getFeedingSession(babyId)?.feedingType ?: 0

    fun updateFeedingStart(babyId: Int, startTime: Long) {
        updateStart(babyId, RecordType.FEEDING, startTime)
    }

    fun updateFeedingType(babyId: Int, feedingType: Int) {
        val session = getFeedingSession(babyId) ?: return
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

    private fun updateStart(babyId: Int, type: RecordType, startTime: Long) {
        if (startTime <= 0L) return
        val session = readSession(babyId, type) ?: return
        val updatedResumeAt = if (
            session.status == SessionStatus.RUNNING && session.activeDurationMs == 0L
        ) {
            startTime
        } else {
            session.resumedAt
        }
        saveSession(session.copy(startAt = startTime, resumedAt = updatedResumeAt))
    }

    private fun updatePaused(
        babyId: Int,
        type: RecordType,
        endTime: Long,
        activeDurationMs: Long
    ) {
        val session = readSession(babyId, type) ?: return
        if (endTime < session.startAt) return
        saveSession(
            session.copy(
                endAt = endTime,
                status = SessionStatus.PAUSED,
                activeDurationMs = activeDurationMs.coerceAtLeast(0L),
                resumedAt = 0L
            )
        )
    }

    private fun updateRunning(babyId: Int, type: RecordType) {
        val session = readSession(babyId, type) ?: return
        saveSession(
            session.copy(
                endAt = null,
                status = SessionStatus.RUNNING,
                resumedAt = System.currentTimeMillis()
            )
        )
    }

    data class OngoingSession(
        val babyId: Int,
        val type: RecordType,
        val startAt: Long,
        val endAt: Long? = null,
        val status: SessionStatus,
        val feedingType: Int = 0,
        val activeDurationMs: Long = 0L,
        val resumedAt: Long = 0L
    ) {
        /** 运行态叠加本次进程恢复后的时长；暂停态保持落盘时的累计时长。 */
        fun elapsedDuration(now: Long = System.currentTimeMillis()): Long {
            if (status != SessionStatus.RUNNING || resumedAt <= 0L) return activeDurationMs
            return (activeDurationMs + (now - resumedAt)).coerceAtLeast(0L)
        }

        companion object {
            fun running(
                babyId: Int,
                type: RecordType,
                startAt: Long,
                feedingType: Int = 0
            ) = OngoingSession(
                babyId = babyId,
                type = type,
                startAt = startAt,
                status = SessionStatus.RUNNING,
                feedingType = feedingType,
                resumedAt = startAt
            )
        }
    }

    enum class RecordType { FEEDING, SLEEP }

    enum class SessionStatus { RUNNING, PAUSED }

    enum class OngoingStatus { IDLE, SLEEPING, FEEDING }
}
