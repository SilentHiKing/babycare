package com.zero.components.widget

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.updatePadding
import androidx.core.view.updatePaddingRelative
import androidx.recyclerview.widget.LinearLayoutManager
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
 * - 多动作列表：setActions()
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
 * toolbar.setActions(listOf(ToolbarAction(text = "编辑"))) { /* 编辑操作 */ }
 * 
 * // 编辑页：返回 + 标题 + 保存（单动作也使用列表）
 * toolbar.title = "编辑信息"
 * toolbar.showBackButton { onBackPressed() }
 * toolbar.setActions(listOf(ToolbarAction(text = "保存"))) { save() }
 * ```
 */
class BaseToolbar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {

    private val binding = LayoutBaseToolBarBinding.inflate(LayoutInflater.from(context), this)
    private var onMultiActionClick: ((ToolbarAction) -> Unit)? = null
    private val titleBasePaddingStart = binding.tvTitle.paddingStart
    private val titleBasePaddingEnd = binding.tvTitle.paddingEnd
    private var onLeftActionClick: ((ToolbarAction) -> Unit)? = null

    // 右侧多动作列表适配器，统一走 baby_recyclerview 体系
    private val actionAdapter = ToolbarActionAdapter { action ->
        onMultiActionClick?.invoke(action)
    }
    private val leftActionAdapter = ToolbarLeftActionAdapter { action ->
        onLeftActionClick?.invoke(action)
    }

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

        // 初始化左侧动作列表，统一使用 RecyclerView
        binding.rvLeftActions.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = leftActionAdapter
            itemAnimator = null
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
                0 -> { /* back - 默认 */ showBackButton() }
                1 -> { /* menu */ showMenuButton() }
                2 -> { /* none */ hideLeftButton() }
            }
            
            typedArray.recycle()
        }

        // 初始化右侧多动作列表，避免在调用时重复配置
        binding.rvActions.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = actionAdapter
            itemAnimator = null
        }
        requestTitleInsetsUpdate()

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
        val action = ToolbarAction(
            iconRes = com.zero.common.R.drawable.ic_back,
            contentDescription = context.getString(com.zero.common.R.string.back)
        )
        setLeftActions(listOf(action)) { listener?.invoke() }
        requestTitleInsetsUpdate()
    }

    /**
     * 显示菜单按钮
     */
    fun showMenuButton(listener: (() -> Unit)? = null) {
        val action = ToolbarAction(
            iconRes = com.zero.common.R.drawable.ic_menu,
            contentDescription = context.getString(com.zero.common.R.string.menu)
        )
        setLeftActions(listOf(action)) { listener?.invoke() }
        requestTitleInsetsUpdate()
    }

    /**
     * 隐藏左侧按钮
     */
    fun hideLeftButton() {
        binding.rvLeftActions.visibility = View.GONE
        onLeftActionClick = null
        leftActionAdapter.submitList(emptyList())
        requestTitleInsetsUpdate()
    }

    /**
     * 设置返回按钮点击事件
     */
    fun setOnBackListener(listener: () -> Unit) {
        onLeftActionClick = { action ->
            if (action.iconRes == com.zero.common.R.drawable.ic_back) {
                listener()
            }
        }
    }

    // ==================== 右侧操作区 ====================

    /**
     * 设置右侧多动作列表（支持图标/文字/图标+文字）
     */
    fun setActions(actions: List<ToolbarAction>, listener: ((ToolbarAction) -> Unit)? = null) {
        // 为空时直接隐藏右侧区域，避免空占位
        if (actions.isEmpty()) {
            hideAction()
            return
        }
        onMultiActionClick = listener
        binding.rvActions.visibility = View.VISIBLE
        actionAdapter.submitList(actions)
        requestTitleInsetsUpdate()
    }

    /**
     * 隐藏右侧操作区
     */
    fun hideAction() {
        binding.rvActions.visibility = View.GONE
        requestTitleInsetsUpdate()
    }

    private fun setLeftActions(
        actions: List<ToolbarAction>,
        listener: ((ToolbarAction) -> Unit)? = null
    ) {
        if (actions.isEmpty()) {
            hideLeftButton()
            return
        }
        onLeftActionClick = listener
        binding.rvLeftActions.visibility = View.VISIBLE
        leftActionAdapter.submitList(actions)
        requestTitleInsetsUpdate()
    }

    private fun requestTitleInsetsUpdate() {
        doOnLayout {
            updateTitleInsets()
        }
    }

    private fun updateTitleInsets() {
        // 为了保证标题视觉居中，用左右区域的最大宽度做对称内边距
        val leftWidth = if (binding.rvLeftActions.visibility == View.VISIBLE) {
            binding.rvLeftActions.width
        } else {
            0
        }
        val rightWidth = if (binding.rvActions.visibility == View.VISIBLE) {
            binding.llRightActions.width
        } else {
            0
        }
        val reserved = maxOf(leftWidth, rightWidth)
        binding.tvTitle.updatePaddingRelative(
            start = titleBasePaddingStart + reserved,
            end = titleBasePaddingEnd + reserved
        )
    }
}
