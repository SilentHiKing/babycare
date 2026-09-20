package com.zero.babycare.home.record

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.Application
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.babycare.MainActivity
import com.zero.babycare.MainViewModel
import com.zero.babycare.R
import com.zero.babycare.home.record.event.EventRecordFragment
import com.zero.babycare.home.record.event.EventRecordViewModel
import com.zero.babycare.home.record.event.EventSubtype
import com.zero.babycare.navigation.NavTarget
import com.zero.babydata.entity.BabyInfo
import com.zero.babydata.entity.EventType
import com.zero.babydata.entity.FeedingRecord
import com.zero.babydata.entity.FeedingType
import com.zero.babydata.room.BabyDatabase
import com.zero.babydata.room.BabyRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 实际页面操作仅依赖隔离内存库；AVD 上现有合成宝宝只提供导航身份，不修改业务数据。 */
@RunWith(AndroidJUnit4::class)
class RecordFieldFeedbackTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun emptyMedicineNameReturnsFromNoteToPersistentFieldError() = withScreen { scenario, repository, babyId ->
        assertEventFieldFeedback(scenario, repository, babyId,
            EventType.HEALTH_MEDICINE, R.id.etMedicineName, "", com.zero.common.R.string.medicine_name_hint)
    }

    @Test
    fun unfinishedGrowthNumberReturnsFromNoteToPersistentFieldError() = withScreen { scenario, repository, babyId ->
        assertEventFieldFeedback(scenario, repository, babyId,
            EventType.GROWTH_WEIGHT, R.id.etValue, ".", com.zero.common.R.string.event_value_required)
    }

    @Test
    fun breastDurationNodesKeepDistinctLabelsWhenEmptyAndFilled() = withScreen { scenario, repository, babyId ->
        // 编辑隔离记录，不进入或改变设备已有的实时喂养会话。
        val record = FeedingRecord(feedingId = RECORD_ID, babyId = babyId,
            feedingType = FeedingType.BREAST.type, feedingStart = START,
            feedingEnd = START + 600_000L, feedingDuration = 600_000L,
            feedingDurationBreastLeft = 120_000L, feedingDurationBreastRight = 120_000L)
        runBlocking { repository.insertFeedingRecord(record) }
        val target = NavTarget.FeedingRecord(record.feedingId, NavTarget.Dashboard)
        navigate(scenario, target)
        await(scenario, "隔离母乳记录未加载") { activity ->
            val page = shown(activity) as? FeedingRecordFragment ?: return@await false
            val field = page.requireView().findViewById<EditText>(R.id.etLeftBreastDuration)
            field?.isShown == true && field.text.toString() == "2"
        }
        val automation = instrumentation.uiAutomation
        val originalFlags = automation.serviceInfo.flags
        try {
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            }
            listOf("", "3").forEach { input ->
                listOf(R.id.etLeftBreastDuration to com.zero.common.R.string.left_breast,
                    R.id.etRightBreastDuration to com.zero.common.R.string.right_breast).forEach { (fieldId, labelRes) ->
                    var resourceName = ""
                    var expectedLabel = ""
                    scenario.onActivity { activity ->
                        val field = shown(activity).requireView().findViewById<EditText>(fieldId)
                        field.setText(input)
                        field.requestRectangleOnScreen(Rect(0, 0, field.width, field.height), true)
                        resourceName = field.resources.getResourceName(fieldId)
                        expectedLabel = field.context.getString(labelRes)
                    }
                    val node = awaitNode(resourceName)
                    try {
                        assertTrue("时长必须保留真实可编辑节点：$resourceName", node.isEditable)
                        val label = node.labeledBy
                        try {
                            assertEquals("空值及同值字段都必须能由节点自身区分左右：$resourceName，input=$input",
                                expectedLabel, label?.text?.toString())
                        } finally { label?.recycle() }
                        if (input.isNotEmpty()) assertEquals(input, node.text?.toString())
                    } finally { node.recycle() }
                }
            }
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
        }
        assertEquals("读屏与输入检查不能保存记录", record,
            runBlocking { repository.getFeedingRecordById(record.feedingId) })
    }

    private fun assertEventFieldFeedback(
        scenario: ActivityScenario<MainActivity>, repository: BabyRepository, babyId: Int,
        type: Int, fieldId: Int, invalidInput: String, messageRes: Int
    ) {
        navigate(scenario, NavTarget.EventRecord(returnTarget = NavTarget.Dashboard))
        scenario.onActivity { activity ->
            val vm = ViewModelProvider(shown(activity))[EventRecordViewModel::class.java]
            val subtype = requireNotNull(EventSubtype.fromType(type))
            vm.selectCategory(subtype.category)
            vm.selectSubtype(subtype)
        }
        await(scenario, "事件详情未完成布局") { activity ->
            val field = shown(activity).requireView().findViewById<EditText>(fieldId)
            field != null && field.isShown && field.height > 0 && !field.isLayoutRequested
        }
        scenario.onActivity { activity ->
            val root = shown(activity).requireView()
            root.findViewById<EditText>(fieldId).setText(invalidInput)
            root.findViewById<View>(R.id.noteCollapsedRow).performClick()
            root.findViewById<EditText>(R.id.etNote).apply {
                // 长合成备注确保目标字段已经离开视口，不能靠原本可见的字段让滚动断言假通过。
                setText(List(80) { "合成备注" }.joinToString("\n"))
                requestFocus()
            }
        }
        await(scenario, "合成备注未完成布局") { activity ->
            val note = shown(activity).requireView().findViewById<EditText>(R.id.etNote)
            !note.isLayoutRequested && note.height > 0
        }
        scenario.onActivity { activity ->
            val root = shown(activity).requireView()
            val scroll = root.findViewById<NestedScrollView>(R.id.scrollView)
            scroll.scrollTo(0, scroll.getChildAt(0).height)
            assertFalse("基线必须先将错误输入滚出视口", root.findViewById<EditText>(fieldId).getGlobalVisibleRect(Rect()))
            assertTrue("必须通过真实保存入口验证", root.findViewById<View>(R.id.btnSave).performClick())
        }
        assertFieldFeedback(scenario, type, fieldId, messageRes)
        // 同一无效输入再次保存也必须重新定位，不能被相等的状态流吞掉请求。
        scenario.onActivity { activity ->
            val root = shown(activity).requireView()
            root.findViewById<EditText>(R.id.etNote).requestFocus()
            val scroll = root.findViewById<NestedScrollView>(R.id.scrollView)
            scroll.scrollTo(0, scroll.getChildAt(0).height)
            root.findViewById<View>(R.id.btnSave).performClick()
        }
        assertFieldFeedback(scenario, type, fieldId, messageRes)
        scenario.recreate()
        assertFieldFeedback(scenario, type, fieldId, messageRes)
        scenario.onActivity { activity ->
            val root = shown(activity).requireView()
            assertEquals("无效文本必须保留供用户修正", invalidInput, root.findViewById<EditText>(fieldId).text.toString())
            assertTrue("失败不能清空备注", root.findViewById<EditText>(R.id.etNote).text.isNotEmpty())
            assertEquals(NavTarget.EventRecord(returnTarget = NavTarget.Dashboard), mainVm(activity).navTarget.value)
        }
        assertTrue("校验失败不能写入记录", runBlocking { repository.getEventRecordsByType(babyId, type) }.isEmpty())
        scenario.onActivity { activity ->
            shown(activity).requireView().findViewById<EditText>(fieldId).setText(
                if (type == EventType.HEALTH_MEDICINE) "合成药名" else "7.25")
        }
        await(scenario, "用户更正输入后仍残留旧错误") { activity ->
            shown(activity).requireView().findViewById<EditText>(fieldId).error.isNullOrEmpty()
        }
        assertTrue("修正文字本身不能自动保存", runBlocking { repository.getEventRecordsByType(babyId, type) }.isEmpty())
    }

    private fun assertFieldFeedback(scenario: ActivityScenario<MainActivity>, type: Int, fieldId: Int, messageRes: Int) {
        var diagnostic = ""
        await(scenario, { "保存后没有可恢复字段反馈：type=$type, $diagnostic" }) { activity ->
            val page = activity.supportFragmentManager.fragments.firstOrNull { it is EventRecordFragment && !it.isHidden }
            val root = page?.view ?: return@await false
            val field = root.findViewById<EditText>(fieldId) ?: return@await false
            val scroll = root.findViewById<NestedScrollView>(R.id.scrollView)
            val fieldBounds = Rect()
            val viewport = Rect()
            // 两个矩形独立读取；错误字段离屏不能短路掉视口诊断。
            val fieldVisible = field.getGlobalVisibleRect(fieldBounds)
            val viewportVisible = scroll.getGlobalVisibleRect(viewport)
            val entirelyVisible = fieldVisible && viewportVisible &&
                fieldBounds.height() == field.height && viewport.contains(fieldBounds)
            diagnostic = "error=${field.error}, focused=${field.hasFocus()}, visible=$entirelyVisible, bounds=$fieldBounds, viewport=$viewport"
            field.error?.toString() == activity.getString(messageRes) && field.hasFocus() && entirelyVisible
        }
    }

    private fun withScreen(block: (ActivityScenario<MainActivity>, BabyRepository, Int) -> Unit) {
        assumeTrue("仅在 AVD 的合成宝宝上运行隔离记录回归", Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        val database = Room.inMemoryDatabaseBuilder(app, BabyDatabase::class.java).build()
        val repository = BabyRepository(database)
        val injection = RecordInjection(repository)
        app.registerActivityLifecycleCallbacks(injection)
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            val current = ActivityScenario.launch(MainActivity::class.java)
            scenario = current
            await(current, "宝宝身份尚未加载") { mainVm(it).babyDataLoaded.value }
            var babyId: Int? = null
            current.onActivity { babyId = mainVm(it).getCurrentBabyInfo()?.babyId }
            assumeTrue("AVD 需有用于导航的合成宝宝", babyId != null)
            runBlocking { repository.insertBabyInfo(BabyInfo(babyId = requireNotNull(babyId), name = "隔离反馈测试")) }
            block(current, repository, requireNotNull(babyId))
        } finally {
            scenario?.onActivity { mainVm(it).navigateTo(NavTarget.Dashboard) }
            scenario?.close()
            app.unregisterActivityLifecycleCallbacks(injection)
            database.close()
        }
    }

    /** 页面仍由生产 Fragment 创建；只替换 VM 的存储依赖，防止回归时误写用户库。 */
    private class RecordInjection(private val repository: BabyRepository) : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            if (activity !is MainActivity) return
            activity.supportFragmentManager.registerFragmentLifecycleCallbacks(object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentPreCreated(fm: FragmentManager, f: Fragment, savedInstanceState: Bundle?) {
                    val modelClass = when (f) {
                        is EventRecordFragment -> EventRecordViewModel::class.java
                        is FeedingRecordFragment -> FeedingRecordViewModel::class.java
                        else -> return
                    }
                    ViewModelProvider(f, object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
                            EventRecordViewModel::class.java -> EventRecordViewModel(repository)
                            FeedingRecordViewModel::class.java -> FeedingRecordViewModel(repository)
                            else -> error("不支持的记录 VM")
                        } as T
                    })[modelClass]
                }
            }, false)
        }
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    private fun awaitNode(resourceName: String): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 5_000L
        do {
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.rootInActiveWindow?.let { root ->
                try {
                    val matches = root.findAccessibilityNodeInfosByViewId(resourceName)
                    val visible = matches.firstOrNull { it.isVisibleToUser }
                    matches.filter { it !== visible }.forEach { it.recycle() }
                    if (visible != null) return visible
                } finally { root.recycle() }
            }
            SystemClock.sleep(40L)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("窗口未提供可见编辑节点：$resourceName")
    }

    private fun navigate(scenario: ActivityScenario<MainActivity>, target: NavTarget) {
        scenario.onActivity { mainVm(it).navigateTo(target) }
        await(scenario, "目标记录页尚未显示") { activity ->
            val fragment = shown(activity)
            target.fragmentClass.isInstance(fragment) && fragment.view?.isShown == true
        }
    }

    private fun mainVm(activity: MainActivity) = ViewModelProvider(activity)[MainViewModel::class.java]
    private fun shown(activity: MainActivity) = activity.supportFragmentManager.fragments.first { !it.isHidden && it.view?.isShown == true }
    private fun await(scenario: ActivityScenario<MainActivity>, message: String, ready: (MainActivity) -> Boolean) =
        await(scenario, { message }, ready)

    private fun await(scenario: ActivityScenario<MainActivity>, message: () -> String, ready: (MainActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8_000L
        do {
            instrumentation.waitForIdleSync()
            var done = false
            scenario.onActivity { done = ready(it) }
            if (done) return
            SystemClock.sleep(40L)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError(message())
    }

    private companion object {
        const val RECORD_ID = 2_000_000_041
        const val START = 1_577_836_800_000L
    }
}
