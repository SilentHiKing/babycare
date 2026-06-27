package com.zero.components.widget

import androidx.annotation.DrawableRes

/**
 * 工具栏右侧动作数据模型
 */
data class ToolbarAction(
    val text: String? = null,
    @param:DrawableRes val iconRes: Int? = null,
    val contentDescription: String? = null,
    val enabled: Boolean = true
)
