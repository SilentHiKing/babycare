package com.zero.common.util


import android.app.Activity
import android.view.View
import android.view.Window
import android.view.WindowManager
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
        @Suppress("DEPRECATION")
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        // 2) 状态栏颜色
        val statusBarColor = window.context.getThemeColor(com.zero.common.R.attr.primary_bg_default)
        @Suppress("DEPRECATION")
        window.statusBarColor = statusBarColor

        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = ColorUtils.calculateLuminance(statusBarColor) > 0.5
        }

        // 4) 根布局统一处理状态栏与键盘 Insets，固定底部按钮和表单内容都跟随可用区域移动。
        rootView?.applySystemBarsAndImePaddingOnce()
    }

    /**
     * 给根 View 加上系统栏与 IME padding（只基于初始 padding 计算，避免重复叠加）。
     * Edge-to-Edge 模式下 adjustResize 不是所有系统版本都会自动收缩内容区，因此这里显式使用 IME inset。
     */
    private fun View.applySystemBarsAndImePaddingOnce() {
        val initial = PaddingState(paddingLeft, paddingTop, paddingRight, paddingBottom)

        ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navigationBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bottom = ime.bottom.coerceAtLeast(navigationBars.bottom)
            v.setPadding(
                initial.left,
                initial.top + statusBars.top,
                initial.right,
                initial.bottom + bottom
            )
            insets
        }
        // 触发一次 insets 分发
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
