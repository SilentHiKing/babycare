package com.zero.babycare.statistics.model

/**
 * 结构图总览
 */
data class StructureOverview(
    val sections: List<StructureSection>
)

/**
 * 单个结构分组（如喂养结构、排泄结构）
 */
data class StructureSection(
    val titleResId: Int,
    val items: List<StructureItem>
)

/**
 * 结构分组中的单项
 */
data class StructureItem(
    val labelResId: Int,
    val count: Int,
    val colorResId: Int
)
