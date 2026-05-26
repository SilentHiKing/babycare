package com.zero.babycare.home

import com.zero.babycare.home.bean.DashboardData

/**
 * 首页完整渲染状态。
 *
 * Fragment 只消费这个状态并渲染界面，数据查询、当前宝宝变更和刷新触发都收敛到 ViewModel。
 */
sealed interface DashboardUiState {
    data object Loading : DashboardUiState
    data object NoBaby : DashboardUiState

    data class Content(
        val babyId: Int,
        val babyName: String,
        val data: DashboardData,
        val nowMillis: Long
    ) : DashboardUiState

    data class Error(val message: String) : DashboardUiState
}
