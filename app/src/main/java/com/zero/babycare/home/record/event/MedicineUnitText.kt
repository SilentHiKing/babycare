package com.zero.babycare.home.record.event

import com.zero.babydata.domain.MedicineUnit
import com.zero.common.R

/** 记录页与时间轴共用单位显示映射；数据库代码不直接作为本地化文案展示。 */
object MedicineUnitText {
    fun label(value: String?, getString: (Int) -> String): String? = when (MedicineUnit.fromStored(value)) {
        MedicineUnit.ML -> getString(R.string.unit_ml_abbr)
        MedicineUnit.MG -> getString(R.string.unit_mg_abbr)
        MedicineUnit.DROP -> getString(R.string.unit_drop)
        MedicineUnit.TABLET -> getString(R.string.unit_tablet)
        null -> value
    }
}
