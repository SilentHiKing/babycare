package com.zero.components.base.util

import android.content.Context
import com.blankj.utilcode.util.StringUtils
import com.lxj.xpopup.XPopup
import com.lxj.xpopup.core.BasePopupView
import com.lxj.xpopup.impl.LoadingPopupView
import kotlin.math.min

/**
 * 弹窗辅助工具类
 * 提供统一样式的弹窗创建方法，适配手机和平板设备
 */
object DialogHelper {

    /** 弹窗最大宽度（dp） */
    private const val MAX_POPUP_WIDTH_DP = 400

    /** 弹窗占屏幕宽度的最大比例 */
    private const val MAX_POPUP_WIDTH_RATIO = 0.85f

    /**
     * 创建统一样式的 XPopup Builder
     * 自动限制最大宽度，适配手机和平板设备
     * 
     * @param context 上下文
     * @return 配置好宽度的 XPopup.Builder
     */
    fun createBuilder(context: Context): XPopup.Builder {
        val popupWidth = calculatePopupWidth(context)
        return XPopup.Builder(context)
            .maxWidth(popupWidth)
    }

    /**
     * 计算弹窗宽度
     * 取 MAX_POPUP_WIDTH_DP 和 屏幕宽度 * MAX_POPUP_WIDTH_RATIO 的较小值
     */
    fun calculatePopupWidth(context: Context): Int {
        val displayMetrics = context.resources.displayMetrics
        val maxWidthPx = (MAX_POPUP_WIDTH_DP * displayMetrics.density).toInt()
        val screenWidth = displayMetrics.widthPixels
        return min(maxWidthPx, (screenWidth * MAX_POPUP_WIDTH_RATIO).toInt())
    }

    /**
     * 创建加载对话框
     */
    fun createLoadingDialog(context: Context, title: String? = "loading"): LoadingPopupView {
        return XPopup.Builder(context)
            .isViewMode(false)
            .hasShadowBg(true)
            .dismissOnTouchOutside(false)
            .dismissOnBackPressed(false)
            .asLoading(title, LoadingPopupView.Style.Spinner)
    }

    /**
     * 显示确认对话框（自定义样式）
     * 
     * @param context 上下文
     * @param title 标题
     * @param content 内容
     * @param confirmText 确认按钮文字（默认"确定"）
     * @param cancelText 取消按钮文字（默认"取消"）
     * @param onConfirm 确认回调
     * @param onCancel 取消回调（可选）
     * @param isHideCancel 是否隐藏取消按钮（默认 false）
     */
    fun showConfirmDialog(
        context: Context,
        title: String,
        content: String,
        confirmText: String = StringUtils.getString(com.zero.common.R.string.confirm),
        cancelText: String = StringUtils.getString(com.zero.common.R.string.cancel),
        onConfirm: () -> Unit,
        onCancel: (() -> Unit)? = null,
        isHideCancel: Boolean = false
    ): BasePopupView {
        val popup = ConfirmPopup(context)
            .setTitle(title)
            .setContent(content)
            .setConfirmText(confirmText)
            .setCancelText(cancelText)
            .setOnConfirmListener(onConfirm)
            .setHideCancel(isHideCancel)

        onCancel?.let { popup.setOnCancelListener(it) }

        return XPopup.Builder(context)
            .asCustom(popup)
            .show()
    }

    /**
     * 显示简单提示对话框（只有确认按钮）
     */
    fun showAlertDialog(
        context: Context,
        title: String,
        content: String,
        confirmText: String = StringUtils.getString(com.zero.common.R.string.confirm),
        onConfirm: (() -> Unit)? = null
    ): BasePopupView {
        val popup = ConfirmPopup(context)
            .setTitle(title)
            .setContent(content)
            .setConfirmText(confirmText)
            .setHideCancel(true)

        onConfirm?.let { popup.setOnConfirmListener(it) }

        return XPopup.Builder(context)
            .asCustom(popup)
            .show()
    }

    // ============ 兼容旧方法 ============

    @Deprecated(
        message = "Use createLoadingDialog instead",
        replaceWith = ReplaceWith("createLoadingDialog(context, title)")
    )
    fun generateLoadingDialog(context: Context, title: String? = "loading"): LoadingPopupView {
        return createLoadingDialog(context, title)
    }
}


