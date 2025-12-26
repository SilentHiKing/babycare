package com.zero.babydata.entity

/**
 * 事件类型定义
 * 
 * 编码规则：大类(百位) + 子类(十位和个位)
 * - 1xx: 排泄类
 * - 2xx: （保留，喂养使用 FeedingRecord + FeedingType + SolidFoodType）
 * - 3xx: 生长测量类
 * - 4xx: 健康类
 * - 5xx: 里程碑类
 * - 6xx: 护理类
 * - 7xx: 活动类
 * - 9xx: 其他/自定义
 * 
 * 预留规则：
 * - 每个大类预留100个子类型（00-99）
 * - 00-49: 系统预定义
 * - 50-99: 用户自定义（未来扩展）
 * 
 * @see FeedingType 喂养类型（母乳/配方奶/辅食等）
 * @see SolidFoodType 辅食细分类型
 */
object EventType {
    
    // ==================== 1xx: 排泄类 ====================
    const val CATEGORY_DIAPER = 100
    
    /** 换尿布 - 湿（小便） */
    const val DIAPER_WET = 101
    /** 换尿布 - 脏（大便） */
    const val DIAPER_DIRTY = 102
    /** 换尿布 - 混合 */
    const val DIAPER_MIXED = 103
    /** 换尿布 - 干净（仅更换） */
    const val DIAPER_DRY = 104
    
    // 2xx 保留：喂养类使用 FeedingRecord + FeedingType + SolidFoodType
    
    // ==================== 3xx: 生长测量类 ====================
    const val CATEGORY_GROWTH = 300
    
    /** 体重 */
    const val GROWTH_WEIGHT = 301
    /** 身高/身长 */
    const val GROWTH_HEIGHT = 302
    /** 头围 */
    const val GROWTH_HEAD = 303
    
    // ==================== 4xx: 健康类 ====================
    const val CATEGORY_HEALTH = 400
    
    /** 体温 */
    const val HEALTH_TEMPERATURE = 401
    /** 用药 */
    const val HEALTH_MEDICINE = 402
    /** 疫苗接种 */
    const val HEALTH_VACCINE = 403
    /** 就医/检查 */
    const val HEALTH_DOCTOR = 404
    /** 症状记录（咳嗽、流鼻涕等） */
    const val HEALTH_SYMPTOM = 405
    
    // ==================== 5xx: 里程碑类 ====================
    const val CATEGORY_MILESTONE = 500
    
    /** 翻身 */
    const val MILESTONE_ROLL = 501
    /** 独坐 */
    const val MILESTONE_SIT = 502
    /** 爬行 */
    const val MILESTONE_CRAWL = 503
    /** 站立 */
    const val MILESTONE_STAND = 504
    /** 行走 */
    const val MILESTONE_WALK = 505
    /** 第一个词 */
    const val MILESTONE_FIRST_WORD = 506
    /** 第一颗牙 */
    const val MILESTONE_FIRST_TOOTH = 507
    /** 自定义里程碑 */
    const val MILESTONE_CUSTOM = 550
    
    // ==================== 6xx: 护理类 ====================
    const val CATEGORY_CARE = 600
    
    /** 洗澡 */
    const val CARE_BATH = 601
    /** 剪指甲 */
    const val CARE_NAIL = 602
    /** 涂护肤品/护臀膏 */
    const val CARE_SKINCARE = 603
    /** 抚触/按摩 */
    const val CARE_MASSAGE = 604
    /** 清洁鼻腔 */
    const val CARE_NOSE = 605
    /** 清洁耳朵 */
    const val CARE_EAR = 606
    
    // ==================== 7xx: 活动类 ====================
    const val CATEGORY_ACTIVITY = 700
    
    /** 户外活动/遛娃 */
    const val ACTIVITY_OUTDOOR = 701
    /** 趴着玩/Tummy Time */
    const val ACTIVITY_TUMMY_TIME = 702
    /** 游泳 */
    const val ACTIVITY_SWIMMING = 703
    /** 亲子互动/游戏 */
    const val ACTIVITY_PLAY = 704
    
    // ==================== 9xx: 其他 ====================
    const val CATEGORY_OTHER = 900
    
    /** 拍嗝 */
    const val OTHER_BURP = 901
    /** 哭闹 */
    const val OTHER_CRY = 902
    /** 吐奶 */
    const val OTHER_SPIT_UP = 903
    /** 自定义事件 */
    const val OTHER_CUSTOM = 999
    
    // ==================== 工具方法 ====================
    
    /**
     * 获取事件大类
     */
    fun getCategory(type: Int): Int = (type / 100) * 100
    
    /**
     * 判断是否属于某个大类
     */
    fun isCategory(type: Int, category: Int): Boolean = getCategory(type) == category
    
    /**
     * 判断是否是排泄类事件
     */
    fun isDiaper(type: Int): Boolean = isCategory(type, CATEGORY_DIAPER)
    
    /**
     * 判断是否是生长测量类事件
     */
    fun isGrowth(type: Int): Boolean = isCategory(type, CATEGORY_GROWTH)
    
    /**
     * 判断是否是健康类事件
     */
    fun isHealth(type: Int): Boolean = isCategory(type, CATEGORY_HEALTH)
    
    /**
     * 判断是否是里程碑事件
     */
    fun isMilestone(type: Int): Boolean = isCategory(type, CATEGORY_MILESTONE)
    
    /**
     * 判断是否需要数值输入（如体重、身高、体温）
     */
    fun requiresNumericValue(type: Int): Boolean = when (type) {
        GROWTH_WEIGHT, GROWTH_HEIGHT, GROWTH_HEAD,
        HEALTH_TEMPERATURE -> true
        else -> false
    }
    
    /**
     * 判断是否有时长（使用 endTime）
     */
    fun hasDuration(type: Int): Boolean = when (type) {
        ACTIVITY_OUTDOOR, ACTIVITY_TUMMY_TIME, 
        ACTIVITY_SWIMMING, ACTIVITY_PLAY,
        CARE_BATH, CARE_MASSAGE -> true
        else -> false
    }
}

