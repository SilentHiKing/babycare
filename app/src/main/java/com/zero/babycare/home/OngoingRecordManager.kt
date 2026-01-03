package com.zero.babycare.home

import com.zero.common.mmkv.MMKVStore

/**
 * 进行中记录管理器
 * 使用 MMKV 缓存记录当前正在进行的活动状态
 */
object OngoingRecordManager {

    private const val KEY_ONGOING_SLEEP_START = "ongoing_sleep_start"
    private const val KEY_ONGOING_SLEEP_BABY_ID = "ongoing_sleep_baby_id"
    private const val KEY_ONGOING_FEEDING_START = "ongoing_feeding_start"
    private const val KEY_ONGOING_FEEDING_BABY_ID = "ongoing_feeding_baby_id"
    private const val KEY_ONGOING_FEEDING_TYPE = "ongoing_feeding_type"
    private const val KEY_ONGOING_BABY_IDS = "ongoing_baby_ids"

    private fun sleepStartKey(babyId: Int) = "${KEY_ONGOING_SLEEP_START}_$babyId"
    private fun feedingStartKey(babyId: Int) = "${KEY_ONGOING_FEEDING_START}_$babyId"
    private fun feedingTypeKey(babyId: Int) = "${KEY_ONGOING_FEEDING_TYPE}_$babyId"

    /**
     * 获取当前有进行中记录的宝宝集合
     */
    private fun getTrackedBabyIds(): MutableSet<Int> {
        val ids = MMKVStore.get(KEY_ONGOING_BABY_IDS, IntArray::class.java, null)
        return ids?.toMutableSet() ?: mutableSetOf()
    }

    /**
     * 保存进行中宝宝集合
     */
    private fun saveTrackedBabyIds(ids: Set<Int>) {
        if (ids.isEmpty()) {
            MMKVStore.remove(KEY_ONGOING_BABY_IDS)
        } else {
            MMKVStore.put(KEY_ONGOING_BABY_IDS, ids.toIntArray())
        }
    }

    /**
     * 记录有进行中状态的宝宝
     */
    private fun trackBabyId(babyId: Int) {
        val ids = getTrackedBabyIds()
        if (ids.add(babyId)) {
            saveTrackedBabyIds(ids)
        }
    }

    /**
     * 移除无进行中状态的宝宝
     */
    private fun untrackBabyId(babyId: Int) {
        val ids = getTrackedBabyIds()
        if (ids.remove(babyId)) {
            saveTrackedBabyIds(ids)
        }
    }

    /**
     * 同步宝宝是否存在进行中记录
     */
    private fun syncTrackedBabyId(babyId: Int) {
        if (getOngoingSleepStart(babyId) != null || getOngoingFeedingStart(babyId) != null) {
            trackBabyId(babyId)
        } else {
            untrackBabyId(babyId)
        }
    }

    // ==================== 睡眠 ====================

    /**
     * 开始睡眠记录
     */
    fun startSleep(babyId: Int) {
        MMKVStore.put(sleepStartKey(babyId), System.currentTimeMillis())
        MMKVStore.remove(KEY_ONGOING_SLEEP_START)
        MMKVStore.remove(KEY_ONGOING_SLEEP_BABY_ID)
        trackBabyId(babyId)
    }

    /**
     * 结束睡眠记录
     * @return 开始时间，如果没有进行中的记录则返回 null
     */
    fun endSleep(babyId: Int): Long? {
        val start = getOngoingSleepStart(babyId)
        MMKVStore.remove(sleepStartKey(babyId))
        if (MMKVStore.getInt(KEY_ONGOING_SLEEP_BABY_ID, -1) == babyId) {
            MMKVStore.remove(KEY_ONGOING_SLEEP_START)
            MMKVStore.remove(KEY_ONGOING_SLEEP_BABY_ID)
        }
        syncTrackedBabyId(babyId)
        return start
    }

    /**
     * 获取正在进行的睡眠开始时间
     */
    fun getOngoingSleepStart(babyId: Int): Long? {
        val startKey = sleepStartKey(babyId)
        val start = MMKVStore.getLong(startKey, 0L)
        if (start > 0) {
            trackBabyId(babyId)
            return start
        }

        val legacyStart = MMKVStore.getLong(KEY_ONGOING_SLEEP_START, 0L)
        val legacyBabyId = MMKVStore.getInt(KEY_ONGOING_SLEEP_BABY_ID, -1)
        if (legacyStart > 0 && legacyBabyId == babyId) {
            MMKVStore.put(startKey, legacyStart)
            MMKVStore.remove(KEY_ONGOING_SLEEP_START)
            MMKVStore.remove(KEY_ONGOING_SLEEP_BABY_ID)
            trackBabyId(babyId)
            return legacyStart
        }
        return null
    }

    /**
     * 获取正在进行的睡眠对应的宝宝ID
     */
    /**
     * 检查指定宝宝是否正在睡觉
     */
    fun isSleeping(babyId: Int): Boolean {
        return getOngoingSleepStart(babyId) != null
    }

    /**
     * 取消睡眠记录（不保存）
     */
    fun cancelSleep(babyId: Int) {
        MMKVStore.remove(sleepStartKey(babyId))
        if (MMKVStore.getInt(KEY_ONGOING_SLEEP_BABY_ID, -1) == babyId) {
            MMKVStore.remove(KEY_ONGOING_SLEEP_START)
            MMKVStore.remove(KEY_ONGOING_SLEEP_BABY_ID)
        }
        syncTrackedBabyId(babyId)
    }

    // ==================== 喂养 ====================

    /**
     * 开始喂养记录
     * @param feedingType 喂养类型（0=母乳，1=配方奶，2=混合）
     */
    fun startFeeding(babyId: Int, feedingType: Int = 0) {
        MMKVStore.put(feedingStartKey(babyId), System.currentTimeMillis())
        MMKVStore.put(feedingTypeKey(babyId), feedingType)
        MMKVStore.remove(KEY_ONGOING_FEEDING_START)
        MMKVStore.remove(KEY_ONGOING_FEEDING_BABY_ID)
        MMKVStore.remove(KEY_ONGOING_FEEDING_TYPE)
        trackBabyId(babyId)
    }

    /**
     * 结束喂养记录
     * @return 开始时间，如果没有进行中的记录则返回 null
     */
    fun endFeeding(babyId: Int): Long? {
        val start = getOngoingFeedingStart(babyId)
        MMKVStore.remove(feedingStartKey(babyId))
        MMKVStore.remove(feedingTypeKey(babyId))
        if (MMKVStore.getInt(KEY_ONGOING_FEEDING_BABY_ID, -1) == babyId) {
            MMKVStore.remove(KEY_ONGOING_FEEDING_START)
            MMKVStore.remove(KEY_ONGOING_FEEDING_BABY_ID)
            MMKVStore.remove(KEY_ONGOING_FEEDING_TYPE)
        }
        syncTrackedBabyId(babyId)
        return start
    }

    /**
     * 获取正在进行的喂养开始时间
     */
    fun getOngoingFeedingStart(babyId: Int): Long? {
        val startKey = feedingStartKey(babyId)
        val start = MMKVStore.getLong(startKey, 0L)
        if (start > 0) {
            trackBabyId(babyId)
            return start
        }

        val legacyStart = MMKVStore.getLong(KEY_ONGOING_FEEDING_START, 0L)
        val legacyBabyId = MMKVStore.getInt(KEY_ONGOING_FEEDING_BABY_ID, -1)
        if (legacyStart > 0 && legacyBabyId == babyId) {
            MMKVStore.put(startKey, legacyStart)
            MMKVStore.put(feedingTypeKey(babyId), MMKVStore.getInt(KEY_ONGOING_FEEDING_TYPE, 0))
            MMKVStore.remove(KEY_ONGOING_FEEDING_START)
            MMKVStore.remove(KEY_ONGOING_FEEDING_BABY_ID)
            MMKVStore.remove(KEY_ONGOING_FEEDING_TYPE)
            trackBabyId(babyId)
            return legacyStart
        }
        return null
    }

    /**
     * 获取正在进行的喂养对应的宝宝ID
     */
    /**
     * 获取正在进行的喂养类型
     */
    fun getOngoingFeedingType(babyId: Int): Int {
        val typeKey = feedingTypeKey(babyId)
        val type = MMKVStore.getInt(typeKey, -1)
        if (type >= 0) {
            return type
        }

        val legacyStart = MMKVStore.getLong(KEY_ONGOING_FEEDING_START, 0L)
        val legacyBabyId = MMKVStore.getInt(KEY_ONGOING_FEEDING_BABY_ID, -1)
        if (legacyStart > 0 && legacyBabyId == babyId) {
            val legacyType = MMKVStore.getInt(KEY_ONGOING_FEEDING_TYPE, 0)
            MMKVStore.put(typeKey, legacyType)
            MMKVStore.put(feedingStartKey(babyId), legacyStart)
            MMKVStore.remove(KEY_ONGOING_FEEDING_START)
            MMKVStore.remove(KEY_ONGOING_FEEDING_BABY_ID)
            MMKVStore.remove(KEY_ONGOING_FEEDING_TYPE)
            return legacyType
        }

        return 0
    }

    /**
     * 同步进行中的喂养开始时间（仅在已有进行中记录时更新）
     */
    fun updateFeedingStart(babyId: Int, startTime: Long) {
        if (startTime <= 0L) return
        if (getOngoingFeedingStart(babyId) == null) return

        MMKVStore.put(feedingStartKey(babyId), startTime)
        // 清理旧字段，避免新旧数据冲突
        MMKVStore.remove(KEY_ONGOING_FEEDING_START)
        MMKVStore.remove(KEY_ONGOING_FEEDING_BABY_ID)
        trackBabyId(babyId)
    }

    /**
     * 同步进行中的喂养类型（仅在已有进行中记录时更新）
     */
    fun updateFeedingType(babyId: Int, feedingType: Int) {
        if (getOngoingFeedingStart(babyId) == null) return

        MMKVStore.put(feedingTypeKey(babyId), feedingType)
        // 清理旧字段，避免新旧数据冲突
        MMKVStore.remove(KEY_ONGOING_FEEDING_TYPE)
        MMKVStore.remove(KEY_ONGOING_FEEDING_BABY_ID)
        trackBabyId(babyId)
    }

    /**
     * 同步进行中的睡眠开始时间（仅在已有进行中记录时更新）
     */
    fun updateSleepStart(babyId: Int, startTime: Long) {
        if (startTime <= 0L) return
        if (getOngoingSleepStart(babyId) == null) return

        MMKVStore.put(sleepStartKey(babyId), startTime)
        // 清理旧字段，避免新旧数据冲突
        MMKVStore.remove(KEY_ONGOING_SLEEP_START)
        MMKVStore.remove(KEY_ONGOING_SLEEP_BABY_ID)
        trackBabyId(babyId)
    }

    /**
     * 检查指定宝宝是否正在喂奶
     */
    fun isFeeding(babyId: Int): Boolean {
        return getOngoingFeedingStart(babyId) != null
    }

    /**
     * 取消喂养记录（不保存）
     */
    fun cancelFeeding(babyId: Int) {
        MMKVStore.remove(feedingStartKey(babyId))
        MMKVStore.remove(feedingTypeKey(babyId))
        if (MMKVStore.getInt(KEY_ONGOING_FEEDING_BABY_ID, -1) == babyId) {
            MMKVStore.remove(KEY_ONGOING_FEEDING_START)
            MMKVStore.remove(KEY_ONGOING_FEEDING_BABY_ID)
            MMKVStore.remove(KEY_ONGOING_FEEDING_TYPE)
        }
        syncTrackedBabyId(babyId)
    }

    // ==================== 通用 ====================

    /**
     * 获取当前宝宝的状态
     */
    fun getCurrentStatus(babyId: Int): OngoingStatus {
        val sleepStart = getOngoingSleepStart(babyId)
        val feedingStart = getOngoingFeedingStart(babyId)
        return when {
            // 同时存在时以最近一次开始的记录为准，避免状态冲突
            sleepStart != null && feedingStart != null -> {
                if (sleepStart >= feedingStart) OngoingStatus.SLEEPING else OngoingStatus.FEEDING
            }
            sleepStart != null -> OngoingStatus.SLEEPING
            feedingStart != null -> OngoingStatus.FEEDING
            else -> OngoingStatus.IDLE
        }
    }

    /**
     * 清除所有进行中的记录
     */
    fun clearAll() {
        val trackedIds = getTrackedBabyIds()
        trackedIds.forEach { babyId ->
            MMKVStore.remove(sleepStartKey(babyId))
            MMKVStore.remove(feedingStartKey(babyId))
            MMKVStore.remove(feedingTypeKey(babyId))
        }
        MMKVStore.remove(KEY_ONGOING_BABY_IDS)

        val legacySleepBabyId = MMKVStore.getInt(KEY_ONGOING_SLEEP_BABY_ID, -1)
        if (legacySleepBabyId >= 0) {
            MMKVStore.remove(sleepStartKey(legacySleepBabyId))
        }
        val legacyFeedingBabyId = MMKVStore.getInt(KEY_ONGOING_FEEDING_BABY_ID, -1)
        if (legacyFeedingBabyId >= 0) {
            MMKVStore.remove(feedingStartKey(legacyFeedingBabyId))
            MMKVStore.remove(feedingTypeKey(legacyFeedingBabyId))
        }

        MMKVStore.remove(KEY_ONGOING_SLEEP_START)
        MMKVStore.remove(KEY_ONGOING_SLEEP_BABY_ID)
        MMKVStore.remove(KEY_ONGOING_FEEDING_START)
        MMKVStore.remove(KEY_ONGOING_FEEDING_BABY_ID)
        MMKVStore.remove(KEY_ONGOING_FEEDING_TYPE)
    }

    /**
     * 进行中状态枚举
     */
    enum class OngoingStatus {
        IDLE,       // 空闲
        SLEEPING,   // 睡觉中
        FEEDING     // 喂奶中
    }
}
