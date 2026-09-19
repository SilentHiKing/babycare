package com.zero.babycare

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.SystemClock
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lxj.xpopup.core.BasePopupView
import com.lxj.xpopup.enums.PopupStatus
import com.zero.components.base.util.DialogHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 使用资料页真实五项资源；由设备矩阵提供横屏及大字号，不改用户资料或显示配置。 */
@RunWith(AndroidJUnit4::class)
class ChoiceSheetWindowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun allBloodTypesAndActionsRemainReadableAndReachableInCurrentWindow() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val automation = instrumentation.uiAutomation
            val originalFlags = automation.serviceInfo.flags
            try {
                automation.serviceInfo = automation.serviceInfo.apply {
                    flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                }
                await(scenario, "初始页面未就绪") {
                    ViewModelProvider(it)[MainViewModel::class.java].babyDataLoaded.value
                }
                val themes = listOf("boy" to com.zero.common.R.style.AppTheme_Boy,
                    "girl" to com.zero.common.R.style.AppTheme_Girl)
                themes.forEach { (name, theme) ->
                    listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES).forEach { night ->
                        inspectTheme(scenario, name, theme, night)
                    }
                }
            } finally {
                automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
            }
        }
    }

    private fun inspectTheme(scenario: ActivityScenario<MainActivity>, themeName: String, theme: Int, nightMode: Int) {
        var popup: BasePopupView? = null
        val selected = mutableListOf<Int>()
        lateinit var themedContext: Context
        try {
            scenario.onActivity { activity ->
                val configuration = Configuration(activity.resources.configuration).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
                }
                // 内部使用 createConfigurationContext 隔离资源；baseContext 保留 Activity，XPopup 才能找到真实窗口。
                themedContext = ContextThemeWrapper(activity, theme).apply {
                    applyOverrideConfiguration(configuration)
                }
                val labels = listOf(
                    com.zero.common.R.string.blood_type_a,
                    com.zero.common.R.string.blood_type_b,
                    com.zero.common.R.string.blood_type_ab,
                    com.zero.common.R.string.blood_type_o,
                    com.zero.common.R.string.blood_type_unknown
                ).map(themedContext::getString)
                popup = DialogHelper.showChoiceSheet(themedContext,
                    themedContext.getString(com.zero.common.R.string.babyBloodType),
                    labels.mapIndexed { index, label -> DialogHelper.PickerOption(index, label) },
                    selectedValue = 0, onSelected = selected::add)
            }
            val sheet = requireNotNull(popup)
            await(scenario, "血型选择器未显示") { sheet.popupStatus == PopupStatus.Show && sheet.width > 0 }
            settle()
            val configuration = sheet.resources.configuration
            val imageName = "choice-sheet-${configuration.screenWidthDp}x${configuration.screenHeightDp}" +
                "-font${configuration.fontScale}-$themeName-" +
                if (nightMode == Configuration.UI_MODE_NIGHT_YES) "dark" else "light"
            scenario.onActivity {
                assertEquals("选择器必须使用隔离的浅深资源", nightMode,
                    sheet.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
                listOf(com.zero.components.R.id.tv_cancel, com.zero.components.R.id.tv_confirm).forEach { id ->
                    val action = sheet.findViewById<TextView>(id)
                    assertWholeText(action, sheet)
                    assertFullBounds(action, sheet, "固定操作")
                    assertTrue("选择器操作触控高度不足48dp", action.height >= 48 * action.resources.displayMetrics.density)
                }
            }
            val container = sheet.findViewById<LinearLayout>(com.zero.components.R.id.option_container)
            assertEquals("必须包含资料页全部五种血型", 5, container.childCount)
            scenario.onActivity {
                val selectedRow = (container.getChildAt(0) as android.view.ViewGroup).getChildAt(0) as android.view.ViewGroup
                assertEquals("选中图标必须使用本次宝宝主题前景色",
                    resolveColor(themedContext, com.zero.common.R.attr.colorBrandForeground),
                    (selectedRow.getChildAt(1) as ImageView).imageTintList?.defaultColor)
                assertEquals("选项文字必须使用本次浅深主题正文色",
                    resolveColor(themedContext, com.zero.common.R.attr.colorTextPrimary),
                    (selectedRow.getChildAt(0) as TextView).currentTextColor)
            }
            saveScreenshot(scenario, sheet, "$imageName-top.png")
            for (index in 0 until container.childCount) {
                val row = (container.getChildAt(index) as android.view.ViewGroup).getChildAt(0) as android.view.ViewGroup
                val label = row.getChildAt(0) as TextView
                scenario.onActivity {
                    assertWholeText(label, sheet)
                    val minimum = row.resources.getDimensionPixelSize(com.zero.common.R.dimen.min_touch_target)
                    assertTrue("血型选项触控范围不足48dp", row.width >= minimum && row.height >= minimum)
                }
                // 必须通过用户可用的滚动动作抵达被裁剪项目，直接调用 performClick 不能证明可操作。
                var reached = false
                for (attempt in 0..6) {
                    scenario.onActivity { reached = isFullyVisible(row, sheet) }
                    if (reached) break
                    if (!scrollForward()) break
                    settle()
                }
                scenario.onActivity { assertFullBounds(row, sheet, "血型第${index + 1}项") }
                val node = findNode { it.className == "android.widget.RadioButton" && it.text?.toString() == label.text.toString() }
                try {
                    assertTrue("血型选项必须具有真实选择动作", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                } finally { node.recycle() }
                settle()
                val checked = findNode { it.className == "android.widget.RadioButton" && it.text?.toString() == label.text.toString() }
                try { assertTrue("选中血型必须立即提供checked状态", checked.isChecked) }
                finally { checked.recycle() }
            }
            saveScreenshot(scenario, sheet, "$imageName-bottom.png")
            scenario.onActivity { assertFullBounds(sheet.findViewById(com.zero.components.R.id.tv_confirm), sheet, "选择后确认") }
            val confirmId = sheet.resources.getResourceName(com.zero.components.R.id.tv_confirm)
            val confirm = findNode { it.viewIdResourceName == confirmId }
            try { assertTrue(confirm.performAction(AccessibilityNodeInfo.ACTION_CLICK)) }
            finally { confirm.recycle() }
            await(scenario, "选择未提交或旧弹层未撤下") {
                selected == listOf(4) && sheet.popupStatus == PopupStatus.Dismiss && !sheet.isAttachedToWindow
            }
        } finally {
            scenario.onActivity { popup?.dismiss() }
        }
    }

    private fun resolveColor(context: Context, attr: Int): Int {
        val value = TypedValue()
        check(context.theme.resolveAttribute(attr, value, true))
        return if (value.resourceId == 0) value.data else context.getColor(value.resourceId)
    }

    /** 只绘制临时弹窗本身，避免把半透明遮罩下的真实宝宝资料写入截图。 */
    private fun saveScreenshot(scenario: ActivityScenario<MainActivity>, popup: BasePopupView, filename: String) {
        if (InstrumentationRegistry.getArguments().getString("captureScreenshots") != "true") return
        lateinit var bitmap: Bitmap
        scenario.onActivity {
            bitmap = Bitmap.createBitmap(popup.width, popup.height, Bitmap.Config.ARGB_8888)
            popup.draw(Canvas(bitmap).apply {
                drawColor(resolveColor(popup.context, com.zero.common.R.attr.colorBackground))
            })
        }
        try {
            val directory = File(instrumentation.targetContext.filesDir, "audit-screens")
            check(directory.isDirectory || directory.mkdirs()) { "无法创建选择器截图目录" }
            File(directory, filename).outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "无法保存选择器截图" }
            }
        } finally { bitmap.recycle() }
    }

    private fun assertWholeText(label: TextView, popup: BasePopupView) {
        val layout = requireNotNull(label.layout)
        val diagnostic = "${label.text}, view=${bounds(label)}, font=${label.resources.configuration.fontScale}, safe=${safeBounds(popup)}"
        assertTrue("文字没有参与完整布局：$diagnostic", layout.lineCount > 0)
        assertEquals("文字不能截断：$diagnostic", label.text.length, layout.getLineEnd(layout.lineCount - 1))
        assertTrue("文字垂直裁剪：$diagnostic", layout.height <= label.height - label.compoundPaddingTop - label.compoundPaddingBottom)
        val width = label.width - label.compoundPaddingLeft - label.compoundPaddingRight
        for (line in 0 until layout.lineCount) {
            assertEquals("文字不能省略：$diagnostic", 0, layout.getEllipsisCount(line))
            assertTrue("文字横向裁剪：$diagnostic", layout.getLineLeft(line) >= -1f && layout.getLineRight(line) <= width + 1f)
        }
    }

    private fun assertFullBounds(view: View, popup: BasePopupView, label: String) {
        assertTrue("${label}不可完整触达：bounds=${bounds(view)}, safe=${safeBounds(popup)}, font=${view.resources.configuration.fontScale}",
            isFullyVisible(view, popup))
    }

    private fun isFullyVisible(view: View, popup: BasePopupView): Boolean {
        val visible = Rect()
        return view.getGlobalVisibleRect(visible) && visible == bounds(view) && safeBounds(popup).contains(bounds(view))
    }

    private fun safeBounds(popup: BasePopupView): Rect {
        val decor = requireNotNull(popup.dialog.window).decorView
        val safe = bounds(decor)
        val inset = requireNotNull(ViewCompat.getRootWindowInsets(decor))
            .getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        safe.set(safe.left + inset.left, safe.top + inset.top, safe.right - inset.right, safe.bottom - inset.bottom)
        return safe
    }

    private fun bounds(view: View): Rect {
        val location = IntArray(2).also(view::getLocationOnScreen)
        return Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
    }

    private fun scrollForward(): Boolean {
        val scrollable = findNode { node -> node.isScrollable &&
            node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD } }
        try {
            return scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        } finally { scrollable.recycle() }
    }

    private fun findNode(matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        // 窗口/滚动无障碍事件异步更新；每次重取系统快照，不用旧节点或后台点击代替真实可达性。
        val deadline = SystemClock.uptimeMillis() + 5_000
        do {
            instrumentation.waitForIdleSync()
            val root = instrumentation.uiAutomation.rootInActiveWindow
            if (root != null) {
                try { find(root, matches)?.let { return it } }
                finally { root.recycle() }
            }
            SystemClock.sleep(30)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("选择器缺少目标无障碍节点，等待系统窗口更新5秒后仍未找到")
    }

    private fun find(node: AccessibilityNodeInfo, matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (matches(node)) return AccessibilityNodeInfo.obtain(node)
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try { find(child, matches)?.let { return it } }
            finally { child.recycle() }
        }
        return null
    }

    private fun settle() {
        SystemClock.sleep(250)
        instrumentation.waitForIdleSync()
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
