package com.zero.babydata.backup

import com.zero.babydata.entity.ChildDailyRecord

/** 合并导入承诺保留当前数据：旧备份仅补缺失字段，不覆盖本地较新的测量或照片。 */
internal fun mergeDailyRecord(
    existing: ChildDailyRecord,
    incoming: ChildDailyRecord
): ChildDailyRecord = existing.copy(
    weight = existing.weight.takeIf { it > 0f } ?: incoming.weight,
    height = existing.height.takeIf { it > 0f } ?: incoming.height,
    headCircumference = existing.headCircumference.takeIf { it > 0f } ?: incoming.headCircumference,
    pic = existing.pic.ifBlank { incoming.pic },
    extra = existing.extra.ifBlank { incoming.extra }
)

/** 同日可以有多次真实测量；双方都有不同内容时必须保留独立行，不得当作缺失字段合并。 */
internal fun hasDailyRecordConflict(existing: ChildDailyRecord, incoming: ChildDailyRecord): Boolean =
    (existing.weight > 0f && incoming.weight > 0f && existing.weight != incoming.weight) ||
        (existing.height > 0f && incoming.height > 0f && existing.height != incoming.height) ||
        (existing.headCircumference > 0f && incoming.headCircumference > 0f &&
            existing.headCircumference != incoming.headCircumference) ||
        (existing.pic.isNotBlank() && incoming.pic.isNotBlank() && existing.pic != incoming.pic) ||
        (existing.extra.isNotBlank() && incoming.extra.isNotBlank() && existing.extra != incoming.extra)

/** 忽略重建数据库时变化的自增主键，只比较宝宝、日期和实际内容。 */
internal fun hasSameDailyContent(existing: ChildDailyRecord, incoming: ChildDailyRecord): Boolean =
    existing.copy(recordId = 0) == incoming.copy(recordId = 0)
