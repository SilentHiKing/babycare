package com.zero.components.base.vm


import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 原始异常可能包含记录内容；页面应使用资源化的通用错误文案。
                _uiState.value = UiState.Error(e)
            }
        }
    }

}
