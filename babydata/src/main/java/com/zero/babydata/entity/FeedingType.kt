package com.zero.babydata.entity

/**
 * 喂养类型枚举
 */
enum class FeedingType(val type: Int) {
    /**
     * 母乳
     */
    BREAST(0),

    /**
     * 奶粉
     */
    FORMULA(1),

    /**
     * 母乳 + 奶粉 混合喂养
     */
    MIXED(2),
    /**
     * 辅食（米糊、蔬菜泥、水果泥，水，维生素，零食等）
     */
    SOLID_FOOD(3),

    /**
     * 其他（手动输入）
     */
    OTHER(4);

    /**
     * 获取显示名称
     */
    val displayName: String
        get() = when (this) {
            BREAST -> "母乳喂养"
            FORMULA -> "奶粉喂养"
            MIXED -> "混合喂养"
            SOLID_FOOD -> "辅食"
            OTHER -> "其他"
        }

    companion object {
        /**
         * 根据原始值获取枚举（数据库/网络反序列化）
         */
        @JvmStatic
        fun fromType(type: Int): FeedingType {
            return entries.find { it.type == type } ?: OTHER
        }
    }
}

