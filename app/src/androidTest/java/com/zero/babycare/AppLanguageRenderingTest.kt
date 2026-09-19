package com.zero.babycare

import android.os.SystemClock
import android.content.res.Resources
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.babycare.statistics.StatisticsViewModel
import com.zero.babycare.statistics.StatisticsFragment
import com.zero.babycare.navigation.NavTarget
import com.zero.babydata.entity.BabyInfo
import com.zero.common.ext.getAppString
import com.zero.common.theme.ThemeManager
import com.zero.components.base.vm.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 只读取真实统计状态并临时切换进程内语言；恢复原语言，不改宝宝记录或语言偏好。 */
@RunWith(AndroidJUnit4::class)
class AppLanguageRenderingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun visibleStatisticsRecreatesItsTextWhileKeepingTheSameViewModel() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val originalLocales = AppCompatDelegate.getApplicationLocales()
        var originalBaby: BabyInfo? = null
        var capturedBaby = false
        try {
            await(scenario) { ViewModelProvider(it)[MainViewModel::class.java].babyDataLoaded.value }
            scenario.onActivity { activity ->
                val main = ViewModelProvider(activity)[MainViewModel::class.java]
                originalBaby = main.getCurrentBabyInfo()
                capturedBaby = true
                // 只替换页面内存输入，统计查询使用不存在的 ID，不创建或编辑宝宝。
                currentBabyFlow(main).value = BabyInfo(
                    babyId = Int.MAX_VALUE, name = "LanguageTest",
                    gender = if (ThemeManager.getSavedTheme() == ThemeManager.BabyTheme.GIRL) "girl" else "boy"
                )
                main.navigateTo(NavTarget.Statistics())
            }
            var originalModel: StatisticsViewModel? = null
            for (tag in listOf("en", "zh-Hans")) {
                instrumentation.runOnMainSync {
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
                }
                await(scenario) { activity ->
                    if (activity.resources.configuration.locales[0].language != tag.substringBefore('-')) return@await false
                    val page = activity.supportFragmentManager.fragments.filterIsInstance<StatisticsFragment>()
                        .firstOrNull { !it.isHidden && it.view?.isShown == true } ?: return@await false
                    val state = ViewModelProvider(page)[StatisticsViewModel::class.java].uiState.value
                    !state.isLoading && state.dayRecord.summaryMetrics.isNotEmpty()
                }
                scenario.onActivity { activity ->
                    val page = activity.supportFragmentManager.fragments.filterIsInstance<StatisticsFragment>().single()
                    val model = ViewModelProvider(page)[StatisticsViewModel::class.java]
                    originalModel?.let { assertTrue("语言重建应保留原 ViewModel", it === model) }
                    originalModel = model
                    val state = model.uiState.value.dayRecord
                    assertEquals("页面重建不能保留旧语言摘要", activity.getString(
                        com.zero.common.R.string.statistics_day_record_count_format, state.timelineItems.size
                    ), state.recordCountText)
                }
            }
        } finally {
            scenario.onActivity {
                val main = ViewModelProvider(it)[MainViewModel::class.java]
                main.navigateTo(NavTarget.Dashboard)
                if (capturedBaby) currentBabyFlow(main).value = originalBaby
                AppCompatDelegate.setApplicationLocales(originalLocales)
            }
            instrumentation.waitForIdleSync()
            scenario.close()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun currentBabyFlow(model: MainViewModel): MutableStateFlow<BabyInfo?> =
        model.javaClass.getDeclaredField("_currentBaby").apply { isAccessible = true }.get(model) as MutableStateFlow<BabyInfo?>

    @Test
    fun statisticsGeneratedTextMatchesActivityLanguageAfterSwitchAndRecreation() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val originalLocales = AppCompatDelegate.getApplicationLocales()
        val store = ViewModelStore()
        lateinit var model: StatisticsViewModel
        scenario.onActivity {
            model = StatisticsViewModel(it.application, SavedStateHandle())
            store.put("language-audit", model)
        }
        val delayedFailure = UiState.Error(IllegalStateException("合成延迟反馈"),
            messageRes = com.zero.common.R.string.backup_import_report_save_failed)
        try {
            for (tag in listOf("en", "zh-Hans", "zh-Hant", "ja", "ko", "")) {
                instrumentation.runOnMainSync {
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
                }
                await(scenario) { activity ->
                    val expected = if (tag.isEmpty()) Resources.getSystem().configuration.locales[0]
                        else Locale.forLanguageTag(tag)
                    val actual = activity.resources.configuration.locales[0]
                    actual.language == expected.language &&
                        (expected.script.isEmpty() || actual.script == expected.script)
                }
                // 使用不存在的 ID 走完整只读统计链路，不依赖设备上有宝宝，也不写测试数据。
                scenario.onActivity {
                    if (model.currentBabyId.value == 0) model.setBabyId(Int.MAX_VALUE)
                    else model.refreshData()
                }
                await(scenario) {
                    val state = model.uiState.value
                    !state.isLoading && state.dayRecord.summaryMetrics.isNotEmpty()
                }
                scenario.onActivity { activity ->
                    val record = model.uiState.value.dayRecord
                    assertEquals("隐藏页恢复的错误应在当前语言下渲染",
                        activity.getString(com.zero.common.R.string.backup_import_report_save_failed),
                        delayedFailure.messageFor(activity))
                    assertEquals("统计生成文案必须与当前页面使用同一语言", activity.getString(
                        com.zero.common.R.string.statistics_day_record_count_format, record.timelineItems.size
                    ), record.recordCountText)
                    listOf(
                        com.zero.common.R.string.backup_import_report_load_failed,
                        com.zero.common.R.string.reminder_channel_name,
                        com.zero.common.R.string.reminder_notification_title
                    ).forEach { id ->
                        assertEquals("后台文案必须跟随当前应用语言", activity.getString(id),
                            activity.application.getAppString(id))
                        assertEquals("共享字符串入口必须跟随当前应用语言", activity.getString(id),
                            com.zero.common.ext.getString(id))
                    }
                }
            }
        } finally {
            instrumentation.runOnMainSync {
                store.clear()
                AppCompatDelegate.setApplicationLocales(originalLocales)
            }
            instrumentation.waitForIdleSync()
            scenario.close()
        }
    }

    private fun await(scenario: ActivityScenario<MainActivity>, ready: (MainActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000L
        do {
            instrumentation.waitForIdleSync()
            var complete = false
            scenario.onActivity { complete = ready(it) }
            if (complete) return
            SystemClock.sleep(30)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("语言切换或统计数据未完成刷新")
    }
}
