package com.zero.components.base.vm


import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

abstract class BaseViewModel : ViewModel() {

    private val _uiState = MutableStateFlow<UiState<Any>>(UiState.None)
    val uiState: StateFlow<UiState<Any>> = _uiState

    open fun <T> safeLaunch(block: suspend () -> T) {
        _uiState.value = UiState.Loading
        viewModelScope.launch {
            try {
                val result = block()
                _uiState.value = UiState.Success(result)
            } catch (e: Exception) {
                val message = e.message ?: "Unknown error"
                _uiState.value = UiState.Error(e, message)
            }
        }
    }

}