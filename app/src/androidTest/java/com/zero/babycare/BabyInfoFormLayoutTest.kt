package com.zero.babycare

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/** 实际挂载生产资料布局；输入和选择回调均在内存，不创建宝宝或调用保存。 */
@RunWith(AndroidJUnit4::class)
class BabyInfoFormLayoutTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val rowFields = listOf(
        Triple(R.id.rowName, R.id.labelName, R.id.etName),
        Triple(R.id.rowBirthday, R.id.labelBirthday, R.id.etBirthday),
        Triple(R.id.rowGender, R.id.labelGender, R.id.etGender),
        Triple(R.id.rowWeight, R.id.labelWeight, R.id.etWeight),
        Triple(R.id.rowHeight, R.id.labelHeight, R.id.etHeight),
        Triple(R.id.rowBloodType, R.id.labelBloodType, R.id.etBloodType)
    )

    @Test
    fun englishProfileFieldsKeepWholeTextAndAccessibleBirthdayAtDefaultFont() = inspectWidths(fontScale = 1f)

    @Test
    fun englishProfileFieldsKeepWholeTextAndAccessibleBirthdayAtLargeFont() = inspectWidths(fontScale = 2f)

    private fun inspectWidths(fontScale: Float) {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val automation = instrumentation.uiAutomation
        val originalFlags = automation.serviceInfo.flags
        try {
            await(scenario) { ViewModelProvider(it)[MainViewModel::class.java].babyDataLoaded.value }
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            }
            // 本地 Context 覆盖主题和字号，不修改 Activity、系统配置或用户保存的宝宝主题。
            val themes = listOf("boy" to com.zero.common.R.style.AppTheme_Boy,
                "girl" to com.zero.common.R.style.AppTheme_Girl)
            themes.forEach { (themeName, theme) ->
                listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES).forEach { nightMode ->
                    listOf(320, 360).forEach { width ->
                        inspectForm(scenario, width, fontScale, themeName, theme, nightMode)
                    }
                }
            }
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
            scenario.close()
        }
    }

    private fun inspectForm(
        scenario: ActivityScenario<MainActivity>, widthDp: Int, fontScale: Float,
        themeName: String, theme: Int, nightMode: Int
    ) {
        lateinit var form: ViewGroup
        var siblings: List<Pair<View, Int>> = emptyList()
        val birthdayClicks = AtomicInteger()
        val birthday = "2026-01-08 09:35"
        val imageName = "baby-info-${widthDp}dp-font${fontScale.toInt()}-$themeName-" +
            if (nightMode == Configuration.UI_MODE_NIGHT_YES) "dark" else "light"
        scenario.onActivity { activity ->
            val configuration = Configuration(activity.resources.configuration).apply {
                this.fontScale = fontScale
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
                setLocale(Locale.US)
            }
            val context = ContextThemeWrapper(activity.createConfigurationContext(configuration), theme)
            val content = activity.findViewById<ViewGroup>(android.R.id.content)
            siblings = (0 until content.childCount).map { content.getChildAt(it) }
                .map { it to it.importantForAccessibility }
            siblings.forEach { (view, _) -> view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
            form = LayoutInflater.from(context).inflate(R.layout.fragment_update_info, content, false) as ViewGroup
            form.findViewById<TextView>(R.id.tvProfileTitle).text = "Alex"
            form.findViewById<TextView>(R.id.etName).text = "Alex"
            form.findViewById<TextView>(R.id.etBirthday).text = birthday
            form.findViewById<TextView>(R.id.etGender).setText(if (themeName == "boy")
                com.zero.common.R.string.boy else com.zero.common.R.string.girl)
            form.findViewById<TextView>(R.id.etWeight).text = "3.25"
            form.findViewById<TextView>(R.id.etHeight).text = "50.5"
            form.findViewById<TextView>(R.id.etBloodType).text = "AB"
            // 对应 Fragment 的两处日期点击绑定，只替换最终副作用，不打开用户资料或写入数据库。
            form.findViewById<View>(R.id.rowBirthday).setOnClickListener { birthdayClicks.incrementAndGet() }
            form.findViewById<View>(R.id.etBirthday).setOnClickListener { birthdayClicks.incrementAndGet() }
            val density = context.resources.displayMetrics.density
            content.addView(form, FrameLayout.LayoutParams((widthDp * density).roundToInt(),
                minOf(content.height, (640 * density).roundToInt())))
        }
        try {
            await(scenario) { form.isAttachedToWindow && settled(form) }
            saveScreenshot(scenario, form, "$imageName-top.png")
            val failures = mutableListOf<String>()
            scenario.onActivity {
                val header = form.findViewById<ViewGroup>(R.id.profileHeader)
                val headerLabels = listOf(R.id.tvProfileTitle, R.id.tvProfileSubtitle).map {
                    header.findViewById<TextView>(it)
                }
                headerLabels.forEach { collectTextFailures(header, it, widthDp, fontScale, failures) }
                val avatarBounds = localBounds(header, header.findViewById(R.id.profileAvatarContainer))
                headerLabels.forEach { text ->
                    if (Rect.intersects(avatarBounds, localBounds(header, text))) {
                        failures += "${name(text)} 与头像重叠：$avatarBounds / ${localBounds(header, text)}"
                    }
                }
                if (Rect.intersects(localBounds(header, headerLabels[0]), localBounds(header, headerLabels[1]))) {
                    failures += "资料标题与完整提示重叠"
                }
                // 一次列出所有受影响字段，基线不会只在首个省略标签处中断诊断。
                rowFields.forEach { (rowId, labelId, valueId) ->
                    val row = form.findViewById<ViewGroup>(rowId)
                    val peers = listOf(row.findViewById<TextView>(labelId), row.findViewById<TextView>(valueId))
                    peers.forEach { text -> collectTextFailures(row, text, widthDp, fontScale, failures) }
                    val first = localBounds(row, peers[0])
                    val second = localBounds(row, peers[1])
                    if (Rect.intersects(first, second)) failures += "${name(row)} 标签与输入重叠：$first / $second"
                    val minimum = (48 * row.resources.displayMetrics.density).roundToInt()
                    if (row.height < minimum) failures += "${name(row)} 行高不足 48dp：${row.height} < $minimum"
                }
            }
            assertTrue("资料表单须完整显示：$imageName\n${failures.joinToString("\n")}", failures.isEmpty())

            rowFields.forEach { (rowId, _, valueId) ->
                scenario.onActivity {
                    val value = form.findViewById<View>(valueId)
                    value.requestRectangleOnScreen(Rect(0, 0, value.width, value.height), true)
                }
                await(scenario) { settled(form) }
                scenario.onActivity {
                    val value = form.findViewById<View>(valueId)
                    val viewport = Rect()
                    form.findViewById<NestedScrollView>(R.id.scrollBabyInfo).getGlobalVisibleRect(viewport)
                    val target = globalBounds(value)
                    assertTrue("资料输入须能完整滚入视口：${name(value)}=$target viewport=$viewport", viewport.contains(target))
                    assertTrue("资料字段必须可操作", value.isEnabled)
                    if (rowId == R.id.rowBirthday) {
                        val minimum = (48 * value.resources.displayMetrics.density).roundToInt()
                        assertTrue("日期选择入口须至少 48dp 高", value.height >= minimum)
                    }
                }
                if (rowId == R.id.rowBirthday) assertBirthdayNode(birthday, birthdayClicks,
                    form.context.getString(com.zero.common.R.string.babyBirthday),
                    form.context.getString(com.zero.common.R.string.baby_profile_select_birthday))
            }
            saveScreenshot(scenario, form, "$imageName-bottom.png")
        } finally {
            scenario.onActivity {
                (form.parent as? ViewGroup)?.removeView(form)
                siblings.forEach { (view, flag) -> view.importantForAccessibility = flag }
            }
        }
    }

    /** 截图只绘制合成表单，避免把下方 Activity 的真实宝宝资料写入审查文件。 */
    private fun saveScreenshot(scenario: ActivityScenario<MainActivity>, form: View, filename: String) {
        if (InstrumentationRegistry.getArguments().getString("captureScreenshots") != "true") return
        lateinit var bitmap: Bitmap
        scenario.onActivity {
            bitmap = Bitmap.createBitmap(form.width, form.height, Bitmap.Config.ARGB_8888)
            form.draw(Canvas(bitmap))
        }
        try {
            val directory = File(instrumentation.targetContext.filesDir, "audit-screens")
            check(directory.isDirectory || directory.mkdirs()) { "无法创建验证截图目录" }
            File(directory, filename).outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "无法保存合成资料截图" }
            }
        } finally { bitmap.recycle() }
    }

    private fun collectTextFailures(row: ViewGroup, text: TextView, width: Int, scale: Float, failures: MutableList<String>) {
        val layout = text.layout
        if (layout == null || layout.lineCount == 0) {
            failures += "${name(row)}/${name(text)} 没有文字布局"
            return
        }
        val availableWidth = text.width - text.compoundPaddingLeft - text.compoundPaddingRight
        val availableHeight = text.height - text.compoundPaddingTop - text.compoundPaddingBottom
        val bounds = localBounds(row, text)
        val detail = "${name(row)}/${name(text)} widthDp=$width font=$scale view=${text.width}x${text.height} " +
            "available=${availableWidth}x$availableHeight layout=${layout.width}x${layout.height} " +
            "bounds=$bounds row=${row.width}x${row.height} text=[${text.text}]"
        if (layout.getLineEnd(layout.lineCount - 1) != text.text.length) failures += "全文未排版：$detail"
        if (layout.height > availableHeight) failures += "文字高度裁切：$detail"
        for (line in 0 until layout.lineCount) {
            if (layout.getEllipsisCount(line) != 0) failures += "文字被省略：$detail line=$line"
            if (layout.getLineLeft(line) < -1f || layout.getLineRight(line) > availableWidth + 1f) {
                failures += "文字横向裁切：$detail line=$line left=${layout.getLineLeft(line)} right=${layout.getLineRight(line)}"
            }
        }
        if (bounds.left < row.paddingLeft || bounds.right > row.width - row.paddingRight ||
            bounds.top < row.paddingTop || bounds.bottom > row.height - row.paddingBottom) {
            failures += "文字超出表单行内边距：$detail"
        }
    }

    private fun assertBirthdayNode(expected: String, clicks: AtomicInteger, expectedLabel: String, expectedHint: String) {
        instrumentation.waitForIdleSync()
        val root = requireNotNull(instrumentation.uiAutomation.rootInActiveWindow)
        try {
            val nodes = root.findAccessibilityNodeInfosByViewId("com.zero.babycare:id/etBirthday")
            try {
                val node = requireNotNull(nodes.firstOrNull { it.isVisibleToUser }) { "日期必须提供可见的真实无障碍节点" }
                assertEquals("读屏必须保留完整出生日期和时间", expected, node.text?.toString())
                val label = node.labeledBy
                try {
                    assertEquals("日期值须关联明确的字段身份", expectedLabel, label?.text?.toString())
                } finally { label?.recycle() }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    assertEquals("日期节点仍保留原操作提示", expectedHint, node.hintText?.toString())
                }
                assertTrue("日期节点必须有点击动作", node.isClickable)
                assertTrue("读屏点击日期入口必须成功", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                instrumentation.waitForIdleSync()
                assertEquals("日期选择动作只进入内存回调一次", 1, clicks.get())
            } finally { nodes.forEach { it.recycle() } }
        } finally { root.recycle() }
    }

    private fun name(view: View) = view.resources.getResourceEntryName(view.id)

    private fun localBounds(parent: ViewGroup, child: View) = Rect(0, 0, child.width, child.height).also {
        parent.offsetDescendantRectToMyCoords(child, it)
    }

    private fun globalBounds(view: View): Rect {
        val position = IntArray(2).also(view::getLocationOnScreen)
        return Rect(position[0], position[1], position[0] + view.width, position[1] + view.height)
    }

    private fun settled(view: View): Boolean = view.visibility == View.GONE ||
        (!view.isLayoutRequested && (view !is ViewGroup || (0 until view.childCount).all { settled(view.getChildAt(it)) }))

    private fun await(scenario: ActivityScenario<MainActivity>, condition: (MainActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000L
        do {
            instrumentation.waitForIdleSync()
            var ready = false
            scenario.onActivity { ready = condition(it) }
            if (ready) return
            SystemClock.sleep(30L)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("资料表单测试视图未完成布局")
    }
}
