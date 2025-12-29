package com.zero.components.base

import android.os.Bundle
import androidx.annotation.IdRes
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.viewbinding.ViewBinding
import com.lxj.xpopup.impl.LoadingPopupView
import com.zero.common.theme.ThemeManager
import com.zero.common.util.ActivityCompatHelper
import com.zero.common.util.StatusBarUtil
import com.zero.components.base.util.DialogHelper

abstract class BaseActivity<VB : ViewBinding> : BaseBindingActivity<VB>() {

    val loadingPop: LoadingPopupView by lazy {
        DialogHelper.generateLoadingDialog(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 应用主题 - 必须在 super.onCreate() 之前
        applyThemeIfNeeded()

        super.onCreate(savedInstanceState)
        setupSystemBars()
        initView(savedInstanceState)
        initData(savedInstanceState)

    }

    abstract fun initView(savedInstanceState: Bundle?)

    abstract fun initData(savedInstanceState: Bundle?)

    protected fun showLoading() {
        if (loadingPop.isDismiss && !ActivityCompatHelper.isDestroy(this)) {
            loadingPop.show()
        }
    }

    protected fun dismissLoading() {
        if (!loadingPop.isDismiss) {
            loadingPop.smartDismiss()
        }
    }

    fun addFragmentToView(fragment: Fragment, @IdRes containerViewId: Int) {

        supportFragmentManager.commit {
            setReorderingAllowed(true)
            add(containerViewId, fragment)
            addToBackStack(fragment.javaClass.simpleName)
        }
    }

    /**
     * 应用主题
     * 子类可以覆写此方法来自定义主题应用逻辑
     */
    protected open fun applyThemeIfNeeded() {
        ThemeManager.applyTheme(this)
    }

    protected open fun setupSystemBars() {
        StatusBarUtil.setupForLightPage(this, binding.root)
    }
}
