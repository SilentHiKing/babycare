package com.zero.components.base

import android.os.Bundle
import android.view.View
import androidx.viewbinding.ViewBinding
import com.lxj.xpopup.core.BasePopupView
import com.lxj.xpopup.impl.LoadingPopupView
import com.zero.components.base.util.DialogHelper

open class BaseFragment<VB : ViewBinding> : BaseBindingFragment<VB>() {
    private var loadingPop: BasePopupView? = null
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView(view, savedInstanceState)
        initData(view, savedInstanceState)
    }

    open fun initData(view: View, savedInstanceState: Bundle?) {

    }

    open fun initView(view: View, savedInstanceState: Bundle?) {

    }

    protected fun showLoading(title: String? = "loading") {
        if (loadingPop == null) {
            loadingPop = DialogHelper.generateLoadingDialog(requireContext(), title)
        }

        if (loadingPop?.isShow != true) {
            loadingPop?.show()
        }
    }

    protected fun hideLoading() {
        loadingPop?.takeIf { it.isShow }?.dismiss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        loadingPop?.dismiss()
        loadingPop = null
    }
}