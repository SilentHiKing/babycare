package com.zero.babycare

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.babycare.home.record.SleepRecordFragment
import com.zero.babycare.navigation.NavTarget
import org.hamcrest.Matcher
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 通过 AppCompat 切换真实夜间资源，覆盖系统锁定夜间模式时仍可验证的重建路径。 */
@RunWith(AndroidJUnit4::class)
class MainActivityNightModeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun nightModeRecreationKeepsSleepDraftAndUnsavedConfirmation() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var originalNote = ""
        var draftWritten = false
        try {
            // 先固定浅色，确保下面的夜间切换一定触发真实 Activity 重建。
            scenario.onActivity { it.delegate.localNightMode = AppCompatDelegate.MODE_NIGHT_NO }
            awaitActivity(scenario, "浅色页面和初始数据未就绪") { activity ->
                isNightMode(activity, Configuration.UI_MODE_NIGHT_NO) &&
                    mainViewModel(activity).babyDataLoaded.value
            }
            navigate(scenario, NavTarget.SleepRecord(returnTarget = NavTarget.Dashboard))
            lateinit var lightActivity: MainActivity
            scenario.onActivity { activity ->
                val note = sleepNote(activity)
                originalNote = note.text.toString()
                note.setText(TEST_DRAFT)
                draftWritten = true
                lightActivity = activity
                activity.delegate.localNightMode = AppCompatDelegate.MODE_NIGHT_YES
            }

            awaitActivity(scenario, "夜间重建后未恢复睡眠记录页") { activity ->
                activity !== lightActivity && isNightMode(activity, Configuration.UI_MODE_NIGHT_YES) &&
                    visibleFragment(activity, NavTarget.SleepRecord()) != null
            }
            assertDraftPreserved(scenario)
            saveScreenshot("sleep-night-draft.png")

            // 文字恢复还不够；未保存标记也必须恢复，返回后只能出现确认而不能静默丢稿。
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            onView(withId(com.zero.components.R.id.tv_content))
                .check(matches(withText(com.zero.common.R.string.unsaved_record_tip)))
            saveScreenshot("sleep-night-unsaved-confirm.png")
            onView(withId(com.zero.components.R.id.tv_cancel)).perform(performClick())
            assertDraftPreserved(scenario)

            scenario.onActivity { sleepNote(it).setText(originalNote) }
            listOf(
                NavTarget.Dashboard to "dashboard-night.png",
                NavTarget.FeedingRecord(returnTarget = NavTarget.Dashboard) to "feeding-night.png",
                NavTarget.Statistics(returnTarget = NavTarget.Dashboard) to "statistics-night.png",
                NavTarget.Settings(returnTarget = NavTarget.Dashboard) to "settings-night.png",
                NavTarget.Backup(returnTarget = NavTarget.Settings()) to "backup-night.png"
            ).forEach { (target, filename) ->
                navigate(scenario, target)
                saveScreenshot(filename)
            }
        } finally {
            try {
                scenario.onActivity { activity ->
                    // 只清理本测试写入的固定草稿，不保存、不取消用户的进行中记录。
                    val sleep = activity.supportFragmentManager.fragments
                        .filterIsInstance<SleepRecordFragment>().firstOrNull()
                    sleep?.view?.findViewById<EditText>(R.id.etNote)?.let { note ->
                        if (draftWritten && note.text.toString() == TEST_DRAFT) note.setText(originalNote)
                    }
                    mainViewModel(activity).navigateTo(NavTarget.Dashboard)
                    activity.delegate.localNightMode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
                awaitActivity(scenario, "测试结束时未恢复系统主题和首页") { activity ->
                    activity.delegate.localNightMode == AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM &&
                        visibleFragment(activity, NavTarget.Dashboard) != null
                }
            } finally {
                scenario.close()
            }
        }
    }

    private fun assertDraftPreserved(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { activity ->
            assertTrue("重建或取消返回后必须停留在睡眠页",
                mainViewModel(activity).navTarget.value is NavTarget.SleepRecord)
            // 不把实际备注放入断言消息，避免失败日志泄露用户内容。
            assertTrue("重建后必须保留未保存备注", sleepNote(activity).text.toString() == TEST_DRAFT)
        }
    }

    private fun navigate(scenario: ActivityScenario<MainActivity>, target: NavTarget) {
        scenario.onActivity { mainViewModel(it).navigateTo(target) }
        awaitActivity(scenario, "目标页面未完成显示") { activity ->
            val root = visibleFragment(activity, target)?.view
            root != null && root.width > 0 && root.height > 0 &&
                root.findViewById<View>(com.zero.components.R.id.progressState)?.isShown != true
        }
    }

    private fun visibleFragment(activity: MainActivity, target: NavTarget): Fragment? =
        activity.supportFragmentManager.fragments.firstOrNull {
            target.fragmentClass.isInstance(it) && !it.isHidden && it.view?.isShown == true
        }

    private fun sleepNote(activity: MainActivity): EditText = requireNotNull(
        visibleFragment(activity, NavTarget.SleepRecord())?.view?.findViewById<EditText>(R.id.etNote)
    ) { "睡眠备注字段未显示" }

    private fun mainViewModel(activity: MainActivity): MainViewModel =
        ViewModelProvider(activity)[MainViewModel::class.java]

    private fun isNightMode(activity: MainActivity, expected: Int): Boolean =
        activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == expected

    private fun awaitActivity(
        scenario: ActivityScenario<MainActivity>,
        message: String,
        predicate: (MainActivity) -> Boolean
    ) {
        val deadline = SystemClock.uptimeMillis() + 10_000L
        do {
            instrumentation.waitForIdleSync()
            var ready = false
            scenario.onActivity { ready = predicate(it) }
            if (ready) return
            SystemClock.sleep(50L)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError(message)
    }

    private fun saveScreenshot(filename: String) {
        // 常规回归不生成含现有数据的截图，只有人工发起的视觉审查才显式启用。
        if (InstrumentationRegistry.getArguments().getString("captureScreenshots") != "true") return
        // 主线程空闲不代表 Choreographer 动画已结束，避免把弹窗退场残影写入审查截图。
        SystemClock.sleep(350L)
        instrumentation.waitForIdleSync()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "无法获取验证截图" }
        try {
            val directory = File(instrumentation.targetContext.filesDir, "audit-screens")
            check(directory.isDirectory || directory.mkdirs()) { "无法创建验证截图目录" }
            File(directory, filename).outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "无法保存验证截图" }
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** 直接调用真实按钮监听器，避免依赖该设备会被终止的 shell input 进程。 */
    private fun performClick() = object : ViewAction {
        override fun getDescription() = "点击取消并保留草稿"
        override fun getConstraints(): Matcher<View> = isDisplayed()
        override fun perform(uiController: UiController, view: View) {
            check(view.performClick()) { "取消按钮未处理点击" }
            uiController.loopMainThreadUntilIdle()
        }
    }

    private companion object {
        const val TEST_DRAFT = "AuditNightDraft"
    }
}
