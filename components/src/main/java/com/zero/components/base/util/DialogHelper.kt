package com.zero.components.base.util

import android.content.Context
import com.lxj.xpopup.XPopup
import com.lxj.xpopup.core.BasePopupView
import com.lxj.xpopup.impl.LoadingPopupView


object DialogHelper {

    fun generateLoadingDialog(context: Context, title: String? = "loading"): LoadingPopupView {
        return XPopup.Builder(context)
            .isViewMode(false)
            .hasShadowBg(true)
            .dismissOnTouchOutside(false)
            .dismissOnBackPressed(false)
            .asLoading(title,LoadingPopupView.Style.Spinner)
    }

}


