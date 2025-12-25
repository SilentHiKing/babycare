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

    // ==================== 睡眠 ====================

    /**
     * 开始睡眠记录
     */
    fun startSleep(babyId: Int) {
        MMKVStore.put(KEY_ONGOING_SLEEP_START, System.currentTimeMillis())
        MMKVStore.put(KEY_ONGOING_SLEEP_BABY_ID, babyId)
    }

    /**
     * 结束睡眠记录
     * @return 开始时间，如果没有进行中的记录则返回 null
     */
    fun endSleep(): Long? {
        val start = MMKVStore.getLong(KEY_ONGOING_SLEEP_START, 0L)
        MMKVStore.remove(KEY_ONGOING_SLEEP_START)
        MMKVStore.remove(KEY_ONGOING_SLEEP_BABY_ID)
        return if (start > 0) start else null
    }

    /**
     * 获取正在进行的睡眠开始时间
     */
    fun getOngoingSleepStart(): Long? {
        val start = MMKVStore.getLong(KEY_ONGOING_SLEEP_START, 0L)
        return if (start > 0) start else null
    }

    /**
     * 获取正在进行的睡眠对应的宝宝ID
     */
    fun getOngoingSleepBabyId(): Int? {
        val babyId = MMKVStore.getInt(KEY_ONGOING_SLEEP_BABY_ID, -1)
        return if (babyId > 0) babyId else null
    }

    /**
     * 检查指定宝宝是否正在睡觉
     */
    fun isSleeping(babyId: Int): Boolean {
        return getOngoingSleepStart() != null && getOngoingSleepBabyId() == babyId
    }

    /**
     * 取消睡眠记录（不保存）
     */
    fun cancelSleep() {
        MMKVStore.remove(KEY_ONGOING_SLEEP_START)
        MMKVStore.remove(KEY_ONGOING_SLEEP_BABY_ID)
    }

    // ==================== 喂养 ====================

    /**
     * 开始喂养记录
     * @param feedingType 喂养类型（0=母乳，1=配方奶，2=混合）
     */
    fun startFeeding(babyId: Int, feedingType: Int = 0) {
        MMKVStore.put(KEY_ONGOING_FEEDING_START, System.currentTimeMillis())
        MMKVStore.put(KEY_ONGOING_FEEDING_BABY_ID, babyId)
        MMKVStore.put(KEY_ONGOING_FEEDING_TYPE, feedingType)
    }

    /**
     * 结束喂养记录
     * @return 开始时间，如果没有进行中的记录则返回 null
     */
    fun endFeeding(): Long? {
        val start = MMKVStore.getLong(KEY_ONGOING_FEEDING_START, 0L)
        MMKVStore.remove(KEY_ONGOING_FEEDING_START)
        MMKVStore.remove(KEY_ONGOING_FEEDING_BABY_ID)
        MMKVStore.remove(KEY_ONGOING_FEEDING_TYPE)
        return if (start > 0) start else null
    }

    /**
     * 获取正在进行的喂养开始时间
     */
    fun getOngoingFeedingStart(): Long? {
        val start = MMKVStore.getLong(KEY_ONGOING_FEEDING_START, 0L)
        return if (start > 0) start else null
    }

    /**
     * 获取正在进行的喂养对应的宝宝ID
     */
    fun getOngoingFeedingBabyId(): Int? {
        val babyId = MMKVStore.getInt(KEY_ONGOING_FEEDING_BABY_ID, -1)
        return if (babyId > 0) babyId else null
    }

    /**
     * 获取正在进行的喂养类型
     */
    fun getOngoingFeedingType(): Int {
        return MMKVStore.getInt(KEY_ONGOING_FEEDING_TYPE, 0)
    }

    /**
     * 检查指定宝宝是否正在喂奶
     */
    fun isFeeding(babyId: Int): Boolean {
        return getOngoingFeedingStart() != null && getOngoingFeedingBabyId() == babyId
    }

    /**
     * 取消喂养记录（不保存）
     */
    fun cancelFeeding() {
        MMKVStore.remove(KEY_ONGOING_FEEDING_START)
        MMKVStore.remove(KEY_ONGOING_FEEDING_BABY_ID)
        MMKVStore.remove(KEY_ONGOING_FEEDING_TYPE)
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
        cancelSleep()
        cancelFeeding()
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

