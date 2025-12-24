package com.zero.components.base.util

import android.content.Context
import android.view.View
import android.widget.TextView
import com.blankj.utilcode.util.StringUtils
import com.lxj.xpopup.core.CenterPopupView
import com.zero.components.R

/**
 * 自定义确认弹窗
 * 统一样式，适配手机和平板设备
 */
class ConfirmPopup(context: Context) : CenterPopupView(context) {

    private var title: String = ""
    private var content: String = ""
    private var cancelText: String = StringUtils.getString(com.zero.common.R.string.cancel)
    private var confirmText: String = StringUtils.getString(com.zero.common.R.string.confirm)
    private var onConfirmListener: (() -> Unit)? = null
    private var onCancelListener: (() -> Unit)? = null
    private var hideCancel: Boolean = false

    override fun getImplLayoutId(): Int = R.layout.popup_confirm_dialog

    override fun getMaxWidth(): Int {
        // 最大宽度 400dp，适配手机和平板
        val maxWidthPx = (400 * resources.displayMetrics.density).toInt()
        val screenWidth = resources.displayMetrics.widthPixels
        return minOf(maxWidthPx, (screenWidth * 0.85).toInt())
    }

    override fun onCreate() {
        super.onCreate()

        val tvTitle = findViewById<TextView>(R.id.tv_title)
        val tvContent = findViewById<TextView>(R.id.tv_content)
        val tvCancel = findViewById<TextView>(R.id.tv_cancel)
        val tvConfirm = findViewById<TextView>(R.id.tv_confirm)
        val vLineVertical = findViewById<View>(R.id.v_line_vertical)

        // 设置标题
        if (title.isNotEmpty()) {
            tvTitle.text = title
            tvTitle.visibility = View.VISIBLE
        } else {
            tvTitle.visibility = View.GONE
        }

        // 设置内容
        tvContent.text = content

        // 设置按钮文字
        tvCancel.text = cancelText
        tvConfirm.text = confirmText

        // 处理隐藏取消按钮
        if (hideCancel) {
            tvCancel.visibility = View.GONE
            vLineVertical.visibility = View.GONE
        } else {
            tvCancel.visibility = View.VISIBLE
            vLineVertical.visibility = View.VISIBLE
        }

        // 取消按钮点击事件
        tvCancel.setOnClickListener {
            onCancelListener?.invoke()
            dismiss()
        }

        // 确认按钮点击事件
        tvConfirm.setOnClickListener {
            onConfirmListener?.invoke()
            dismiss()
        }
    }

    /**
     * 设置标题
     */
    fun setTitle(title: String): ConfirmPopup {
        this.title = title
        return this
    }

    /**
     * 设置内容
     */
    fun setContent(content: String): ConfirmPopup {
        this.content = content
        return this
    }

    /**
     * 设置取消按钮文字
     */
    fun setCancelText(text: String): ConfirmPopup {
        this.cancelText = text
        return this
    }

    /**
     * 设置确认按钮文字
     */
    fun setConfirmText(text: String): ConfirmPopup {
        this.confirmText = text
        return this
    }

    /**
     * 设置确认回调
     */
    fun setOnConfirmListener(listener: () -> Unit): ConfirmPopup {
        this.onConfirmListener = listener
        return this
    }

    /**
     * 设置取消回调
     */
    fun setOnCancelListener(listener: () -> Unit): ConfirmPopup {
        this.onCancelListener = listener
        return this
    }

    /**
     * 设置是否隐藏取消按钮
     */
    fun setHideCancel(hide: Boolean): ConfirmPopup {
        this.hideCancel = hide
        return this
    }
}

