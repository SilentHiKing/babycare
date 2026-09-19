package com.zero.babycare.settings.backup

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.NoMatchingViewException
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isCompletelyDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.babycare.MainActivity
import com.zero.babycare.MainViewModel
import com.zero.babycare.navigation.NavTarget
import com.zero.babydata.backup.BackupImportReport
import com.zero.babydata.backup.BackupImportStrategy
import com.zero.babydata.backup.BabyBackupEnvelope
import com.zero.babydata.backup.CommittedBackupImport
import com.zero.components.base.vm.UiState
import com.google.gson.Gson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.hamcrest.Matcher
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** 真实页面与弹窗，提交结果完全合成；报告文件也隔离到本次测试目录。 */
@RunWith(AndroidJUnit4::class)
class BackupReportLifecycleTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var pickerMonitor: Instrumentation.ActivityMonitor? = null

    @Test
    fun delayedHistoryAndExportWaitForVisiblePageAndDetailSurvivesRecreation() {
        val directory = File(app.cacheDir, "backup-report-history-${UUID.randomUUID()}")
        check(directory.mkdirs())
        val isolatedApp = ReportApplication(app, directory)
        val operations = ReportOperations(hasReport = false)
        val reportDirectory = File(isolatedApp.filesDir, "backup_reports").apply { check(mkdirs()) }
        val source = File(reportDirectory, "legacy-test-report.json").apply {
            writeText(Gson().toJson(operations.commit.report))
        }
        val disk = FileBackupReportStore(reportDirectory)
        val readEntered = CountDownLatch(1)
        val allowRead = CompletableDeferred<Unit>()
        val exportEntered = CountDownLatch(1)
        val allowExport = CompletableDeferred<Unit>()
        val delayed = object : BackupReportStore by disk {
            override suspend fun read(item: BackupReportItem): BackupReportItem {
                readEntered.countDown()
                allowRead.await()
                return disk.read(item)
            }
            override suspend fun export(item: BackupReportItem): BackupPayload {
                exportEntered.countDown()
                allowExport.await()
                return disk.export(item)
            }
        }
        val injectedVm = AtomicReference<BackupViewModel>()
        val callbacks = ReportInjection(isolatedApp, operations, injectedVm, delayed)
        // 截获系统选择器并返回取消，验证启动次数而不创建外部文件。
        val pickerFilter = IntentFilter(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            addDataType("application/json")
        }
        val pickerIntent = ActivityResultContracts.CreateDocument("application/json")
            .createIntent(isolatedApp, "synthetic-report.json")
        // 只有 action 的 filter 会漏掉 MIME/type 和 OPENABLE，意外打开真实 DocumentsUI。
        assertTrue("测试监视器必须匹配实际报告选择器 Intent", pickerFilter.match(
            pickerIntent.action, pickerIntent.type, pickerIntent.scheme, pickerIntent.data,
            pickerIntent.categories, "BackupReportLifecycleTest"
        ) >= 0)
        val monitor = instrumentation.addMonitor(pickerFilter,
            Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true)
        pickerMonitor = monitor
        app.registerActivityLifecycleCallbacks(callbacks)
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            val current = ActivityScenario.launch(MainActivity::class.java)
            scenario = current
            awaitActivity(current) { injectedVm.get() != null && ViewModelProvider(it)[MainViewModel::class.java].babyDataLoaded.value }
            navigate(current, NavTarget.Backup())
            awaitActivity(current) { (backupVm(it).reportListState.value as? UiState.Success)?.data?.size == 1 }
            // 大字号下完整设置行把历史列表推到首屏下方，先真实滚动，再保留 Espresso 点击可见性约束。
            onView(withText(source.name)).perform(scrollTo(), click())
            assertTrue(readEntered.await(10, TimeUnit.SECONDS))
            navigate(current, NavTarget.Settings())
            allowRead.complete(Unit)
            awaitActivity(current) { backupVm(it).reportLoadState.value is UiState.Success }
            current.onActivity {
                val model = backupVm(it)
                val loaded = (model.reportLoadState.value as UiState.Success).data!!
                assertEquals("加载结果必须保留原报告定位标识", model.reportPrompt.value?.filePath, loaded.filePath)
            }
            onView(withText(com.zero.common.R.string.backup_import_report_detail_title)).check(doesNotExist())
            navigate(current, NavTarget.Backup())
            awaitReportVisibility(true, com.zero.common.R.string.backup_import_report_detail_title)
            var detailId: String? = null
            current.onActivity { detailId = backupVm(it).reportPrompt.value?.id }
            current.recreate()
            awaitActivity(current) { backupVm(it).reportPrompt.value?.id == detailId }
            awaitReportVisibility(true, com.zero.common.R.string.backup_import_report_detail_title)
            assertReportBodyAndActionsReachable(hasCancel = true)
            onView(withId(com.zero.components.R.id.tv_confirm)).perform(click())
            assertTrue(exportEntered.await(10, TimeUnit.SECONDS))
            navigate(current, NavTarget.Settings())
            allowExport.complete(Unit)
            awaitActivity(current) { backupVm(it).reportExportState.value is UiState.Success }
            assertEquals("隐藏页面不能弹系统选择器", 0, monitor.hits)
            navigate(current, NavTarget.Backup())
            awaitActivity(current) { monitor.hits == 1 }
            current.recreate()
            awaitActivity(current) { ViewModelProvider(it)[MainViewModel::class.java].babyDataLoaded.value }
            assertEquals("已发起的选择器不能因重建再次打开", 1, monitor.hits)
            assertTrue(source.exists())
        } finally {
            try { scenario?.close() } finally {
                app.unregisterActivityLifecycleCallbacks(callbacks)
                instrumentation.removeMonitor(monitor)
                pickerMonitor = null
                allowRead.complete(Unit)
                allowExport.complete(Unit)
                var jobs = emptyList<Job>()
                instrumentation.runOnMainSync { jobs = callbacks.clearStores() }
                runBlocking { withTimeout(10_000) { jobs.forEach { it.join() } } }
                check(directory.canonicalFile.parentFile == app.cacheDir.canonicalFile)
                directory.deleteRecursively()
            }
        }
    }

    @Test
    fun reportSurvivesRecreationAndHiddenPageThenClosesAfterUserConsumption() {
        val directory = File(app.cacheDir, "backup-report-lifecycle-${UUID.randomUUID()}")
        check(directory.mkdirs())
        val isolatedApp = ReportApplication(app, directory)
        val operations = ReportOperations()
        val injectedVm = AtomicReference<BackupViewModel>()
        val callbacks = ReportInjection(isolatedApp, operations, injectedVm)
        app.registerActivityLifecycleCallbacks(callbacks)
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            val activityScenario = ActivityScenario.launch(MainActivity::class.java)
            scenario = activityScenario
            awaitActivity(activityScenario) {
                injectedVm.get() != null && ViewModelProvider(it)[MainViewModel::class.java].babyDataLoaded.value
            }
            navigate(activityScenario, NavTarget.Backup())
            awaitActivity(activityScenario) { reportId(it) == operations.commit.commitId }
            awaitReportVisibility(true)
            assertEquals(0, operations.consumeCount.get())

            activityScenario.recreate()
            awaitActivity(activityScenario) {
                assertSame(injectedVm.get(), backupVm(it))
                reportId(it) == operations.commit.commitId
            }
            awaitReportVisibility(true)
            assertEquals("配置重建不能消费报告", 0, operations.consumeCount.get())

            navigate(activityScenario, NavTarget.Settings())
            awaitReportVisibility(false)
            assertEquals("隐藏页面不能消费报告", 0, operations.consumeCount.get())
            activityScenario.onActivity { assertEquals(operations.commit.commitId, reportId(it)) }
            navigate(activityScenario, NavTarget.Backup())
            awaitReportVisibility(true)

            // 点击真实弹窗按钮才开始消费；闸门保留旧 Success，制造返回页面时重放的窗口。
            assertReportBodyAndActionsReachable(hasCancel = false)
            onView(withId(com.zero.components.R.id.tv_confirm)).perform(click())
            assertTrue("用户关闭未触发报告消费", operations.consumeStarted.await(10, TimeUnit.SECONDS))
            assertEquals(1, operations.consumeCount.get())
            awaitReportVisibility(false)
            navigate(activityScenario, NavTarget.Settings())
            navigate(activityScenario, NavTarget.Backup())
            awaitReportVisibility(true)
            activityScenario.onActivity { assertEquals(operations.commit.commitId, reportId(it)) }

            operations.allowConsume.complete(Unit)
            awaitActivity(activityScenario) { backupVm(it).importState.value == UiState.None }
            // 此断言覆盖 None 分支的窗口清理，不能只验证 VM 已经消费。
            awaitReportVisibility(false)
            navigate(activityScenario, NavTarget.Settings())
            navigate(activityScenario, NavTarget.Backup())
            awaitReportVisibility(false)
            assertEquals("生命周期关闭不能重复消费", 1, operations.consumeCount.get())
            assertEquals(operations.commit.commitId, operations.consumedCommitId.get())
            assertEquals(0, operations.importCount.get())
            assertEquals(0, operations.readCount.get())
        } finally {
            // 先销毁页面、取消 VM 的所有文件任务，再精确移除自己的目录，不触碰用户报告。
            try {
                scenario?.close()
            } finally {
                app.unregisterActivityLifecycleCallbacks(callbacks)
                operations.allowConsume.complete(Unit)
                var jobs = emptyList<Job>()
                instrumentation.runOnMainSync { jobs = callbacks.clearStores() }
                runBlocking { withTimeout(10_000) { jobs.forEach { it.join() } } }
                check(directory.canonicalFile.parentFile == app.cacheDir.canonicalFile)
                directory.deleteRecursively()
            }
        }
    }

    /** 同一真实报告覆盖普通/大字号窗口：正文可滚到底，操作区始终完整可点击。 */
    private fun assertReportBodyAndActionsReachable(hasCancel: Boolean) {
        val actionIds = if (hasCancel) listOf(com.zero.components.R.id.tv_cancel, com.zero.components.R.id.tv_confirm)
            else listOf(com.zero.components.R.id.tv_confirm)
        fun assertActions() {
            awaitStableActions(actionIds)
            actionIds.forEach { id ->
                onView(withId(id)).check(matches(isCompletelyDisplayed())).check { view, error ->
                    if (error != null) throw error
                    val button = view as TextView
                    val textLayout = requireNotNull(button.layout)
                    assertTrue("弹窗按钮必须保留48dp触控高度", button.height >=
                        button.resources.getDimensionPixelSize(com.zero.common.R.dimen.min_touch_target))
                    assertTrue("按钮全文必须参与排版", textLayout.lineCount > 0 &&
                        textLayout.getLineEnd(textLayout.lineCount - 1) == button.text.length)
                    assertTrue("按钮文字不能被垂直裁切", textLayout.height <=
                        button.height - button.compoundPaddingTop - button.compoundPaddingBottom)
                    repeat(textLayout.lineCount) { line ->
                        assertEquals("按钮不能省略文案", 0, textLayout.getEllipsisCount(line))
                        assertTrue("按钮文字不能横向溢出", textLayout.getLineWidth(line) <=
                            button.width - button.compoundPaddingLeft - button.compoundPaddingRight + 1)
                    }
                }
            }
        }
        assertActions()
        onView(withId(com.zero.components.R.id.scroll_dialog_body)).perform(object : ViewAction {
            override fun getDescription() = "滚动报告正文到底部"
            override fun getConstraints(): Matcher<View> = isDisplayed()
            override fun perform(uiController: UiController, view: View) {
                val scroll = view as NestedScrollView
                scroll.scrollTo(0, scroll.getChildAt(0).height)
                uiController.loopMainThreadUntilIdle()
                assertTrue("报告最后一行必须能够滚入视口", scroll.getChildAt(0).bottom <=
                    scroll.scrollY + scroll.height - scroll.paddingBottom + 1)
            }
        })
        assertActions()
    }

    /** 标题刚出现时 XPopup 仍可能在缩放；等真实窗口稳定，不降低按钮 100% 可见的要求。 */
    private fun awaitStableActions(actionIds: List<Int>) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        var previousBounds: String? = null
        var stableSamples = 0
        var lastDiagnostic = "尚未获取按钮边界"
        do {
            var ready = true
            val snapshots = mutableListOf<String>()
            actionIds.forEach { id ->
                try {
                    onView(withId(id)).check { view, error ->
                        if (error != null) throw error
                        val button = requireNotNull(view)
                        val ancestors = generateSequence(button) { it.parent as? View }.toList()
                        val settled = ancestors.all {
                            !it.isLayoutRequested && it.scaleX == 1f && it.scaleY == 1f &&
                                it.alpha == 1f && (it.animation == null || it.animation.hasEnded())
                        }
                        ready = ready && settled && isCompletelyDisplayed().matches(button)
                        // 只输出几何与动画状态，不记录正文、文件路径或其他用户资料。
                        snapshots += ancestors.joinToString(" <- ") { node ->
                            val global = Rect()
                            val local = Rect()
                            val position = IntArray(2)
                            node.getLocationOnScreen(position)
                            node.getGlobalVisibleRect(global)
                            node.getLocalVisibleRect(local)
                            val label = if (node.id == View.NO_ID) node.javaClass.simpleName
                                else runCatching { node.resources.getResourceEntryName(node.id) }.getOrDefault(node.id.toString())
                            "$label(size=${node.width}x${node.height},xy=${position.contentToString()}," +
                                "bounds=${node.left},${node.top},${node.right},${node.bottom}," +
                                "global=$global,local=$local,scale=${node.scaleX},${node.scaleY}," +
                                "translation=${node.translationX},${node.translationY},alpha=${node.alpha}," +
                                "layout=${node.isLayoutRequested},animation=${node.animation?.hasEnded()})"
                        }
                    }
                } catch (error: NoMatchingViewException) {
                    ready = false
                    snapshots += "按钮尚未挂载：$id"
                }
            }
            lastDiagnostic = snapshots.joinToString("\n")
            stableSamples = if (ready && lastDiagnostic == previousBounds) stableSamples + 1 else 0
            if (stableSamples >= 2) return
            previousBounds = lastDiagnostic
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("弹窗按钮未达到稳定且完整可见状态：\n$lastDiagnostic")
    }

    private fun backupVm(activity: MainActivity): BackupViewModel = ViewModelProvider(
        activity.supportFragmentManager.fragments.filterIsInstance<BackupFragment>().first()
    )[BackupViewModel::class.java]

    private fun reportId(activity: MainActivity): String? =
        (backupVm(activity).importState.value as? UiState.Success)?.data?.commitId

    private fun navigate(scenario: ActivityScenario<MainActivity>, target: NavTarget) {
        scenario.onActivity { ViewModelProvider(it)[MainViewModel::class.java].navigateTo(target) }
        awaitActivity(scenario) { activity ->
            ViewModelProvider(activity)[MainViewModel::class.java].navTarget.value.fragmentClass == target.fragmentClass &&
                activity.supportFragmentManager.fragments.any {
                    target.fragmentClass.isInstance(it) && !it.isHidden && it.isResumed && it.view?.isShown == true &&
                        it.viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                }
        }
    }

    private fun awaitActivity(scenario: ActivityScenario<MainActivity>, predicate: (MainActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        var lastState = "尚未收到 Activity"
        do {
            instrumentation.waitForIdleSync()
            var ready = false
            scenario.onActivity { activity ->
                ready = predicate(activity)
                if (!ready) {
                    // 只记录合成测试的导航/生命周期，不包含宝宝资料、报告正文或文件路径。
                    val target = ViewModelProvider(activity)[MainViewModel::class.java].navTarget.value.fragmentClass.simpleName
                    val fragments = activity.supportFragmentManager.fragments.filter {
                        it is BackupFragment || it is com.zero.babycare.settings.SettingsFragment
                    }.joinToString { fragment ->
                        val viewState = if (fragment.view == null) "无视图" else fragment.viewLifecycleOwner.lifecycle.currentState.name
                        "${fragment.javaClass.simpleName}(hidden=${fragment.isHidden},resumed=${fragment.isResumed},shown=${fragment.view?.isShown},view=$viewState)"
                    }
                    lastState = "target=$target,activity=${activity.lifecycle.currentState},picker=${pickerMonitor?.hits},$fragments"
                }
            }
            if (ready) return
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("备份报告页面未恢复预期状态：$lastState")
    }

    /** XPopup 有退出动画，等待窗口实际消失，避免只看 Fragment 字段造成假通过。 */
    private fun awaitReportVisibility(visible: Boolean, titleRes: Int = com.zero.common.R.string.backup_import_report_title) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        do {
            try {
                onView(withText(titleRes)).check(
                    if (visible) matches(isDisplayed()) else doesNotExist()
                )
                return
            } catch (error: NoMatchingViewException) {
                if (SystemClock.uptimeMillis() >= deadline) throw error
            } catch (error: AssertionError) {
                if (SystemClock.uptimeMillis() >= deadline) throw error
            }
            SystemClock.sleep(50)
        } while (true)
    }

    /** 利用正常 Fragment ViewModelStore 注入，页面第一次访问 VM 前完成，不需要生产测试开关。 */
    private class ReportInjection(
        private val application: Application,
        private val operations: BackupImportOperations,
        private val injectedVm: AtomicReference<BackupViewModel>,
        private val reportStore: BackupReportStore? = null
    ) : Application.ActivityLifecycleCallbacks {
        private val stores = mutableSetOf<androidx.lifecycle.ViewModelStore>()
        private val models = mutableSetOf<BackupViewModel>()

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            if (activity !is MainActivity) return
            activity.supportFragmentManager.registerFragmentLifecycleCallbacks(
                object : FragmentManager.FragmentLifecycleCallbacks() {
                    override fun onFragmentPreCreated(fm: FragmentManager, f: Fragment, savedInstanceState: Bundle?) {
                        if (f !is BackupFragment) return
                        stores += f.viewModelStore
                        val model = ViewModelProvider(f, object : ViewModelProvider.Factory {
                            @Suppress("UNCHECKED_CAST")
                            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                                check(modelClass == BackupViewModel::class.java)
                                return BackupViewModel(application, SavedStateHandle(), operations, reportStore) as T
                            }
                        })[BackupViewModel::class.java]
                        models += model
                        injectedVm.compareAndSet(null, model)
                    }
                }, false
            )
        }

        fun clearStores(): List<Job> {
            val jobs = models.mapNotNull { it.viewModelScope.coroutineContext[Job] }
            stores.forEach { it.clear() }
            return jobs
        }
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    /** VM 真实保存报告，但 Application 文件目录隔离，因此同秒命名也不会覆盖用户报告。 */
    private class ReportApplication(base: Application, directory: File) : Application() {
        private val reportFiles = File(directory, "files").apply { check(mkdirs()) }
        private val privateFiles = File(directory, "no-backup").apply { check(mkdirs()) }
        init { attachBaseContext(base) }
        override fun getFilesDir(): File = reportFiles
        override fun getNoBackupFilesDir(): File = privateFiles
    }

    private class ReportOperations(private val hasReport: Boolean = true) : BackupImportOperations {
        val commit = CommittedBackupImport(UUID.randomUUID().toString(), BackupImportReport(
            BackupImportStrategy.MERGE, 1, 0, 0, 2, 0, 3, 0, 4, 0, 0, 0
        ), emptyList())
        val consumeCount = AtomicInteger()
        val consumedCommitId = AtomicReference<String>()
        val importCount = AtomicInteger()
        val readCount = AtomicInteger()
        val consumeStarted = CountDownLatch(1)
        val allowConsume = CompletableDeferred<Unit>()

        override suspend fun recoverReport(): CommittedBackupImport? =
            if (hasReport && consumedCommitId.get() == null) commit else null

        override suspend fun consumeReport(commitId: String) {
            check(commitId == commit.commitId)
            consumeCount.incrementAndGet()
            consumeStarted.countDown()
            allowConsume.await()
            consumedCommitId.set(commitId)
        }

        override suspend fun read(uri: Uri): BabyBackupEnvelope {
            readCount.incrementAndGet()
            error("报告恢复不应读取备份")
        }

        override suspend fun execute(envelope: BabyBackupEnvelope, mode: BackupImportMode, dedupMinutes: Int): CommittedBackupImport {
            importCount.incrementAndGet()
            error("报告恢复不应执行导入")
        }

        override fun release(uri: Uri) = Unit
    }
}
