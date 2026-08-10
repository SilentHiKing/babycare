package com.zero.components.widget

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.StringRes
import com.zero.components.databinding.ViewPageStateBinding

/**
 * 页面级加载与可恢复状态容器。
 *
 * 组件只负责统一渲染，不判断业务状态；页面仍按 MVVM 状态决定何时显示加载、错误或空态。
 */
class PageStateView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val binding = ViewPageStateBinding.inflate(LayoutInflater.from(context), this, true)

    init {
        visibility = View.GONE
    }

    fun showLoading(@StringRes messageResId: Int) {
        visibility = View.VISIBLE
        binding.progressState.visibility = View.VISIBLE
        binding.tvStateMessage.setText(messageResId)
        binding.tvStateAction.visibility = View.GONE
        binding.tvStateAction.setOnClickListener(null)
    }

    fun showMessage(
        message: CharSequence,
        actionText: CharSequence? = null,
        onAction: (() -> Unit)? = null
    ) {
        visibility = View.VISIBLE
        binding.progressState.visibility = View.GONE
        binding.tvStateMessage.text = message

        val showAction = !actionText.isNullOrBlank() && onAction != null
        binding.tvStateAction.visibility = if (showAction) View.VISIBLE else View.GONE
        binding.tvStateAction.text = actionText
        binding.tvStateAction.setOnClickListener(if (showAction) View.OnClickListener { onAction?.invoke() } else null)
    }

    fun hide() {
        visibility = View.GONE
        binding.tvStateAction.setOnClickListener(null)
    }
}
