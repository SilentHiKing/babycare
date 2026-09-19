package com.zero.babycare

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.babycare.navigation.NavTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 真实系统返回优先收键盘、关闭抽屉或保护草稿，根首页提前放行平台返回预览。 */
@RunWith(AndroidJUnit4::class)
class MainActivityBackNavigationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun rootReleasesBackWhileDrawerAndRecordDraftKeepTheirOwnBehavior() {
        assumeTrue("仅在审查AVD验证合成草稿", Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                await(scenario, "初始页面未加载") { mainVm(it).babyDataLoaded.value }
                scenario.onActivity { mainVm(it).navigateTo(NavTarget.Dashboard) }
                await(scenario, "首页仍拦截平台返回预览") {
                    mainVm(it).navTarget.value == NavTarget.Dashboard && !it.onBackPressedDispatcher.hasEnabledCallbacks()
                }
                scenario.onActivity { it.openDrawer() }
                await(scenario, "抽屉未打开") { it.isDrawerOpen() }
                scenario.onActivity { assertTrue("抽屉打开时应接管返回", it.onBackPressedDispatcher.hasEnabledCallbacks()) }
                systemBack()
                await(scenario, "返回没有关闭抽屉并释放首页") {
                    !it.findViewById<DrawerLayout>(R.id.drawerLayout).isDrawerVisible(GravityCompat.START) &&
                        !it.onBackPressedDispatcher.hasEnabledCallbacks()
                }
                scenario.onActivity { mainVm(it).navigateTo(NavTarget.Settings()) }
                await(scenario, "设置页没有接管返回") { mainVm(it).navTarget.value is NavTarget.Settings && it.onBackPressedDispatcher.hasEnabledCallbacks() }
                systemBack()
                await(scenario, "设置页没有回到首页") {
                    mainVm(it).navTarget.value == NavTarget.Dashboard && !it.onBackPressedDispatcher.hasEnabledCallbacks()
                }

                scenario.onActivity { mainVm(it).navigateTo(NavTarget.SleepRecord()) }
                await(scenario, "记录页尚未显示") { sleepRoot(it)?.isShown == true }
                val draft = "Audit draft"
                scenario.onActivity { activity ->
                    note(activity).apply { setText(draft); requestFocus() }
                    WindowInsetsControllerCompat(activity.window, note(activity)).show(WindowInsetsCompat.Type.ime())
                }
                await(scenario, "备注键盘未显示") { imeVisible(it) }
                systemBack()
                await(scenario, "首次返回没有先关闭键盘") { !imeVisible(it) }
                scenario.onActivity {
                    assertTrue("关闭键盘不能离开草稿", mainVm(it).navTarget.value is NavTarget.SleepRecord)
                    assertEquals(draft, note(it).text.toString())
                }
                systemBack()
                val cancel = findCancelNode()
                try { assertTrue("取消放弃必须可操作", cancel.performAction(AccessibilityNodeInfo.ACTION_CLICK)) }
                finally { cancel.recycle() }
                SystemClock.sleep(350)
                instrumentation.waitForIdleSync()
                scenario.onActivity {
                    assertTrue("取消后必须保留记录页", mainVm(it).navTarget.value is NavTarget.SleepRecord)
                    assertEquals(draft, note(it).text.toString())
                    assertFalse("内部返回不能退出宿主", it.isFinishing)
                }
            } finally {
                scenario.onActivity { activity ->
                    WindowInsetsControllerCompat(activity.window, activity.window.decorView).hide(WindowInsetsCompat.Type.ime())
                    sleepRoot(activity)?.findViewById<EditText>(R.id.etNote)?.setText("")
                    mainVm(activity).navigateTo(NavTarget.Dashboard)
                }
            }
        }
    }

    private fun mainVm(activity: MainActivity) = ViewModelProvider(activity)[MainViewModel::class.java]
    private fun sleepRoot(activity: MainActivity): View? = activity.supportFragmentManager.fragments
        .firstOrNull { NavTarget.SleepRecord().fragmentClass.isInstance(it) && !it.isHidden }?.view
    private fun note(activity: MainActivity) = requireNotNull(sleepRoot(activity)).findViewById<EditText>(R.id.etNote)
    private fun imeVisible(activity: MainActivity) = ViewCompat.getRootWindowInsets(activity.window.decorView)
        ?.isVisible(WindowInsetsCompat.Type.ime()) == true
    private fun systemBack() {
        assertTrue("系统返回未被受理", instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
    }

    private fun findCancelNode(): AccessibilityNodeInfo {
        val id = instrumentation.targetContext.resources.getResourceName(com.zero.components.R.id.tv_cancel)
        val deadline = SystemClock.uptimeMillis() + 10_000
        do {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            if (root != null) {
                try {
                    val nodes = root.findAccessibilityNodeInfosByViewId(id)
                    val match = nodes.firstOrNull { it.isVisibleToUser }
                    val result = match?.let(AccessibilityNodeInfo::obtain)
                    nodes.forEach(AccessibilityNodeInfo::recycle)
                    if (result != null) return result
                } finally { root.recycle() }
            }
            SystemClock.sleep(30)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("关闭键盘后的返回必须显示未保存确认")
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
