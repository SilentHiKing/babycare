package com.zero.babycare

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.babycare.babies.AllChildrenFragment
import com.zero.babycare.babies.BabyAgeReference
import com.zero.babycare.babies.BabyCardUiMapper
import com.zero.babydata.entity.BabyInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** 实际宝宝卡 Adapter 在窄窗、大字与缺失生日下完整排版，同时暴露当前宝宝的读屏状态。 */
@RunWith(AndroidJUnit4::class)
class BabyCardLayoutTest {
    @Test
    fun babyDetailsStayAboveActionsAndSelectedStateIsAccessible() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            awaitInitialData(scenario)
            val themes = listOf("boy" to com.zero.common.R.style.AppTheme_Boy,
                "girl" to com.zero.common.R.style.AppTheme_Girl)
            themes.forEach { (gender, theme) ->
                listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES).forEach { nightMode ->
                    listOf(1f, 2f).forEach { fontScale ->
                        assertCardStates(scenario, gender, theme, nightMode, fontScale)
                    }
                }
            }
        } finally {
            scenario.close()
        }
    }

    private fun assertCardStates(
        scenario: ActivityScenario<MainActivity>, gender: String, theme: Int, nightMode: Int, fontScale: Float
    ) {
        lateinit var host: RecyclerView
        lateinit var adapter: AllChildrenFragment.BabyListAdapter
        scenario.onActivity { activity ->
            val config = Configuration(activity.resources.configuration).apply {
                this.fontScale = fontScale
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
                setLocale(Locale.US)
            }
            val context = ContextThemeWrapper(activity.createConfigurationContext(config), theme)
            adapter = AllChildrenFragment().BabyListAdapter()
            host = RecyclerView(context).apply {
                layoutManager = LinearLayoutManager(context)
                itemAnimator = null
                overScrollMode = View.OVER_SCROLL_NEVER
                this.adapter = adapter
            }
            val width = (288 * context.resources.displayMetrics.density).roundToInt()
            activity.findViewById<ViewGroup>(android.R.id.content)
                .addView(host, FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        try {
            val today = LocalDate.of(2026, 9, 20)
            val zone = ZoneId.of("UTC")
            listOf(true, false).forEach { hasBirthday ->
                listOf(true, false).forEach { selected ->
                    val model = BabyCardUiMapper.map(
                        BabyInfo(babyId = 1, name = "Alexandra Audit", gender = gender, birthDate =
                            if (hasBirthday) today.minusDays(365).atStartOfDay(zone).toInstant().toEpochMilli() else 0),
                        if (selected) 1 else 2,
                        BabyAgeReference(today, zone)
                    )
                    // 每次绑定交给 RecyclerView 的真实帧，不能在手动测量后提前读取子 View 边界。
                    val frame = CountDownLatch(1)
                    val listener = object : ViewTreeObserver.OnPreDrawListener {
                        override fun onPreDraw(): Boolean {
                            if (!hasPendingLayout(host) && !host.hasPendingAdapterUpdates() &&
                                host.findViewHolderForAdapterPosition(0) != null) {
                                host.viewTreeObserver.removeOnPreDrawListener(this)
                                frame.countDown()
                            }
                            return true
                        }
                    }
                    try {
                        scenario.onActivity {
                            host.viewTreeObserver.addOnPreDrawListener(listener)
                            adapter.submitList(listOf(model))
                        }
                        assertTrue("宝宝卡片未完成真实布局帧：$gender/$nightMode/$fontScale/$hasBirthday/$selected",
                            frame.await(10, TimeUnit.SECONDS))
                        scenario.onActivity { assertRenderedCard(host, gender, hasBirthday, selected) }
                    } catch (failure: AssertionError) {
                        val evidence = captureEvidence(scenario, host)
                        throw AssertionError("${failure.message}\n合成卡片证据：$evidence", failure)
                    } finally {
                        scenario.onActivity {
                            if (host.viewTreeObserver.isAlive) host.viewTreeObserver.removeOnPreDrawListener(listener)
                        }
                    }
                }
            }
        } finally {
            scenario.onActivity {
                host.adapter = null
                (host.parent as? ViewGroup)?.removeView(host)
            }
        }
    }

    private fun assertRenderedCard(host: RecyclerView, gender: String, hasBirthday: Boolean, selected: Boolean) {
        val root = requireNotNull(host.findViewHolderForAdapterPosition(0)).itemView
        val context = root.context
        assertTrue("无障碍校验必须使用已挂载的卡片", root.isAttachedToWindow)
        val labels = listOf(R.id.tvName, R.id.tvInfo, R.id.tvBirthDate).map { root.findViewById<TextView>(it) }
            .filter { it.visibility == View.VISIBLE }
        val actions = root.findViewById<View>(R.id.actionRow)
        labels.forEach { text ->
            val layout = requireNotNull(text.layout)
            // 文字可位于内部容器，重叠判断统一换算到卡片坐标，不能误用各自父布局坐标。
            val bounds = Rect(0, 0, text.width, text.height)
            (root as ViewGroup).offsetDescendantRectToMyCoords(text, bounds)
            // 保留精确尺寸，区分复用测量缓存与真实文本裁切，不用放宽断言掩盖问题。
            val details = "字段=${context.resources.getResourceEntryName(text.id)}，主题=$gender，" +
                "夜间=${context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK}，" +
                "字号=${context.resources.configuration.fontScale}，生日=$hasBirthday，选中=$selected，" +
                "layoutHeight=${layout.height}，height=${text.height}，measuredHeight=${text.measuredHeight}，" +
                "padding=${text.compoundPaddingTop}/${text.compoundPaddingBottom}，" +
                "width=${text.width}，layoutWidth=${layout.width}，lines=${layout.lineCount}，" +
                "textSize=${text.textSize}，fontPadding=${text.includeFontPadding}，" +
                "host=${host.width}x${host.height}，card=${root.width}x${root.height}，" +
                "top=${bounds.top}，bottom=${bounds.bottom}，actionsTop=${actions.top}，" +
                "parent=${viewIdentity(text.parent as? View)}，" +
                "layoutRequested=${text.isLayoutRequested}/${(text.parent as? View)?.isLayoutRequested}/${root.isLayoutRequested}/${host.isLayoutRequested}，" +
                "computingLayout=${host.isComputingLayout}，pendingAdapterUpdates=${host.hasPendingAdapterUpdates()}"
            assertEquals("宝宝信息必须完整排版；$details", text.text.length, layout.getLineEnd(layout.lineCount - 1))
            for (line in 0 until layout.lineCount) assertEquals("宝宝信息不能省略；$details", 0, layout.getEllipsisCount(line))
            assertTrue("文字不能在卡片顶部被裁切；$details", bounds.top >= root.paddingTop)
            assertTrue("文字不能与操作区重叠；$details", bounds.bottom <= actions.top)
            assertTrue("文字高度必须容纳全部行；$details", layout.height <= text.height - text.compoundPaddingTop - text.compoundPaddingBottom)
        }
        assertTrue("操作行必须避开头像", actions.top >= root.findViewById<View>(R.id.ivAvatar).bottom)
        assertTrue("操作行完整位于卡片内", actions.bottom <= root.height - root.paddingBottom)
        assertEquals("Adapter 必须更新卡片本身的选中状态", selected, root.isSelected)
        val node = AccessibilityNodeInfoCompat.wrap(root.createAccessibilityNodeInfo())
        assertEquals(selected, node.isSelected)
        assertEquals(if (selected) context.getString(com.zero.common.R.string.current_baby) else null,
            node.stateDescription?.toString())
    }

    private fun hasPendingLayout(view: View): Boolean = view.visibility != View.GONE &&
        (view.isLayoutRequested || (view is ViewGroup && (0 until view.childCount).any {
            hasPendingLayout(view.getChildAt(it))
        }))

    private fun viewIdentity(view: View?): String = view?.let {
        val id = if (it.id == View.NO_ID) "no-id" else runCatching {
            it.resources.getResourceEntryName(it.id)
        }.getOrDefault(it.id.toString())
        "${it.javaClass.simpleName}/$id"
    } ?: "none"

    /** 只保存合成 RecyclerView；绘制在主线程，文件写入在测试线程，不采集真实宝宝内容。 */
    private fun captureEvidence(scenario: ActivityScenario<MainActivity>, host: RecyclerView): String {
        lateinit var bitmap: Bitmap
        lateinit var hierarchy: String
        scenario.onActivity {
            hierarchy = buildString {
                fun appendView(view: View, depth: Int) {
                    appendLine("${"  ".repeat(depth)}${viewIdentity(view)} visibility=${view.visibility} " +
                        "bounds=${view.left},${view.top},${view.right},${view.bottom} " +
                        "measured=${view.measuredWidth}x${view.measuredHeight} requested=${view.isLayoutRequested}")
                    if (view is ViewGroup) (0 until view.childCount).forEach { appendView(view.getChildAt(it), depth + 1) }
                }
                appendView(host, 0)
            }
            bitmap = Bitmap.createBitmap(host.width.coerceAtLeast(1), host.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            host.draw(Canvas(bitmap))
        }
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "baby-card-layout-audit")
        try {
            check(directory.isDirectory || directory.mkdirs())
            File(directory, "failure.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            File(directory, "hierarchy.txt").writeText(hierarchy)
        } finally { bitmap.recycle() }
        return "$directory\n$hierarchy"
    }

    /** 等待初次宝宝加载和主题同步完成，避免测试容器在首次切换宝宝主题时随 Activity 销毁。 */
    private fun awaitInitialData(scenario: ActivityScenario<MainActivity>) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.uptimeMillis() + 10_000
        do {
            instrumentation.waitForIdleSync()
            var ready = false
            scenario.onActivity { ready = ViewModelProvider(it)[MainViewModel::class.java].babyDataLoaded.value }
            if (ready) {
                instrumentation.waitForIdleSync()
                return
            }
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("宝宝初始状态未就绪")
    }
}
