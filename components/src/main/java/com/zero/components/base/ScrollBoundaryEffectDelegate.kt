package com.zero.components.base

import android.view.View
import android.view.ViewGroup

/**
 * 统一关闭页面滚动到边界时的 EdgeEffect。
 *
 * Android 12 以后默认是拉伸回弹，旧系统是边缘发光；这两种到头反馈在 BabyCare 的
 * 记录和统计页面里会制造额外视觉噪音，因此在 Fragment 根视图上递归关闭。
 */
object ScrollBoundaryEffectDelegate {

    fun applyTo(root: View) {
        root.disableBoundaryEffectRecursively()
        // 部分页面会在 initView 中同步补充子 View，post 一次可覆盖首帧前完成的动态布局。
        root.post {
            root.disableBoundaryEffectRecursively()
        }
    }

    private fun View.disableBoundaryEffectRecursively() {
        overScrollMode = View.OVER_SCROLL_NEVER
        if (this !is ViewGroup) return

        for (index in 0 until childCount) {
            getChildAt(index).disableBoundaryEffectRecursively()
        }
    }
}
