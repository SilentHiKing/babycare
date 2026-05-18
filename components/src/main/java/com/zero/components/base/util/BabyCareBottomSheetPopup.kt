package com.zero.components.base.util

import android.content.Context
import android.graphics.Rect
import android.util.Log
import android.view.MotionEvent
import android.view.View
import com.lxj.xpopup.core.BottomPopupView

/**
 * BabyCare 选择类 BottomSheet 的公共交互基类。
 *
 * XPopup 默认允许整张 Sheet 响应下拉关闭，但选择器内容区本身也需要纵向滚动。
 * 这里按触摸落点临时关闭内容区的 Sheet 拖拽，让列表滚动和弹框关闭各自只在自己的区域生效。
 */
internal abstract class BabyCareBottomSheetPopup(context: Context) : BottomPopupView(context) {

    override fun onKeyboardHeightChange(height: Int) {
        super.onKeyboardHeightChange(height)
        if (height > 0) {
            // 选择器不展示软键盘，记录非零高度便于定位系统布局回调误判导致的弹框位移问题。
            Log.d(TAG, "Unexpected keyboard height for picker bottom sheet: height=$height")
        }
    }

    protected fun updateSheetDragEnabledForContentTouch(event: MotionEvent, contentView: View) {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            bottomPopupContainer.enableDrag(!contentView.containsRawPoint(event.rawX, event.rawY))
        }
    }

    protected fun restoreSheetDragAfterContentTouch(event: MotionEvent) {
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            bottomPopupContainer.enableDrag(true)
        }
    }

    private fun View.containsRawPoint(rawX: Float, rawY: Float): Boolean {
        val rect = Rect()
        return isShown && getGlobalVisibleRect(rect) && rect.contains(rawX.toInt(), rawY.toInt())
    }

    private companion object {
        private const val TAG = "BabyCarePickerSheet"
    }
}
