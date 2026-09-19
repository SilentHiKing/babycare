package com.zero.components.base.vm

import android.content.Context
import androidx.annotation.StringRes

// UiState.kt
sealed interface UiState<out T> {
    data object None : UiState<Nothing>
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T?) : UiState<T>
    data class Error(
        val throwable: Throwable,
        val message: String? = null,
        @param:StringRes val messageRes: Int? = null
    ) : UiState<Nothing> {
        /** 可重放的错误延迟到 View 渲染时取资源，隐藏期间切换语言也不会播出旧文案。 */
        fun messageFor(context: Context): String? = messageRes?.let(context::getString) ?: message
    }
}
