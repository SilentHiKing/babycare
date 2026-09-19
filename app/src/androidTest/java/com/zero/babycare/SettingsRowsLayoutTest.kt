package com.zero.babycare

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.babycare.navigation.NavTarget
import com.zero.babycare.settings.SettingsFragment
import com.zero.babycare.settings.SettingsViewModel
import com.zero.babycare.settings.backup.BackupViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import kotlin.math.roundToInt

/** 只检查真实页面和内存布局，不点击导入/导出/清理操作，也不改变用户设置。 */
@RunWith(AndroidJUnit4::class)
class SettingsRowsLayoutTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun settingsAndBackupRowsKeepWholeTextAndReachableControls() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            await(scenario) { ViewModelProvider(it)[MainViewModel::class.java].babyDataLoaded.value }
            navigate(scenario, NavTarget.Settings())
            var settingsBefore: Any? = null
            scenario.onActivity { activity ->
                val page = shown(activity) as SettingsFragment
                settingsBefore = ViewModelProvider(page)[SettingsViewModel::class.java].settingsState.value
            }
            val settingRows = listOf(R.id.rowFeedingUnit, R.id.rowWeightUnit, R.id.rowHeightUnit,
                R.id.rowReminder, R.id.rowVersion, R.id.rowLanguage, R.id.rowBackup, R.id.rowFamily)
            inspectPage(scenario, R.id.scrollSettings, settingRows)
            scenario.onActivity { activity ->
                val page = shown(activity) as SettingsFragment
                val row = page.requireView().findViewById<ViewGroup>(R.id.rowReminder)
                val toggle = row.findViewById<SwitchCompat>(R.id.switchToggle)
                val title = row.findViewById<TextView>(R.id.tvTitle)
                assertTrue("无按钮文案的开关也必须作为真实控件保留", toggle.text.isNullOrEmpty())
                assertTouchTarget(toggle)
                assertEquals("提醒标题必须与真实开关关联", toggle.id, title.labelFor)
                assertEquals("滚动检查不得修改用户设置", settingsBefore,
                    ViewModelProvider(page)[SettingsViewModel::class.java].settingsState.value)
            }
            assertReminderAccessibility(scenario)

            navigate(scenario, NavTarget.Backup())
            var dedupBefore = 0
            scenario.onActivity { dedupBefore = ViewModelProvider(shown(it))[BackupViewModel::class.java].dedupMinutes.value }
            inspectPage(scenario, R.id.scrollBackup,
                listOf(R.id.rowBackupExport, R.id.rowBackupImport, R.id.rowBackupDedup, R.id.rowBackupReportClear))
            scenario.onActivity { activity ->
                assertEquals("滚动检查不得修改合并配置", dedupBefore,
                    ViewModelProvider(shown(activity))[BackupViewModel::class.java].dedupMinutes.value)
            }
        } finally {
            try { scenario.onActivity { ViewModelProvider(it)[MainViewModel::class.java].navigateTo(NavTarget.Dashboard) } }
            finally { scenario.close() }
        }
    }

    /** 关联节点须从系统窗口读取；View 直接创建的未封装节点不能查询 labeledBy。 */
    private fun assertReminderAccessibility(scenario: ActivityScenario<MainActivity>) {
        var expectedTitle = ""
        var expectedChecked = false
        var viewId = ""
        scenario.onActivity { activity ->
            val row = shown(activity).requireView().findViewById<ViewGroup>(R.id.rowReminder)
            val toggle = row.findViewById<SwitchCompat>(R.id.switchToggle)
            expectedTitle = row.findViewById<TextView>(R.id.tvTitle).text.toString()
            expectedChecked = toggle.isChecked
            viewId = toggle.resources.getResourceName(toggle.id)
            row.requestRectangleOnScreen(Rect(0, 0, row.width, row.height), true)
        }
        await(scenario) { settled(shown(it).requireView()) }
        val automation = instrumentation.uiAutomation
        val originalFlags = automation.serviceInfo.flags
        try {
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            }
            val deadline = SystemClock.uptimeMillis() + 5_000L
            var node: AccessibilityNodeInfo? = null
            do {
                instrumentation.waitForIdleSync()
                automation.rootInActiveWindow?.let { root ->
                    try {
                        val matches = root.findAccessibilityNodeInfosByViewId(viewId)
                        node = matches.firstOrNull()
                        matches.drop(1).forEach { it.recycle() }
                    } finally { root.recycle() }
                }
                if (node == null) SystemClock.sleep(30L)
            } while (node == null && SystemClock.uptimeMillis() < deadline)
            val toggleNode = requireNotNull(node) { "系统无障碍窗口未提供提醒开关节点" }
            try {
                assertTrue("提醒开关仍须提供可选中的无障碍节点", toggleNode.isCheckable)
                assertTrue("提醒开关必须已滚入可见窗口", toggleNode.isVisibleToUser)
                assertEquals("布局检查不能切换提醒", expectedChecked, toggleNode.isChecked)
                val label = toggleNode.labeledBy
                try {
                    assertEquals("读屏必须能从开关找到提醒标题", expectedTitle, label?.text?.toString())
                } finally { label?.recycle() }
            } finally { toggleNode.recycle() }
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
        }
    }

    @Test
    fun trailingValuesReflowAcrossWidthsRtlAndEmptyStates() {
        instrumentation.runOnMainSync {
            val app = instrumentation.targetContext
            listOf(1f, 2f).forEach { fontScale ->
                val config = Configuration(app.resources.configuration).apply {
                    this.fontScale = fontScale
                    setLocale(Locale.US)
                }
                val context = ContextThemeWrapper(app.createConfigurationContext(config), com.zero.common.R.style.AppTheme_Girl)
                listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL).forEach { direction ->
                    val row = LayoutInflater.from(context).inflate(R.layout.item_setting_row, null) as ViewGroup
                    row.layoutDirection = direction
                    row.findViewById<TextView>(R.id.tvTitle).setText(com.zero.common.R.string.backup_dedup_title)
                    row.findViewById<TextView>(R.id.tvSummary).apply {
                        setText(com.zero.common.R.string.backup_dedup_summary)
                        visibility = View.VISIBLE
                    }
                    val value = row.findViewById<TextView>(R.id.tvValue)
                    value.setText(com.zero.common.R.string.settings_language_system)
                    var narrowHeight = 0
                    listOf(288, 800, 288).forEachIndexed { index, width ->
                        measure(row, width)
                        assertRowText(row)
                        val content = row.findViewById<View>(R.id.rowTextContent)
                        if (fontScale == 2f && width == 288) {
                            assertTrue("大字窄窗尾值须下移而不挤压正文", value.top >= content.bottom)
                        }
                        if (width == 800) {
                            assertTrue("宽窗应恢复并排", if (direction == View.LAYOUT_DIRECTION_LTR)
                                value.left >= content.right else value.right <= content.left)
                        }
                        if (index == 0) narrowHeight = row.height
                        if (index == 2) assertEquals("重复测量必须恢复稳定高度", narrowHeight, row.height)
                    }
                    value.text = ""
                    measure(row, 288)
                    val emptyHeight = row.height
                    assertEquals("空值不额外占据一行：font=$fontScale rtl=$direction " +
                        "row=${row.width}x${row.height} measured=${value.measuredWidth}x${value.measuredHeight} " +
                        "value=${localBounds(row, value)}", 0, value.height)
                    value.visibility = View.GONE
                    measure(row, 288)
                    assertEquals("空值和 GONE 尾值不应改变行高", emptyHeight, row.height)
                    value.visibility = View.VISIBLE
                    value.setText(com.zero.common.R.string.settings_language_system)
                    measure(row, 288)
                    assertEquals("恢复非空值必须恢复原布局", narrowHeight, row.height)
                    assertRowText(row)

                    val switchRow = LayoutInflater.from(context).inflate(R.layout.item_setting_switch, null) as ViewGroup
                    switchRow.layoutDirection = direction
                    switchRow.findViewById<TextView>(R.id.tvTitle).setText(com.zero.common.R.string.settings_reminder_switch_title)
                    switchRow.findViewById<TextView>(R.id.tvSummary).apply {
                        setText(com.zero.common.R.string.settings_reminder_switch_summary)
                        visibility = View.VISIBLE
                    }
                    val toggle = switchRow.findViewById<SwitchCompat>(R.id.switchToggle)
                    listOf(288, 800, 288).forEach { width ->
                        measure(switchRow, width)
                        assertTrue("开关本身没有按钮文案，仍不能被当作空值", toggle.text.isNullOrEmpty())
                        assertTouchTarget(toggle)
                        assertRowText(switchRow)
                    }
                }
            }
        }
    }

    private fun inspectPage(scenario: ActivityScenario<MainActivity>, scrollId: Int, rowIds: List<Int>) {
        rowIds.forEach { id ->
            var target: View? = null
            scenario.onActivity { activity ->
                val row = shown(activity).requireView().findViewById<ViewGroup>(id)
                assertRowText(row)
                val toggle = row.findViewById<View>(R.id.switchToggle)
                target = toggle ?: row
                val control = requireNotNull(target)
                // 超长说明允许跨屏；逐行滚到操作的末尾 48dp，验证完整点击区可进入视口。
                val height = minOf(control.height, (48 * control.resources.displayMetrics.density).roundToInt())
                control.requestRectangleOnScreen(Rect(0, control.height - height, control.width, control.height), true)
            }
            await(scenario) { settled(shown(it).requireView()) }
            scenario.onActivity { activity ->
                val root = shown(activity).requireView()
                val control = requireNotNull(target)
                assertTouchTarget(control)
                val viewport = Rect()
                root.findViewById<NestedScrollView>(scrollId).getGlobalVisibleRect(viewport)
                val full = bounds(control)
                val touch = Rect(full.left, full.bottom - (48 * control.resources.displayMetrics.density).roundToInt(),
                    full.right, full.bottom)
                assertTrue("设置操作必须可滚入安全视口：control=$full viewport=$viewport", viewport.contains(touch))
                if (id != R.id.rowVersion && id != R.id.rowFamily) {
                    assertTrue("操作必须保留真实点击入口", control.isEnabled && control.isClickable)
                }
            }
        }
    }

    private fun assertRowText(row: ViewGroup) {
        val rowName = if (row.id == View.NO_ID) "synthetic" else row.resources.getResourceEntryName(row.id)
        val labels = listOf(R.id.tvTitle, R.id.tvSummary, R.id.tvValue).mapNotNull { row.findViewById<TextView>(it) }
            .filter { it.visibility == View.VISIBLE && it.text.isNotEmpty() }
        labels.forEach { label ->
            val name = label.resources.getResourceEntryName(label.id)
            val layout = requireNotNull(label.layout)
            val available = label.width - label.compoundPaddingLeft - label.compoundPaddingRight
            val detail = "$rowName/$name font=${label.resources.configuration.fontScale} " +
                "rtl=${row.layoutDirection} width=$available layoutWidth=${layout.width} lines=${layout.lineCount} " +
                "row=${row.width}x${row.height} bounds=${localBounds(row, label)}"
            assertTrue("非空设置文字必须有有效宽度：$detail", available > 0 && layout.lineCount > 0)
            assertEquals("设置文字必须完整排版：$detail", label.text.length, layout.getLineEnd(layout.lineCount - 1))
            assertTrue("设置文字不得被固定高度裁切：$detail",
                layout.height <= label.height - label.compoundPaddingTop - label.compoundPaddingBottom)
            for (line in 0 until layout.lineCount) {
                assertEquals("设置文字不能省略：$detail", 0, layout.getEllipsisCount(line))
                val lineText = label.text.subSequence(layout.getLineStart(line), layout.getLineEnd(line))
                    .toString().replace("\n", "\\n")
                // getLineWidth 包含换行前的尾空格；绘制边界使用实际左右范围，RTL 同样检查。
                assertTrue("设置文字不得横向越界：$detail line=$line " +
                    "lineWidth=${layout.getLineWidth(line)} lineMax=${layout.getLineMax(line)} " +
                    "lineLeft=${layout.getLineLeft(line)} lineRight=${layout.getLineRight(line)} text=[$lineText]",
                    layout.getLineLeft(line) >= -1f && layout.getLineRight(line) <= available + 1f)
            }
            val local = Rect(0, 0, label.width, label.height)
            row.offsetDescendantRectToMyCoords(label, local)
            assertTrue("文字必须完整处于行内边距中：$detail bounds=$local",
                local.left >= row.paddingLeft && local.right <= row.width - row.paddingRight &&
                    local.top >= row.paddingTop && local.bottom <= row.height - row.paddingBottom)
        }
        val peers = labels + listOfNotNull(row.findViewById<View>(R.id.switchToggle), row.findViewById(R.id.ivArrow))
            .filter { it.visibility == View.VISIBLE }
        peers.forEachIndexed { index, view ->
            peers.drop(index + 1).forEach { other ->
                // 内存布局没有窗口坐标，必须转换到共同祖先，不能把默认 (0,0) 当成实际位置。
                val firstBounds = localBounds(row, view)
                val secondBounds = localBounds(row, other)
                val detail = "font=${row.resources.configuration.fontScale} rtl=${row.layoutDirection} " +
                    "row=${row.width}x${row.height} " +
                    "${view.resources.getResourceEntryName(view.id)}=$firstBounds " +
                    "${other.resources.getResourceEntryName(other.id)}=$secondBounds"
                assertFalse("设置文字与值/控件不能重叠：$detail", Rect.intersects(firstBounds, secondBounds))
            }
        }
    }

    private fun localBounds(parent: ViewGroup, child: View) = Rect(0, 0, child.width, child.height).also {
        parent.offsetDescendantRectToMyCoords(child, it)
    }

    private fun assertTouchTarget(view: View) {
        val minimum = 48 * view.resources.displayMetrics.density
        assertTrue("设置操作至少保留 48dp 热区", view.width >= minimum && view.height >= minimum)
    }

    private fun navigate(scenario: ActivityScenario<MainActivity>, target: NavTarget) {
        scenario.onActivity { ViewModelProvider(it)[MainViewModel::class.java].navigateTo(target) }
        await(scenario) { activity ->
            activity.supportFragmentManager.fragments.any { target.fragmentClass.isInstance(it) &&
                !it.isHidden && it.view?.let { view -> view.isShown && settled(view) } == true }
        }
    }

    private fun shown(activity: MainActivity): Fragment = activity.supportFragmentManager.fragments
        .first { !it.isHidden && it.view?.isShown == true }

    private fun measure(view: View, widthDp: Int) {
        val width = (widthDp * view.resources.displayMetrics.density).roundToInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, width, view.measuredHeight)
    }

    private fun bounds(view: View): Rect {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
    }

    private fun settled(view: View): Boolean = view.visibility == View.GONE ||
        (!view.isLayoutRequested && (view !is ViewGroup || (0 until view.childCount).all { settled(view.getChildAt(it)) }))

    private fun await(scenario: ActivityScenario<MainActivity>, ready: (MainActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000L
        do {
            instrumentation.waitForIdleSync()
            var complete = false
            scenario.onActivity { complete = ready(it) }
            if (complete) return
            SystemClock.sleep(30)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("设置/备份页面未完成布局")
    }
}
