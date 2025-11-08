package com.zero.components.base.vm

// UiState.kt
sealed interface UiState<out T> {
    data object None : UiState<Nothing>
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T?) : UiState<T>
    data class Error(val throwable: Throwable, val message: String? = null) : UiState<Nothing>
}