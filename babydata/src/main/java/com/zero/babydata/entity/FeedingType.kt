package com.zero.babydata.entity

import androidx.annotation.StringRes

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
     * 获取展示名称资源，由 UI 层通过当前 context 解析。
     */
    @get:StringRes
    val displayNameResId: Int
        get() = when (this) {
            BREAST -> com.zero.common.R.string.feeding_type_breast
            FORMULA -> com.zero.common.R.string.feeding_type_formula
            MIXED -> com.zero.common.R.string.feeding_type_mixed
            SOLID_FOOD -> com.zero.common.R.string.feeding_type_solid
            OTHER -> com.zero.common.R.string.feeding_type_other
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

