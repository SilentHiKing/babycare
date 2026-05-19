package com.zero.components.base.util

import android.content.Context
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import com.lxj.xpopup.core.BottomPopupView

/**
 * BabyCare 选择类 BottomSheet 的公共交互基类。
 *
 * XPopup 默认允许整张 Sheet 响应下拉关闭，但选择器内容区本身也需要纵向滚动。
 * SmartDragLayout.enableDrag 同时影响布局模式，运行时切换会让已打开的 Sheet 保留 scrollY 后重新布局上移。
 * 这里仅阻止父层拦截内容区触摸，不改变 XPopup 的拖拽布局状态。
 */
internal abstract class BabyCareBottomSheetPopup(context: Context) : BottomPopupView(context) {

    protected fun protectSheetDragForContentTouch(event: MotionEvent, contentView: View) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val isContentTouch = contentView.containsRawPoint(event.rawX, event.rawY)
                contentView.parent?.requestDisallowInterceptTouchEvent(isContentTouch)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                contentView.parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
    }

    private fun View.containsRawPoint(rawX: Float, rawY: Float): Boolean {
        val rect = Rect()
        return isShown && getGlobalVisibleRect(rect) && rect.contains(rawX.toInt(), rawY.toInt())
    }
}
