package com.zero.babycare.statistics.mapper

import com.zero.babycare.statistics.standard.WhoSex
import com.zero.common.util.BabyGender

/**
 * WHO 标准只区分男孩/女孩。这里把用户资料中的本地化性别文案收敛为标准枚举，
 * 并优先匹配 female/girl，避免 female 中包含 male 导致误判。
 */
object WhoSexParser {

    fun parse(gender: String?): WhoSex {
        return when (BabyGender.normalize(gender)) {
            BabyGender.GIRL -> WhoSex.GIRL
            BabyGender.BOY -> WhoSex.BOY
            else -> WhoSex.UNKNOWN
        }
    }
}
