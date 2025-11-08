package com.zero.components.base

import android.os.Bundle
import android.os.PersistableBundle
import androidx.activity.enableEdgeToEdge
import androidx.annotation.IdRes
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.viewbinding.ViewBinding
import com.lxj.xpopup.XPopup
import com.lxj.xpopup.impl.LoadingPopupView
import com.zero.common.components.util.ActivityCompatHelper
import com.zero.components.base.util.DialogHelper

abstract class BaseActivity<VB : ViewBinding> : BaseBindingActivity<VB>() {

    val loadingPop: LoadingPopupView by lazy {
        DialogHelper.generateLoadingDialog(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
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
}