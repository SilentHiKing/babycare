package com.zero.babycare.statistics.mapper

import com.zero.babycare.statistics.standard.WhoSex
import java.util.Locale

/**
 * WHO 标准只区分男孩/女孩。这里把用户资料中的本地化性别文案收敛为标准枚举，
 * 并优先匹配 female/girl，避免 female 中包含 male 导致误判。
 */
object WhoSexParser {

    fun parse(gender: String?): WhoSex {
        val value = gender?.trim()?.lowercase(Locale.ROOT) ?: return WhoSex.UNKNOWN
        return when {
            value.contains("女") || value.contains("girl") || value.contains("female") -> WhoSex.GIRL
            value.contains("男") || value.contains("boy") || value.contains("male") -> WhoSex.BOY
            else -> WhoSex.UNKNOWN
        }
    }
}
