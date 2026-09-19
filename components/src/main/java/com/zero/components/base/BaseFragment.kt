package com.zero.components.base

import android.os.Bundle
import android.view.View
import androidx.viewbinding.ViewBinding
import com.lxj.xpopup.core.BasePopupView
import com.lxj.xpopup.impl.LoadingPopupView
import com.zero.components.base.util.DialogHelper
import com.zero.common.ext.launchInLifecycle
import kotlinx.coroutines.Job

open class BaseFragment<VB : ViewBinding> : BaseBindingFragment<VB>() {
    private var loadingPop: BasePopupView? = null
    private var keyboardSafeScrollDelegate: KeyboardSafeScrollDelegate? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ScrollBoundaryEffectDelegate.applyTo(view)
        keyboardSafeScrollDelegate = KeyboardSafeScrollDelegate.install(view)
        initView(view, savedInstanceState)
        initData(view, savedInstanceState)
    }
    open fun initView(view: View, savedInstanceState: Bundle?) {

    }

    open fun initData(view: View, savedInstanceState: Bundle?) {

    }

    /**
     * 页面订阅随 View 销毁取消，避免 Fragment 保留时旧订阅在新 View 上重复渲染。
     * Activity 继续使用自身生命周期；所有继承本类的页面默认使用此入口。
     */
    protected fun launchInLifecycle(block: suspend () -> Unit): Job =
        viewLifecycleOwner.launchInLifecycle(block = block)



    protected fun showLoading(title: String? = null) {
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
        keyboardSafeScrollDelegate?.dispose()
        keyboardSafeScrollDelegate = null
        super.onDestroyView()
        loadingPop?.dismiss()
        loadingPop = null
    }
}
