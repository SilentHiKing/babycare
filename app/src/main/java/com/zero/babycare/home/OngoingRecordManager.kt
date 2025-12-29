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

    private fun sleepStartKey(babyId: Int) = "${KEY_ONGOING_SLEEP_START}_$babyId"
    private fun feedingStartKey(babyId: Int) = "${KEY_ONGOING_FEEDING_START}_$babyId"
    private fun feedingTypeKey(babyId: Int) = "${KEY_ONGOING_FEEDING_TYPE}_$babyId"

    // ==================== 睡眠 ====================

    /**
     * 开始睡眠记录
     */
    fun startSleep(babyId: Int) {
        MMKVStore.put(sleepStartKey(babyId), System.currentTimeMillis())
        MMKVStore.remove(KEY_ONGOING_SLEEP_START)
        MMKVStore.remove(KEY_ONGOING_SLEEP_BABY_ID)
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
        return start
    }

    /**
     * 获取正在进行的睡眠开始时间
     */
    fun getOngoingSleepStart(babyId: Int): Long? {
        val startKey = sleepStartKey(babyId)
        val start = MMKVStore.getLong(startKey, 0L)
        if (start > 0) {
            return start
        }

        val legacyStart = MMKVStore.getLong(KEY_ONGOING_SLEEP_START, 0L)
        val legacyBabyId = MMKVStore.getInt(KEY_ONGOING_SLEEP_BABY_ID, -1)
        if (legacyStart > 0 && legacyBabyId == babyId) {
            MMKVStore.put(startKey, legacyStart)
            MMKVStore.remove(KEY_ONGOING_SLEEP_START)
            MMKVStore.remove(KEY_ONGOING_SLEEP_BABY_ID)
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
        return start
    }

    /**
     * 获取正在进行的喂养开始时间
     */
    fun getOngoingFeedingStart(babyId: Int): Long? {
        val startKey = feedingStartKey(babyId)
        val start = MMKVStore.getLong(startKey, 0L)
        if (start > 0) {
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
    }

    // ==================== 通用 ====================

    /**
     * 获取当前宝宝的状态
     */
    fun getCurrentStatus(babyId: Int): OngoingStatus {
        return when {
            isSleeping(babyId) -> OngoingStatus.SLEEPING
            isFeeding(babyId) -> OngoingStatus.FEEDING
            else -> OngoingStatus.IDLE
        }
    }

    /**
     * 清除所有进行中的记录
     */
    fun clearAll() {
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
