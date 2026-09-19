package com.zero.components.viewbinding

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.databinding.ViewDataBinding
import androidx.fragment.app.Fragment
import androidx.viewbinding.ViewBinding

interface FragmentBinding<VB : ViewBinding> {
    val binding: VB
    fun Fragment.createViewWithBinding(inflater: LayoutInflater, container: ViewGroup?): View
    fun clearBinding()
}

class FragmentBindingDelegate<VB : ViewBinding> : FragmentBinding<VB> {
    private var _binding: VB? = null

    override val binding: VB
        get() = requireNotNull(_binding) { "The property of binding has been destroyed." }

    override fun Fragment.createViewWithBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ): View {
        // 每次创建 View 都生成新的绑定，不复用上一轮已销毁的视图树。
        val newBinding = ViewBindingUtil.inflateWithGeneric<VB>(this, inflater, container, false)
        _binding = newBinding
        return newBinding.root
    }

    override fun clearBinding() {
        (_binding as? ViewDataBinding)?.unbind()
        _binding = null
    }
}
