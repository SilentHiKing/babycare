package com.zero.babydata.domain

import java.util.Locale

/** 用药单位持久化为稳定代码；历史五语标签只在读取边界兼容，不再受显示语言影响。 */
enum class MedicineUnit(val code: String) {
    ML("ml"), MG("mg"), DROP("drop"), TABLET("tablet");

    companion object {
        fun fromStored(value: String?): MedicineUnit? = when (value?.trim()?.lowercase(Locale.ROOT)) {
            "ml", "毫升", "ミリリットル", "밀리리터" -> ML
            "mg", "毫克", "ミリグラム", "밀리그램" -> MG
            "drop", "滴", "방울" -> DROP
            "tablet", "片", "錠", "정" -> TABLET
            else -> null
        }

        // 未知单位可能来自旧版本或导入，不能猜测成 ml，必须让用户有机会原样保留。
        fun canonicalValue(value: String?): String? = fromStored(value)?.code ?: value
    }
}
