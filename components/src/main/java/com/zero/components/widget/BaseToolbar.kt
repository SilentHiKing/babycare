package com.zero.components.widget

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import androidx.constraintlayout.widget.ConstraintLayout
import com.blankj.utilcode.util.SizeUtils
import com.zero.components.databinding.LayoutBaseToolBarBinding

class BaseToolbar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {

    private val binding = LayoutBaseToolBarBinding.inflate(LayoutInflater.from(context), this)

    var title: String?
        get() = binding.tvTitle.text.toString()
        set(value) {
            binding.tvTitle.text = value
        }

    init {
        elevation = SizeUtils.dp2px(1f).toFloat()
    }


    fun setOnBackListener(listener: OnClickListener) {
        binding.ivBack.setOnClickListener(listener)
    }

    fun setOnFinishListener(listener: OnClickListener) {
        binding.tvFinish.setOnClickListener(listener)
    }


}