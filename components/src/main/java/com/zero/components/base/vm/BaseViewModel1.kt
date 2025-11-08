package com.zero.components.base.vm


// BaseViewModel.kt（支持参数版）
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

abstract class BaseViewModel1<T, R, A> : ViewModel() {

    private val _uiState = MutableStateFlow<UiState<T>>(UiState.Loading)
    val uiState: StateFlow<UiState<T>> = _uiState

    // 保存当前加载参数（可用于刷新）
    private var currentArg: A? = null



    protected abstract val repository: R

    // 带参加载
    protected abstract suspend fun R.fetchData(arg: A): T?

    // 可选：刷新时是否使用相同参数
    protected open suspend fun R.refreshData(arg: A): T? = fetchData(arg)

    // 公共加载方法：供外部（如 UI）调用
    fun load(arg: A) {
        currentArg = arg
        safeLaunch {
            _uiState.value = UiState.Loading
            val result = repository.fetchData(arg)
            _uiState.value = UiState.Success(result)
        }
    }

    // 刷新：使用上次的参数
    fun refresh() {
        currentArg?.let { arg ->
            safeLaunch {
                _uiState.value = UiState.Loading
                val result = repository.refreshData(arg)
                _uiState.value = UiState.Success(result)
            }
        }
        // 如果 currentArg 为 null，可抛异常或忽略
    }

    private fun safeLaunch(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                val message = e.message ?: "Unknown error"
                _uiState.value = UiState.Error(e, message)
            }
        }
    }

    protected fun updateUiState(transform: (UiState<T>) -> UiState<T>) {
        _uiState.update(transform)
    }
}