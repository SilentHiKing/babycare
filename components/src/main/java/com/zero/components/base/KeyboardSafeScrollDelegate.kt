package com.zero.components.base

import android.graphics.Rect
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.EditText
import android.widget.ScrollView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import java.util.Collections
import java.util.WeakHashMap

internal class KeyboardSafeScrollDelegate private constructor(
    private val root: View
) {
    private val revealGapPx = (root.resources.displayMetrics.density * REVEAL_GAP_DP).toInt()
    private val attachedEditTexts = Collections.newSetFromMap(WeakHashMap<EditText, Boolean>())
    private val scrollPaddingStates = WeakHashMap<View, ScrollPaddingState>()
    private var treeObserver: ViewTreeObserver? = null
    private var lastStableWindowBottom = 0

    private val focusChangeListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
        val editText = newFocus as? EditText ?: return@OnGlobalFocusChangeListener
        if (!root.containsDescendant(editText)) return@OnGlobalFocusChangeListener
        scheduleReveal(editText, "focus")
    }

    private val layoutChangeListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        attachEditTextTouchListeners(root)
        revealFocusedEditText()
    }

    private val touchRevealListener = View.OnTouchListener { touchedView, event ->
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val editText = touchedView as? EditText ?: return@OnTouchListener false
            scheduleReveal(editText, "touch")
        }
        false
    }

    fun dispose() {
        treeObserver?.takeIf { it.isAlive }?.removeOnGlobalFocusChangeListener(focusChangeListener)
        restoreKeyboardScrollPadding()
        attachedEditTexts.forEach { editText ->
            editText.setOnTouchListener(null)
        }
        attachedEditTexts.clear()
        root.removeOnLayoutChangeListener(layoutChangeListener)
        ViewCompat.setOnApplyWindowInsetsListener(root, null)
        treeObserver = null
    }

    private fun start() {
        attachEditTextTouchListeners(root)
        treeObserver = root.viewTreeObserver.also {
            it.addOnGlobalFocusChangeListener(focusChangeListener)
        }
        root.addOnLayoutChangeListener(layoutChangeListener)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            if (insets.isVisible(WindowInsetsCompat.Type.ime())) {
                revealFocusedEditText()
            } else {
                restoreKeyboardScrollPadding()
            }
            insets
        }
        requestApplyInsetsWhenAttached()
    }

    private fun attachEditTextTouchListeners(view: View) {
        when (view) {
            is EditText -> {
                if (attachedEditTexts.add(view)) {
                    view.setOnTouchListener(touchRevealListener)
                }
            }
            is ViewGroup -> {
                for (index in 0 until view.childCount) {
                    attachEditTextTouchListeners(view.getChildAt(index))
                }
            }
        }
    }

    private fun scheduleReveal(editText: EditText, reason: String) {
        // 键盘动画和布局重算存在时序差异，立即滚一次并在动画后补一次，保证低端机也能露出焦点输入框。
        root.post { revealEditText(editText, reason) }
        root.postDelayed({ revealEditText(editText, "$reason-delayed") }, KEYBOARD_ANIMATION_DELAY_MS)
    }

    private fun revealFocusedEditText() {
        val editText = root.findFocus() as? EditText ?: return
        scheduleReveal(editText, "focused")
    }

    private fun revealEditText(editText: EditText, reason: String) {
        if (!root.containsDescendant(editText) ||
            !root.hasUsableGlobalRect() ||
            !editText.isAttachedToWindow ||
            !editText.isShown
        ) {
            return
        }

        val scrollParent = editText.findNearestVerticalScrollParent()
        if (scrollParent == null) {
            logReveal(reason, editText, null, null, 0)
            editText.requestRectangleOnScreen(Rect(0, 0, editText.width, editText.height), true)
            return
        }

        applyKeyboardScrollPadding(scrollParent)
        val deltaY = calculateScrollDelta(scrollParent, editText, reason)
        if (deltaY == 0) return

        scrollParent.scrollTo(0, scrollParent.scrollY + deltaY)
    }

    private fun calculateScrollDelta(scrollParent: View, editText: EditText, reason: String): Int {
        val rootRect = Rect()
        if (!root.getGlobalVisibleRect(rootRect) || rootRect.isEmpty) {
            logReveal(reason, editText, scrollParent, null, 0)
            return 0
        }

        val scrollRect = Rect()
        if (!scrollParent.getGlobalVisibleRect(scrollRect) || scrollRect.isEmpty) {
            logReveal(reason, editText, scrollParent, null, 0)
            return 0
        }

        val inputRect = Rect(0, 0, editText.width, editText.height)
        (scrollParent as ViewGroup).offsetDescendantRectToMyCoords(editText, inputRect)

        val safeTopScreen = scrollRect.top + revealGapPx
        val safeBottomScreen = scrollRect.bottom.coerceAtMost(resolveKeyboardSafeBottom(rootRect)) - revealGapPx
        val safeTop = scrollParent.scrollY + (safeTopScreen - scrollRect.top)
        val safeBottom = scrollParent.scrollY + (safeBottomScreen - scrollRect.top)
        val deltaY = when {
            inputRect.bottom > safeBottom -> inputRect.bottom - safeBottom
            inputRect.top < safeTop -> inputRect.top - safeTop
            else -> 0
        }
        logReveal(reason, editText, scrollParent, RevealGeometry(inputRect, scrollRect, safeTop, safeBottom), deltaY)
        return deltaY
    }

    private fun resolveKeyboardSafeBottom(rootRect: Rect): Int {
        val insets = ViewCompat.getRootWindowInsets(root)
        val imeVisible = insets?.isVisible(WindowInsetsCompat.Type.ime()) == true
        val navigationBottom = insets?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
        val imeBottom = insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
        val visibleFrame = Rect().also { root.getWindowVisibleDisplayFrame(it) }

        if (!imeVisible && imeBottom == 0) {
            // 记录键盘未显示时的完整窗口底部。adjustResize 生效后 rootRect.bottom 已经是键盘上沿，不能再减 IME 高度。
            lastStableWindowBottom = maxOf(lastStableWindowBottom, rootRect.bottom, visibleFrame.bottom)
        }

        val stableFrameBottom = lastStableWindowBottom.takeIf { it > rootRect.top }
        val rootAlreadyResizedToVisibleFrame = visibleFrame.bottom >= rootRect.bottom - 1
        val imeBaseBottom = when {
            stableFrameBottom != null && stableFrameBottom > rootRect.bottom -> stableFrameBottom
            !rootAlreadyResizedToVisibleFrame -> rootRect.bottom
            else -> null
        }
        val maxKnownBottom = maxOf(rootRect.bottom, stableFrameBottom ?: rootRect.bottom)
        val visibleFrameBottom = visibleFrame.bottom.takeIf {
            visibleFrame.height() > 0 && it > rootRect.top && it <= maxKnownBottom
        }
        val imeSafeBottom = imeBaseBottom?.minus(imeBottom)?.takeIf {
            imeVisible && imeBottom > 0 && it > rootRect.top
        }
        val navigationSafeBottom = (rootRect.bottom - navigationBottom).takeIf {
            navigationBottom > 0 && it > rootRect.top
        }

        // Android 10 等系统上 IME inset 可能不稳定；优先取最保守的可见底部，避免键盘覆盖输入框。
        return listOfNotNull(visibleFrameBottom, imeSafeBottom, navigationSafeBottom, rootRect.bottom)
            .minOrNull()
            ?: rootRect.bottom
    }

    private fun applyKeyboardScrollPadding(scrollParent: View) {
        val insets = ViewCompat.getRootWindowInsets(root) ?: return
        if (!insets.isVisible(WindowInsetsCompat.Type.ime())) return

        val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
        if (imeBottom <= 0) return

        val state = scrollPaddingStates.getOrPut(scrollParent) {
            ScrollPaddingState(
                paddingBottom = scrollParent.paddingBottom,
                clipToPadding = (scrollParent as? ViewGroup)?.clipToPadding ?: true
            )
        }
        val targetPaddingBottom = state.paddingBottom + imeBottom + revealGapPx
        if (scrollParent.paddingBottom != targetPaddingBottom) {
            // 底部输入框本身已经接近内容末尾；键盘态需要临时补足可滚空白，否则 scrollY 到底后也露不出焦点框。
            scrollParent.setPadding(
                scrollParent.paddingLeft,
                scrollParent.paddingTop,
                scrollParent.paddingRight,
                targetPaddingBottom
            )
        }
        (scrollParent as? ViewGroup)?.clipToPadding = false
    }

    private fun restoreKeyboardScrollPadding() {
        scrollPaddingStates.toList().forEach { (scrollParent, state) ->
            scrollParent.setPadding(
                scrollParent.paddingLeft,
                scrollParent.paddingTop,
                scrollParent.paddingRight,
                state.paddingBottom
            )
            (scrollParent as? ViewGroup)?.clipToPadding = state.clipToPadding
        }
        scrollPaddingStates.clear()
    }

    private fun logReveal(
        reason: String,
        editText: EditText,
        scrollParent: View?,
        geometry: RevealGeometry?,
        deltaY: Int
    ) {
        if (!Log.isLoggable(TAG, Log.DEBUG)) return

        val rootRect = Rect().also { root.getGlobalVisibleRect(it) }
        val visibleFrame = Rect().also { root.getWindowVisibleDisplayFrame(it) }
        val insets = ViewCompat.getRootWindowInsets(root)
        val imeVisible = insets?.isVisible(WindowInsetsCompat.Type.ime()) == true
        val imeBottom = insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
        val navigationBottom = insets?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0

        Log.d(TAG,
            "reason=$reason editText=${editText.resourceName()} " +
                "scroll=${scrollParent?.javaClass?.simpleName} scrollY=${scrollParent?.scrollY} " +
                "canScrollDown=${scrollParent?.canScrollVertically(1)} " +
                "paddingBottom=${scrollParent?.paddingBottom} " +
                "deltaY=$deltaY root=$rootRect visibleFrame=$visibleFrame " +
                "stableBottom=$lastStableWindowBottom " +
                "imeVisible=$imeVisible imeBottom=$imeBottom navBottom=$navigationBottom " +
                "input=${geometry?.inputRect} scrollRect=${geometry?.scrollRect} " +
                "safeTop=${geometry?.safeTop} safeBottom=${geometry?.safeBottom}"
        )
    }

    private fun View.resourceName(): String {
        if (id == View.NO_ID) return "no-id"
        return runCatching { resources.getResourceEntryName(id) }.getOrDefault(id.toString())
    }

    private fun View.containsDescendant(view: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current == this) return true
            current = current.parent as? View
        }
        return false
    }

    private fun View.hasUsableGlobalRect(): Boolean {
        val rect = Rect()
        return isAttachedToWindow && isShown && getGlobalVisibleRect(rect) && !rect.isEmpty
    }

    private fun EditText.findNearestVerticalScrollParent(): View? {
        var currentParent = parent
        while (currentParent is View) {
            if (currentParent is NestedScrollView || currentParent is ScrollView) {
                return currentParent
            }
            currentParent = currentParent.parent
        }
        return null
    }

    private fun requestApplyInsetsWhenAttached() {
        if (root.isAttachedToWindow) {
            ViewCompat.requestApplyInsets(root)
            return
        }
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.removeOnAttachStateChangeListener(this)
                ViewCompat.requestApplyInsets(v)
            }

            override fun onViewDetachedFromWindow(v: View) = Unit
        })
    }

    companion object {
        private const val TAG = "BabyCareKeyboard"
        private const val REVEAL_GAP_DP = 24
        private const val KEYBOARD_ANIMATION_DELAY_MS = 260L

        fun install(root: View): KeyboardSafeScrollDelegate {
            return KeyboardSafeScrollDelegate(root).also { it.start() }
        }
    }

    private data class RevealGeometry(
        val inputRect: Rect,
        val scrollRect: Rect,
        val safeTop: Int,
        val safeBottom: Int
    )

    private data class ScrollPaddingState(
        val paddingBottom: Int,
        val clipToPadding: Boolean
    )
}
