package com.zero.babycare

import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.babycare.navigation.NavTarget
import com.zero.babydata.entity.EventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 在真实窗口内验证原生密度与系统栏、键盘避让，可在窄窗和横屏设备重复执行。 */
@RunWith(AndroidJUnit4::class)
class MainActivityWindowInsetsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun firstEventCategoryShowsWholeSubtypeNames() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            awaitActivity(scenario, "首页初始化未完成") { mainViewModel(it).babyDataLoaded.value }
            val target = NavTarget.EventRecord(preSelectedCategory = EventType.CATEGORY_DIAPER)
            scenario.onActivity { mainViewModel(it).navigateTo(target) }
            awaitActivity(scenario, "事件子类型未显示") { activity ->
                recordRoot(activity, target)?.findViewById<RecyclerView>(R.id.rvSubtype)?.childCount?.let { it > 0 } == true
            }
            settleWindow()
            scenario.onActivity { activity ->
                val grid = requireNotNull(recordRoot(activity, target)).findViewById<RecyclerView>(R.id.rvSubtype)
                for (index in 0 until grid.childCount) {
                    val label = grid.getChildAt(index).findViewById<TextView>(R.id.tvName)
                    val layout = requireNotNull(label.layout)
                    val dimensions = "列数=${(grid.layoutManager as GridLayoutManager).spanCount}, 网格宽=${grid.width}, 标签宽=${label.width}, 字号=${label.textSize}"
                    for (line in 0 until layout.lineCount) assertEquals("首次展示的事件子类型不能省略名称（$dimensions）", 0, layout.getEllipsisCount(line))
                    assertEquals("完整名称必须参与排版", label.text.length, layout.getLineEnd(layout.lineCount - 1))
                }
            }
        } finally {
            try {
                scenario.onActivity { mainViewModel(it).navigateTo(NavTarget.Dashboard) }
            } finally {
                scenario.close()
            }
        }
    }

    @Test
    fun nativeDensityAndSaveButtonRemainInsideWindowSafeArea() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            awaitActivity(scenario, "首页初始化未完成") { mainViewModel(it).babyDataLoaded.value }
            scenario.onActivity { activity ->
                val resources = activity.resources
                assertEquals("页面不能再改写原生 dp 密度",
                    resources.configuration.densityDpi.toFloat() / DisplayMetrics.DENSITY_DEFAULT,
                    resources.displayMetrics.density, 0.001f)
            }
            for (target in listOf(NavTarget.FeedingRecord(), NavTarget.SleepRecord())) {
                scenario.onActivity { mainViewModel(it).navigateTo(target) }
                awaitActivity(scenario, "记录页未显示") { recordRoot(it, target)?.findViewById<View>(R.id.tvSave)?.height?.let { height -> height > 0 } == true }
                settleWindow()
                scenario.onActivity { assertSaveInsideSafeArea(it, target) }
            }
            scenario.onActivity { activity ->
                val note = requireNotNull(recordRoot(activity, NavTarget.SleepRecord()))
                    .findViewById<EditText>(R.id.etNote)
                note.requestFocus()
                WindowInsetsControllerCompat(activity.window, note).show(WindowInsetsCompat.Type.ime())
            }
            awaitActivity(scenario, "输入法未显示") { activity ->
                inputMethod(activity).isFullscreenMode || ViewCompat.getRootWindowInsets(activity.window.decorView)
                    ?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }
            settleWindow()
            scenario.onActivity { activity ->
                // 横屏极矮窗口允许系统全屏编辑器；普通键盘模式中保存必须完整位于键盘上方。
                if (!inputMethod(activity).isFullscreenMode) {
                    assertSaveInsideSafeArea(activity, NavTarget.SleepRecord())
                    val note = requireNotNull(recordRoot(activity, NavTarget.SleepRecord())).findViewById<EditText>(R.id.etNote)
                    val layout = requireNotNull(note.layout)
                    val line = layout.getLineForOffset(note.selectionStart.coerceAtLeast(0))
                    val bounds = fullBounds(note)
                    val caret = Rect(bounds.left + note.compoundPaddingLeft,
                        bounds.top + note.compoundPaddingTop + layout.getLineTop(line) - note.scrollY,
                        bounds.left + note.compoundPaddingLeft + 1,
                        bounds.top + note.compoundPaddingTop + layout.getLineBottom(line) - note.scrollY)
                    val visible = Rect()
                    assertTrue("键盘显示后备注的光标行必须完整可见", note.getGlobalVisibleRect(visible) && visible.contains(caret))
                }
                WindowInsetsControllerCompat(activity.window, activity.window.decorView).hide(WindowInsetsCompat.Type.ime())
            }
            awaitActivity(scenario, "输入法未关闭") { activity ->
                !inputMethod(activity).isFullscreenMode && ViewCompat.getRootWindowInsets(activity.window.decorView)
                    ?.isVisible(WindowInsetsCompat.Type.ime()) != true
            }
            settleWindow()
            scenario.onActivity { assertSaveInsideSafeArea(it, NavTarget.SleepRecord()) }
        } finally {
            try {
                scenario.onActivity { activity ->
                    WindowInsetsControllerCompat(activity.window, activity.window.decorView).hide(WindowInsetsCompat.Type.ime())
                    mainViewModel(activity).navigateTo(NavTarget.Dashboard)
                }
            } finally {
                scenario.close()
            }
        }
    }

    private fun assertSaveInsideSafeArea(activity: MainActivity, target: NavTarget) {
        val decor = activity.window.decorView
        val insets = requireNotNull(ViewCompat.getRootWindowInsets(decor))
        val blocked = insets.getInsets(WindowInsetsCompat.Type.systemBars() or
            WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
        val window = fullBounds(decor)
        val safe = Rect(window.left + blocked.left, window.top + blocked.top,
            window.right - blocked.right, window.bottom - blocked.bottom)
        val save = requireNotNull(recordRoot(activity, target)).findViewById<View>(R.id.tvSave)
        assertTrue("保存按钮必须显示", save.isShown)
        // 使用完整布局尺寸，不能让 getGlobalVisibleRect 的裁剪掩盖按钮被导航栏遮住的回归。
        assertTrue("保存按钮完整边界必须位于系统栏和键盘安全区内", safe.contains(fullBounds(save)))
        assertTrue("保存按钮触控高度必须达到原生 48dp", save.height >= 48 * activity.resources.displayMetrics.density)
    }

    private fun fullBounds(view: View): Rect {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
    }

    private fun recordRoot(activity: MainActivity, target: NavTarget): View? =
        activity.supportFragmentManager.fragments.firstOrNull {
            target.fragmentClass.isInstance(it) && !it.isHidden && it.view?.isShown == true
        }?.view

    private fun mainViewModel(activity: MainActivity) = ViewModelProvider(activity)[MainViewModel::class.java]
    private fun inputMethod(activity: MainActivity) = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    private fun settleWindow() {
        SystemClock.sleep(350)
        instrumentation.waitForIdleSync()
    }

    private fun awaitActivity(scenario: ActivityScenario<MainActivity>, message: String, predicate: (MainActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000L
        do {
            instrumentation.waitForIdleSync()
            var ready = false
            scenario.onActivity { ready = predicate(it) }
            if (ready) return
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError(message)
    }
}
