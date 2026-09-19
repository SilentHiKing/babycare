package com.zero.components.base.util

import android.content.Context
import android.view.KeyEvent
import com.lxj.xpopup.impl.LoadingPopupView

/** 保留库的加载样式，只统一新旧返回链与真实键盘判断。 */
internal class BabyCareLoadingPopup(context: Context) : LoadingPopupView(context, 0) {
    override fun processKeyEvent(keyCode: Int, event: KeyEvent): Boolean =
        isCanceledBackEvent(keyCode, event) || super.processKeyEvent(keyCode, event)

    override fun dismissOrHideSoftInput() = dismissOrHideSystemIme()
}
