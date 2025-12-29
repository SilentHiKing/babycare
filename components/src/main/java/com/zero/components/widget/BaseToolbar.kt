package com.zero.components.widget

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import androidx.annotation.DrawableRes
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.zero.components.R
import com.zero.components.databinding.LayoutBaseToolBarBinding

/**
 * 通用工具栏组件
 * 
 * ## 结构
 * ```
 * [左侧按钮] [     标题     ] [右侧操作区]
 * ```
 * 
 * ## 左侧按钮
 * - 返回按钮（默认显示）：showBackButton()
 * - 菜单按钮：showMenuButton()
 * - 隐藏左侧按钮：hideLeftButton()
 * 
 * ## 右侧操作区
 * - 文字按钮：setActionText() + setOnActionClickListener()
 * - 图标按钮：setActionIcon() + setOnActionClickListener()
 * - 隐藏右侧按钮：hideAction()
 * 
 * ## 使用示例
 * ```kotlin
 * // 简单页面：返回 + 标题
 * toolbar.title = "页面标题"
 * toolbar.showBackButton { finish() }
 * 
 * // 首页：菜单 + 标题 + 文字按钮
 * toolbar.title = "首页"
 * toolbar.showMenuButton { openDrawer() }
 * toolbar.setActionText("编辑") { /* 编辑操作 */ }
 * 
 * // 编辑页：返回 + 标题 + 保存
 * toolbar.title = "编辑信息"
 * toolbar.showBackButton { onBackPressed() }
 * toolbar.setActionText("保存") { save() }
 * ```
 */
class BaseToolbar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {

    private val binding = LayoutBaseToolBarBinding.inflate(LayoutInflater.from(context), this)

    /** 标题文字 */
    var title: String?
        get() = binding.tvTitle.text.toString()
        set(value) {
            binding.tvTitle.text = value
        }

    init {
        // 设置默认背景
        if (background == null) {
            setBackgroundResource(R.drawable.bg_toolbar)
        }
        
        // 解析自定义属性
        attrs?.let {
            val typedArray = context.obtainStyledAttributes(it, R.styleable.BaseToolbar)
            
            // 标题
            typedArray.getString(R.styleable.BaseToolbar_toolbarTitle)?.let { title ->
                this.title = title
            }
            
            // 左侧按钮类型
            val leftButtonType = typedArray.getInt(R.styleable.BaseToolbar_leftButtonType, 0)
            when (leftButtonType) {
                0 -> { /* back - 默认 */ }
                1 -> { /* menu */ binding.ivBack.visibility = View.GONE; binding.ivMenu.visibility = View.VISIBLE }
                2 -> { /* none */ binding.ivBack.visibility = View.GONE }
            }
            
            // 右侧文字
            typedArray.getString(R.styleable.BaseToolbar_actionText)?.let { text ->
                setActionText(text)
            }
            
            typedArray.recycle()
        }

        val initialPaddingTop = paddingTop
        val initialPaddingBottom = paddingBottom
        val initialPaddingLeft = paddingLeft
        val initialPaddingRight = paddingRight
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            view.updatePadding(
                left = initialPaddingLeft,
                top = initialPaddingTop + statusBars.top,
                right = initialPaddingRight,
                bottom = initialPaddingBottom
            )
            insets
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ViewCompat.requestApplyInsets(this)
    }

    // ==================== 左侧按钮 ====================

    /**
     * 显示返回按钮
     */
    fun showBackButton(listener: (() -> Unit)? = null) {
        binding.ivMenu.visibility = View.GONE
        binding.ivBack.visibility = View.VISIBLE
        listener?.let { binding.ivBack.setOnClickListener { it() } }
    }

    /**
     * 显示菜单按钮
     */
    fun showMenuButton(listener: (() -> Unit)? = null) {
        binding.ivBack.visibility = View.GONE
        binding.ivMenu.visibility = View.VISIBLE
        listener?.let { binding.ivMenu.setOnClickListener { it() } }
    }

    /**
     * 隐藏左侧按钮
     */
    fun hideLeftButton() {
        binding.ivBack.visibility = View.GONE
        binding.ivMenu.visibility = View.GONE
    }

    /**
     * 设置返回按钮点击事件
     */
    fun setOnBackListener(listener: () -> Unit) {
        binding.ivBack.setOnClickListener { listener() }
    }

    // ==================== 右侧操作区 ====================

    /**
     * 设置右侧文字按钮
     */
    fun setActionText(text: String, listener: (() -> Unit)? = null) {
        binding.tvAction.text = text
        binding.tvAction.visibility = View.VISIBLE
        binding.ivAction.visibility = View.GONE
        listener?.let { binding.tvAction.setOnClickListener { it() } }
    }

    /**
     * 设置右侧图标按钮
     */
    fun setActionIcon(@DrawableRes iconRes: Int, listener: (() -> Unit)? = null) {
        binding.ivAction.setImageResource(iconRes)
        binding.ivAction.visibility = View.VISIBLE
        binding.tvAction.visibility = View.GONE
        listener?.let { binding.ivAction.setOnClickListener { it() } }
    }

    /**
     * 设置右侧按钮点击事件
     */
    fun setOnActionClickListener(listener: () -> Unit) {
        binding.tvAction.setOnClickListener { listener() }
        binding.ivAction.setOnClickListener { listener() }
    }

    /**
     * 隐藏右侧操作区
     */
    fun hideAction() {
        binding.tvAction.visibility = View.GONE
        binding.ivAction.visibility = View.GONE
    }

    /**
     * 获取右侧文字按钮的文字
     */
    fun getActionText(): String = binding.tvAction.text.toString()

    // ==================== 兼容旧 API ====================

    @Deprecated("Use setActionText() instead", ReplaceWith("setActionText(text)"))
    fun setFinishText(text: String) {
        setActionText(text)
    }

    @Deprecated("Use setOnActionClickListener() instead", ReplaceWith("setOnActionClickListener(listener)"))
    fun setOnFinishListener(listener: () -> Unit) {
        setOnActionClickListener(listener)
    }

    @Deprecated("Use hideAction() instead", ReplaceWith("hideAction()"))
    fun hideFinishButton() {
        hideAction()
    }
}
