package com.zero.common.util

import androidx.annotation.StringRes
import com.zero.common.R
import java.util.Locale

/**
 * 宝宝性别的稳定编码与历史展示文案兼容映射。
 *
 * 数据库存储只使用 boy/girl，UI 展示通过资源解析，避免切换语言后继续显示旧语言文案。
 */
object BabyGender {
    const val BOY = "boy"
    const val GIRL = "girl"

    /**
     * 将历史中文/英文展示值统一收敛为稳定 code。
     */
    fun normalize(raw: String?): String {
        val value = raw?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return when {
            value.isBlank() -> ""
            value == BOY || value == "male" || value == "m" || value.contains("男") -> BOY
            value == GIRL || value == "female" || value == "f" || value.contains("女") -> GIRL
            else -> ""
        }
    }

    @StringRes
    fun labelResId(code: String?): Int? {
        return when (normalize(code)) {
            BOY -> R.string.boy
            GIRL -> R.string.girl
            else -> null
        }
    }

    fun isBoy(raw: String?): Boolean = normalize(raw) == BOY

    fun isGirl(raw: String?): Boolean = normalize(raw) == GIRL
}
