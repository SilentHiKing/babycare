package com.zero.components.viewbinding

import android.app.Activity
import androidx.viewbinding.ViewBinding

interface ActivityBinding<VB : ViewBinding> {
    val binding: VB
    fun Activity.setContentViewWithBinding()
}

class ActivityBindingDelegate<VB : ViewBinding> : ActivityBinding<VB> {
    private lateinit var _binding: VB

    override val binding: VB get() = _binding

    override fun Activity.setContentViewWithBinding() {
        _binding = ViewBindingUtil.inflateWithGeneric(this, layoutInflater)
        setContentView(binding.root)
    }
}