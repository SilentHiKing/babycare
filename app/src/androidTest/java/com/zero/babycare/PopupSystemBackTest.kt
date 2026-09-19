package com.zero.babycare

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lxj.xpopup.core.BasePopupView
import com.lxj.xpopup.enums.PopupStatus
import com.lxj.xpopup.interfaces.SimpleCallback
import com.lxj.xpopup.util.XPopupUtils
import com.zero.components.base.util.DialogHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 新旧系统返回均走真实窗口分发；直接调用Activity dispatcher会绕过弹窗兼容性问题。 */
@RunWith(AndroidJUnit4::class)
class PopupSystemBackTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun systemBackDismissesChoiceAndCompletesItsCancellationExactlyOnce() = withHost { scenario ->
        var popup: BasePopupView? = null
        var dismissCount = 0
        var confirmedCount = 0
        try {
            scenario.onActivity { activity ->
                popup = DialogHelper.showChoiceSheet(activity,
                    activity.getString(com.zero.common.R.string.babyGender),
                    listOf(DialogHelper.PickerOption(0, activity.getString(com.zero.common.R.string.boy)),
                        DialogHelper.PickerOption(1, activity.getString(com.zero.common.R.string.girl))),
                    selectedValue = 0, onSelected = { confirmedCount++ })
                // Backup等消费者在onDismiss取消待确认状态；原生Dialog消失不能替代这个完成信号。
                popup!!.popupInfo.xPopupCallback = object : SimpleCallback() {
                    override fun onDismiss(popupView: BasePopupView?) { dismissCount++ }
                }
            }
            val shown = requireNotNull(popup)
            awaitShown(scenario, shown)
            assertTrue("系统返回动作未受理", instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            await(scenario, "系统返回没有关闭选择器") { shown.popupStatus == PopupStatus.Dismiss }
            settle()
            scenario.onActivity {
                assertEquals("系统返回必须完成且仅完成一次取消通知", 1, dismissCount)
                assertEquals("系统返回不能提交选择值", 0, confirmedCount)
                assertTrue("关闭弹窗不能退出资料页面", !it.isFinishing && !it.isDestroyed)
            }
        } finally { scenario.onActivity { popup?.dismiss() } }
    }

    @Test
    fun systemBackCannotDismissNonCancelableLoading() = withHost { scenario ->
        var popup: BasePopupView? = null
        try {
            scenario.onActivity { activity -> popup = DialogHelper.createLoadingDialog(activity).show() }
            val shown = requireNotNull(popup)
            awaitShown(scenario, shown)
            assertTrue("系统返回动作未受理", instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            settle()
            scenario.onActivity {
                assertEquals("不可取消的加载提示不能绕过dismissOnBackPressed(false)关闭", PopupStatus.Show, shown.popupStatus)
                assertTrue("加载窗口必须仍显示", shown.dialog.isShowing)
                assertTrue("加载期间不能退出宿主", !it.isFinishing && !it.isDestroyed)
            }
        } finally { scenario.onActivity { popup?.dismiss() } }
    }

    @Test
    fun consumerCanInterceptSystemBackBeforeAllowingExactlyOneDismissal() = withHost { scenario ->
        var popup: BasePopupView? = null
        var backCount = 0
        var dismissCount = 0
        var intercepted = true
        try {
            scenario.onActivity { activity ->
                popup = DialogHelper.showConfirmDialog(activity,
                    activity.getString(com.zero.common.R.string.tip),
                    activity.getString(com.zero.common.R.string.unsaved_record_tip),
                    onConfirm = { throw AssertionError("系统返回不能执行确认") })
                popup!!.popupInfo.xPopupCallback = object : SimpleCallback() {
                    override fun onBackPressed(popupView: BasePopupView?): Boolean {
                        backCount++
                        Log.i("PopupSystemBackTest", "业务返回拦截回调：次数=$backCount，拦截=$intercepted")
                        return intercepted
                    }

                    override fun onDismiss(popupView: BasePopupView?) { dismissCount++ }
                }
            }
            val shown = requireNotNull(popup)
            awaitShown(scenario, shown)
            assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            await(scenario, "业务返回拦截未被调用") { backCount == 1 }
            scenario.onActivity {
                assertEquals("业务拦截时弹窗必须保留", PopupStatus.Show, shown.popupStatus)
                assertEquals(0, dismissCount)
                intercepted = false
            }
            assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            await(scenario, "放行后弹窗未关闭") { shown.popupStatus == PopupStatus.Dismiss }
            settle()
            scenario.onActivity {
                assertEquals(2, backCount)
                assertEquals("允许关闭时只通知一次", 1, dismissCount)
            }
        } finally { scenario.onActivity { popup?.dismiss() } }
    }

    @Test
    fun systemBackHidesRealKeyboardBeforeDismissingPopup() = withHost { scenario ->
        var popup: BasePopupView? = null
        var dismissCount = 0
        try {
            scenario.onActivity { activity ->
                popup = DialogHelper.showConfirmDialog(activity,
                    activity.getString(com.zero.common.R.string.tip),
                    activity.getString(com.zero.common.R.string.unsaved_record_tip),
                    onConfirm = { throw AssertionError("返回不能执行确认") })
                popup!!.popupInfo.xPopupCallback = object : SimpleCallback() {
                    override fun onDismiss(popupView: BasePopupView?) { dismissCount++ }
                }
            }
            val shown = requireNotNull(popup)
            awaitShown(scenario, shown)
            scenario.onActivity { activity ->
                // 仅在生产弹窗正文中加入内存输入，验证真实 IME；不引入另一条模拟弹窗实现。
                val content = shown.findViewById<TextView>(com.zero.components.R.id.tv_content)
                val editor = EditText(activity).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    setText("Audit")
                }
                (content.parent as ViewGroup).addView(editor)
                editor.requestFocus()
                WindowInsetsControllerCompat(shown.hostWindow, editor).show(WindowInsetsCompat.Type.ime())
            }
            await(scenario, "弹窗输入未显示真实键盘") {
                ViewCompat.getRootWindowInsets(shown)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }
            assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            await(scenario, "第一次返回应只收键盘") {
                shown.popupStatus == PopupStatus.Show &&
                    ViewCompat.getRootWindowInsets(shown)?.isVisible(WindowInsetsCompat.Type.ime()) == false
            }
            scenario.onActivity { assertEquals("收键盘不能完成弹窗取消", 0, dismissCount) }
            assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            await(scenario, "收键盘后再次返回应关闭弹窗") { shown.popupStatus == PopupStatus.Dismiss }
            scenario.onActivity { assertEquals("弹窗关闭只能通知一次", 1, dismissCount) }
        } finally { scenario.onActivity { popup?.dismiss() } }
    }

    private fun withHost(block: (ActivityScenario<MainActivity>) -> Unit) {
        // 只读宿主状态，所有输入和回调限于临时弹窗；旧平台真机也能安全验证原返回链。
        assumeTrue("系统返回回归需要API28及以上平台", Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await(scenario, "初始页面未就绪") { ViewModelProvider(it)[MainViewModel::class.java].babyDataLoaded.value }
            block(scenario)
        }
    }

    private fun settle() {
        SystemClock.sleep(500)
        instrumentation.waitForIdleSync()
    }

    private fun awaitShown(scenario: ActivityScenario<MainActivity>, popup: BasePopupView) {
        // Show 是库的动画状态，系统返回还需真正获得窗口焦点，避免操作前一个窗口。
        await(scenario, "弹窗未完整显示并获得窗口焦点") {
            popup.popupStatus == PopupStatus.Show && popup.isAttachedToWindow && popup.hasWindowFocus()
        }
        scenario.onActivity {
            val ime = ViewCompat.getRootWindowInsets(popup)?.isVisible(WindowInsetsCompat.Type.ime())
            val sameDispatcher = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                popup.findOnBackInvokedDispatcher() === popup.hostWindow.onBackInvokedDispatcher
            } else null
            Log.i("PopupSystemBackTest", "返回前窗口状态：类型=${popup.javaClass.simpleName}，" +
                "允许关闭=${popup.popupInfo.isDismissOnBackPressed}，键盘=$ime，" +
                "旧库不可见高度=${XPopupUtils.getDecorViewInvisibleHeight(popup.hostWindow)}，" +
                "同视图分发器=$sameDispatcher")
        }
    }

    private fun await(scenario: ActivityScenario<MainActivity>, message: String, ready: (MainActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        do {
            instrumentation.waitForIdleSync()
            var done = false
            scenario.onActivity { done = ready(it) }
            if (done) return
            SystemClock.sleep(30)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError(message)
    }
}
