package com.zero.common.util


import android.app.Activity
import android.view.View
import android.view.Window
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.zero.common.ext.getThemeColor

object StatusBarUtil {

    /**
     * Material3 + Edge-to-Edge + 浅色页面：
     * - 状态栏透明
     * - 内容延伸到状态栏下
     * - 状态栏图标/文字：黑色（浅色背景适配）
     */
    fun setupForLightPage(activity: Activity, rootView: View?) {
        setupForLightPage(activity.window, rootView)
    }

    fun setupForLightPage(window: Window, rootView: View?) {
        // 1) Edge-to-Edge：让内容绘制到系统栏区域
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // 2) 状态栏颜色
        val statusBarColor = window.context.getThemeColor(com.zero.common.R.attr.primary_bg_default)
        window.statusBarColor = statusBarColor

        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = ColorUtils.calculateLuminance(statusBarColor) > 0.5
        }

        // 4) 给根布局吃掉状态栏 Insets（只加一次，不重复叠加）
        rootView?.applyTopInsetPaddingOnce(WindowInsetsCompat.Type.statusBars())

        // （可选）如果你的底部也需要避开手势条/导航栏
        // rootView.applyBottomInsetPaddingOnce(WindowInsetsCompat.Type.navigationBars())
    }

    /**
     * 给 View 的 paddingTop 加上指定类型 insets（只加一次）。
     * 适合 rootView / AppBar / Toolbar 容器。
     */
    private fun View.applyTopInsetPaddingOnce(@WindowInsetsCompat.Type.InsetsType type: Int) {
        val initial = PaddingState(paddingLeft, paddingTop, paddingRight, paddingBottom)

        ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
            val top = insets.getInsets(type).top
            v.setPadding(initial.left, initial.top + top, initial.right, initial.bottom)
            insets
        }
        // 触发一次 insets 分发
        requestApplyInsetsWhenAttached()
    }

    private fun View.applyBottomInsetPaddingOnce(@WindowInsetsCompat.Type.InsetsType type: Int) {
        val initial = PaddingState(paddingLeft, paddingTop, paddingRight, paddingBottom)

        ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
            val bottom = insets.getInsets(type).bottom
            v.setPadding(initial.left, initial.top, initial.right, initial.bottom + bottom)
            insets
        }
        requestApplyInsetsWhenAttached()
    }

    private data class PaddingState(val left: Int, val top: Int, val right: Int, val bottom: Int)

    private fun View.requestApplyInsetsWhenAttached() {
        if (isAttachedToWindow) {
            ViewCompat.requestApplyInsets(this)
        } else {
            addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    v.removeOnAttachStateChangeListener(this)
                    ViewCompat.requestApplyInsets(v)
                }
                override fun onViewDetachedFromWindow(v: View) = Unit
            })
        }
    }

}
