package com.zero.babycare

import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.babycare.home.DashboardFragment
import com.zero.babycare.home.DashboardUiState
import com.zero.babycare.home.DashboardViewModel
import com.zero.babycare.home.QuickRecordItem
import com.zero.babycare.home.bean.DashboardData
import com.zero.babycare.home.prediction.PredictionResult
import com.zero.babycare.navigation.NavTarget
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Date

/** 使用宿主真实语言、字号与窗口；合成状态只进入 UI 流，不切换宝宝、不写业务库或计时会话。 */
@RunWith(AndroidJUnit4::class)
class DashboardFirstScreenTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var renderVersion = 0L
    private var renderedCase = "initial"

    @Test
    fun quickEntriesFitFirstScreenOrRemainReachableWithLargeFonts() = withDashboard { scenario ->
        val now = System.currentTimeMillis()
        val states = listOf(DashboardData(), ordinaryData(now))
        states.forEach { data ->
            render(scenario, data, now)
            scenario.onActivity { activity ->
                val root = dashboard(activity).requireView()
                assertAllCardTextFits(root, data)
                val list = root.findViewById<RecyclerView>(R.id.rvQuickRecord)
                assertEquals("四个快速记录入口必须全部布局", 4, list.childCount)
                if (activity.resources.configuration.fontScale <= 1.05f) {
                    // 检查整个可点击行和标签，不允许只露出卡片标题或图标的一部分。
                    for (position in 0 until 4) assertFullyVisible(activity, item(list, position))
                }
            }
            for (position in 0 until 4) {
                scenario.onActivity { activity ->
                    val target = item(dashboard(activity).requireView().findViewById(R.id.rvQuickRecord), position)
                    target.requestRectangleOnScreen(Rect(0, 0, target.width, target.height), true)
                }
                awaitLayout(scenario)
                scenario.onActivity { activity ->
                    val target = item(dashboard(activity).requireView().findViewById(R.id.rvQuickRecord), position)
                    assertTouchTarget(target)
                    assertFullyVisible(activity, target)
                    tap(target)
                }
                await(scenario, "快捷入口必须发出导航") { mainVm(it).navTarget.value != NavTarget.Dashboard }
                scenario.onActivity { activity ->
                    val target = mainVm(activity).navTarget.value
                    val expectedCategory = QuickRecordItem.getQuickRecordItems()[position].categoryId
                    assertTrue("快捷入口必须进入对应记录类别；空库允许先完善宝宝信息",
                        (target is NavTarget.EventRecord && target.preSelectedCategory == expectedCategory) ||
                            (mainVm(activity).currentBaby.value == null && target is NavTarget.BabyInfo))
                    mainVm(activity).navigateTo(NavTarget.Dashboard)
                }
                awaitDashboard(scenario)
                // 返回时会触发真实 VM 刷新，重新注入同一个合成状态后再检查下一个入口。
                render(scenario, data, now)
            }
        }
    }

    @Test
    fun ongoingTimeAndEndActionNeverOverlapOrClip() = withDashboard { scenario ->
        val now = System.currentTimeMillis()
        listOf(DashboardData.BabyStatus.SLEEPING, DashboardData.BabyStatus.FEEDING).forEach { status ->
            val data = ordinaryData(now).copy(currentStatus = status,
                ongoingSleepDurationMillis = 5_025_000L, ongoingFeedingDurationMillis = 5_025_000L)
            render(scenario, data, now)
            scenario.onActivity { activity ->
                val root = dashboard(activity).requireView()
                assertAllCardTextFits(root, data)
                val action = root.findViewById<View>(R.id.tvStatusAction)
                assertEquals("进行中状态必须提供结束入口", View.VISIBLE, action.visibility)
                assertTouchTarget(action)
                listOf(R.id.tvStatusTitle, R.id.tvStatusTime).forEach { id ->
                    assertFalse("结束入口不能覆盖当前状态或计时文字",
                        Rect.intersects(fullBounds(action), fullBounds(root.findViewById(id))))
                }
                action.requestRectangleOnScreen(Rect(0, 0, action.width, action.height), true)
            }
            awaitLayout(scenario)
            scenario.onActivity { activity ->
                assertFullyVisible(activity, dashboard(activity).requireView().findViewById(R.id.tvStatusAction))
            }
        }
    }

    @Test
    fun recordHistoryEntryIsReachableAndOpensStatisticsWithDashboardReturn() = withDashboard { scenario ->
        render(scenario, DashboardData(), System.currentTimeMillis())
        scenario.onActivity { activity ->
            val root = dashboard(activity).requireView()
            val entry = root.findViewById<View>(R.id.cardRecords)
            assertTrue("回看入口必须排在快速记录之后",
                fullBounds(entry).top >= fullBounds(root.findViewById(R.id.cardQuickRecord)).bottom)
            assertEquals(activity.getString(com.zero.common.R.string.daily_records),
                root.findViewById<TextView>(R.id.tvRecordsTitle).text.toString())
            assertEquals(activity.getString(com.zero.common.R.string.data_statistics),
                root.findViewById<TextView>(R.id.tvRecordsSummary).text.toString())
            entry.requestRectangleOnScreen(Rect(0, 0, entry.width, entry.height), true)
        }
        awaitLayout(scenario)
        scenario.onActivity { activity ->
            val root = dashboard(activity).requireView()
            val entry = root.findViewById<View>(R.id.cardRecords)
            assertAllCardTextFits(root, DashboardData())
            assertTouchTarget(entry)
            assertFullyVisible(activity, entry)
            val node = entry.createAccessibilityNodeInfo()
            try {
                assertEquals("显式入口必须具有按钮语义", Button::class.java.name, node.className.toString())
                assertTrue("标题和说明应聚合为一个读屏焦点",
                    AccessibilityNodeInfoCompat.wrap(node).isScreenReaderFocusable)
                assertTrue("读屏必须能够执行入口点击", node.isClickable)
            } finally {
                @Suppress("DEPRECATION")
                node.recycle()
            }
            // 同步检查导航意图，不依赖用户库是否有宝宝，也不为测试创建业务数据。
            assertTrue(entry.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
            assertEquals(NavTarget.Statistics(returnTarget = NavTarget.Dashboard), mainVm(activity).navTarget.value)
            mainVm(activity).navigateTo(NavTarget.Dashboard)
        }
    }

    private fun ordinaryData(now: Long) = DashboardData(
        lastFeedingEndTime = now - 135 * 60_000L, feedingCount = 5, displayFeedingMinutes = 80,
        lastSleepEndTime = now - 35 * 60_000L, sleepCount = 3, displaySleepMinutes = 270,
        feedingPrediction = prediction(now, 35), sleepPrediction = prediction(now, 120)
    )

    private fun prediction(now: Long, minutes: Int) = PredictionResult(
        predictedTime = Date(now + minutes * 60_000L), confidence = 0.8f,
        earliestTime = Date(now + (minutes - 15) * 60_000L),
        latestTime = Date(now + (minutes + 15) * 60_000L), evidenceCount = 6
    )

    private fun render(scenario: ActivityScenario<MainActivity>, data: DashboardData, now: Long) {
        renderedCase = when {
            data == DashboardData() -> "empty"
            data.currentStatus == DashboardData.BabyStatus.SLEEPING -> "ongoing_sleep"
            data.currentStatus == DashboardData.BabyStatus.FEEDING -> "ongoing_feeding"
            else -> "ordinary"
        }
        scenario.onActivity { activity ->
            val vm = dashboardVm(activity)
            // 隔离已在途查询和分钟 ticker，避免真实数据晚到替换合成内容；finally 恢复原快照。
            (field(vm, "loadJob").get(vm) as? Job)?.cancel()
            field(vm, "loadedSnapshot").set(vm, null)
            // 返回首页会重设 Toolbar；递增毫秒版本保证相同样本也重新交给真实 renderer。
            uiFlow(vm).value = DashboardUiState.Content(Int.MAX_VALUE, "Layout Audit", data, now + ++renderVersion)
            dashboard(activity).requireView().findViewById<NestedScrollView>(R.id.contentScroll).scrollTo(0, 0)
        }
        await(scenario, "合成首页未完成渲染") { activity ->
            val root = dashboard(activity).view ?: return@await false
            root.findViewById<TextView>(com.zero.components.R.id.tvIdentityTitle)?.text?.toString() == "Layout Audit" &&
                root.findViewById<View>(R.id.contentScroll).visibility == View.VISIBLE && settled(root)
        }
        awaitLayout(scenario)
    }

    private fun assertAllCardTextFits(root: View, data: DashboardData) {
        try {
            assertCardTextGeometry(root, data)
        } catch (error: AssertionError) {
            throw AssertionError("${error.message}\n${layoutDiagnostics(root)}", error)
        }
    }

    private fun assertCardTextGeometry(root: View, data: DashboardData) {
        listOf(R.id.cardStatus, R.id.cardFeeding, R.id.cardSleep, R.id.cardPrediction, R.id.cardQuickRecord,
            R.id.cardRecords)
            .forEach { cardId ->
                val card = root.findViewById<ViewGroup>(cardId)
                val labels = visibleLabels(card).filter { it.text.isNotEmpty() }
                labels.forEach { label ->
                    val id = label.resources.getResourceEntryName(label.id)
                    val layout = requireNotNull(label.layout)
                    val details = "$id: view=${label.width}x${label.height}, layout=${layout.width}x${layout.height}"
                    val contentWidth = label.width - label.compoundPaddingLeft - label.compoundPaddingRight
                    assertTrue("非空文字必须得到有效宽度：$details", contentWidth > 0)
                    assertTrue("文字必须有排版结果：$details", layout.lineCount > 0)
                    assertEquals("全部文字必须参与排版：$details", label.text.length, layout.getLineEnd(layout.lineCount - 1))
                    assertTrue("文字不能被固定高度裁切：$details",
                        layout.height <= label.height - label.compoundPaddingTop - label.compoundPaddingBottom)
                    for (line in 0 until layout.lineCount) {
                        assertEquals("文字不能省略：$details", 0, layout.getEllipsisCount(line))
                        // 与最终 View 内容宽比较，旧 Layout 缓存或被压成零宽时不能自证通过。
                        assertTrue("文字不能横向越界：$details", layout.getLineWidth(line) <= contentWidth + 1f)
                    }
                    val bounds = Rect(0, 0, label.width, label.height)
                    card.offsetDescendantRectToMyCoords(label, bounds)
                    assertTrue("文字必须完整位于卡片内容区：$details bounds=$bounds",
                        bounds.left >= card.paddingLeft && bounds.right <= card.width - card.paddingRight &&
                            bounds.top >= card.paddingTop && bounds.bottom <= card.height - card.paddingBottom)
                }
                labels.forEachIndexed { index, label ->
                    labels.drop(index + 1).forEach { other ->
                        assertFalse("同一卡片中的文字不能相互覆盖",
                            Rect.intersects(fullBounds(label), fullBounds(other)))
                    }
                }
            }
        assertPredictionRows(root, data)
    }

    private fun assertPredictionRows(root: View, data: DashboardData) {
        val rows = listOf(
            Triple(R.id.tvPredictFeedingTime, R.id.tvPredictFeedingRemain, R.id.tvPredictFeedingEvidence) to
                (data.feedingPrediction != null && data.currentStatus != DashboardData.BabyStatus.FEEDING),
            Triple(R.id.tvPredictSleepTime, R.id.tvPredictSleepRemain, R.id.tvPredictSleepEvidence) to
                (data.sleepPrediction != null && data.currentStatus != DashboardData.BabyStatus.SLEEPING)
        )
        rows.filter { it.second }.forEach { (ids, _) ->
            val title = root.findViewById<TextView>(ids.first)
            val remaining = root.findViewById<TextView>(ids.second)
            val evidence = root.findViewById<TextView>(ids.third)
            listOf(title, remaining, evidence).forEach {
                assertEquals("有效预测的时间、剩余时间与依据不能被隐藏", View.VISIBLE, it.visibility)
                assertTrue("有效预测字段不能为空", it.text.isNotBlank())
            }
            // 允许大字换行，但至少一个完整时钟值应能放进主列，不能挤成逐字符竖排。
            val clockWidths = Regex("\\d{2}:\\d{2}").findAll(title.text).map { title.paint.measureText(it.value) }.toList()
            assertTrue("有效预测必须显示时钟区间", clockWidths.isNotEmpty())
            val available = title.width - title.compoundPaddingLeft - title.compoundPaddingRight
            assertTrue("预测主列必须容纳一个完整时钟值：width=$available",
                available + 1f >= clockWidths.max())
            assertTrue("预测依据必须位于时间和剩余时间下方",
                fullBounds(evidence).top >= maxOf(fullBounds(title).bottom, fullBounds(remaining).bottom))
        }
    }

    private fun visibleLabels(view: View): List<TextView> = when {
        view.visibility != View.VISIBLE -> emptyList()
        view is TextView -> listOf(view)
        view is ViewGroup -> (0 until view.childCount).flatMap { visibleLabels(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun assertTouchTarget(view: View) {
        val minimum = 48 * view.resources.displayMetrics.density
        assertTrue("快捷操作必须保留至少48dp宽高", view.width >= minimum && view.height >= minimum)
        assertTrue("快捷操作必须已启用并可点击", view.isEnabled && view.isClickable)
    }

    private fun assertFullyVisible(activity: MainActivity, view: View) {
        val full = fullBounds(view)
        val visible = Rect()
        val diagnostic = layoutDiagnostics(dashboard(activity).requireView())
        assertTrue("入口必须处于可见窗口\n$diagnostic", view.getGlobalVisibleRect(visible))
        assertEquals("入口和标签不能只显示一部分\n$diagnostic", full, visible)
        val decor = activity.window.decorView
        val safe = safeBounds(decor)
        assertTrue("入口必须完整处于系统安全区：bounds=$full safe=$safe\n$diagnostic", safe.contains(full))
    }

    private fun safeBounds(decor: View): Rect {
        val safe = fullBounds(decor)
        val insets = requireNotNull(ViewCompat.getRootWindowInsets(decor))
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        safe.left += bars.left
        safe.top += bars.top
        safe.right -= bars.right
        safe.bottom -= maxOf(bars.bottom, insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
        return safe
    }

    /** 仅在失败时报告合成场景与几何，不输出宝宝身份或记录内容。 */
    private fun layoutDiagnostics(root: View): String = buildString {
        val config = root.resources.configuration
        append("case=$renderedCase version=$renderVersion locale=${config.locales.toLanguageTags()}")
        append(" fontScale=${config.fontScale} density=${root.resources.displayMetrics.density}")
        append(" safe=${safeBounds(root.rootView)}\n")
        val ids = listOf(R.id.contentScroll, R.id.cardStatus, R.id.cardFeeding, R.id.cardSleep,
            R.id.cardPrediction, R.id.rowPredictFeeding, R.id.rowPredictSleep, R.id.cardQuickRecord,
            R.id.rvQuickRecord, R.id.cardRecords)
        ids.forEach { id ->
            root.findViewById<View>(id)?.let { child ->
                val visible = Rect()
                child.getGlobalVisibleRect(visible)
                append("${child.resources.getResourceEntryName(id)} full=${fullBounds(child)} visible=$visible")
                append(" padding=${child.paddingLeft},${child.paddingTop},${child.paddingRight},${child.paddingBottom}")
                append(" scrollY=${child.scrollY}\n")
                if (child is RecyclerView) {
                    for (index in 0 until child.childCount) {
                        val item = child.getChildAt(index)
                        item.getGlobalVisibleRect(visible)
                        append("quick[${child.getChildAdapterPosition(item)}] full=${fullBounds(item)} visible=$visible\n")
                    }
                }
            }
        }
    }

    private fun tap(view: View) {
        val time = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, view.width / 2f, view.height / 2f, 0)
        val up = MotionEvent.obtain(time, time + 40, MotionEvent.ACTION_UP, view.width / 2f, view.height / 2f, 0)
        try {
            assertTrue("入口必须接收按下事件", view.dispatchTouchEvent(down))
            assertTrue("入口必须接收点击事件", view.dispatchTouchEvent(up))
        } finally { down.recycle(); up.recycle() }
    }

    private fun withDashboard(block: (ActivityScenario<MainActivity>) -> Unit) {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var originalState: DashboardUiState? = null
        var originalSnapshot: Any? = null
        var originalScrollY = 0
        var originalVm: DashboardViewModel? = null
        try {
            await(scenario, "首页初始数据未就绪") { mainVm(it).babyDataLoaded.value }
            scenario.onActivity { mainVm(it).navigateTo(NavTarget.Dashboard) }
            awaitDashboard(scenario)
            scenario.onActivity { activity ->
                originalVm = dashboardVm(activity).also { vm ->
                    originalState = vm.dashboardUiState.value
                    originalSnapshot = field(vm, "loadedSnapshot").get(vm)
                }
                originalScrollY = dashboard(activity).requireView().findViewById<NestedScrollView>(R.id.contentScroll).scrollY
            }
            block(scenario)
        } finally {
            try {
                scenario.onActivity { activity ->
                    mainVm(activity).navigateTo(NavTarget.Dashboard)
                    originalVm?.let { vm ->
                        (field(vm, "loadJob").get(vm) as? Job)?.cancel()
                        field(vm, "loadedSnapshot").set(vm, originalSnapshot)
                        originalState?.let { uiFlow(vm).value = it }
                    }
                    dashboard(activity).view?.findViewById<NestedScrollView>(R.id.contentScroll)?.scrollTo(0, originalScrollY)
                }
            } finally { scenario.close() }
        }
    }

    private fun item(list: RecyclerView, position: Int) =
        requireNotNull(list.findViewHolderForAdapterPosition(position)).itemView

    private fun fullBounds(view: View): Rect {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
    }

    private fun settled(view: View): Boolean = view.visibility == View.GONE ||
        (!view.isLayoutRequested && (view !is RecyclerView || !view.hasPendingAdapterUpdates()) &&
            (view !is ViewGroup || (0 until view.childCount).all { settled(view.getChildAt(it)) }))

    private fun awaitLayout(scenario: ActivityScenario<MainActivity>) =
        await(scenario, "首页布局未稳定") { settled(dashboard(it).requireView()) }

    private fun awaitDashboard(scenario: ActivityScenario<MainActivity>) =
        await(scenario, "首页未显示") { activity ->
            activity.supportFragmentManager.fragments.filterIsInstance<DashboardFragment>().any { !it.isHidden && it.view?.isShown == true } &&
                dashboardVm(activity).dashboardUiState.value != DashboardUiState.Loading
        }

    private fun dashboard(activity: MainActivity) = activity.supportFragmentManager.fragments.filterIsInstance<DashboardFragment>().single()
    private fun dashboardVm(activity: MainActivity) = ViewModelProvider(dashboard(activity))[DashboardViewModel::class.java]
    private fun mainVm(activity: MainActivity) = ViewModelProvider(activity)[MainViewModel::class.java]
    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    private fun uiFlow(vm: DashboardViewModel) = field(vm, "_dashboardUiState").get(vm) as MutableStateFlow<DashboardUiState>

    private fun await(scenario: ActivityScenario<MainActivity>, message: String, ready: (MainActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000L
        do {
            instrumentation.waitForIdleSync()
            var complete = false
            scenario.onActivity { complete = ready(it) }
            if (complete) return
            SystemClock.sleep(30L)
        } while (SystemClock.uptimeMillis() < deadline)
        var diagnostic = ""
        scenario.onActivity { activity ->
            activity.supportFragmentManager.fragments.filterIsInstance<DashboardFragment>()
                .firstOrNull()?.view?.let { diagnostic = layoutDiagnostics(it) }
        }
        throw AssertionError("$message\n$diagnostic")
    }
}
