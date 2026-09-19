package com.zero.babycare.settings.backup

import android.app.Activity
import android.app.Application
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.NoMatchingViewException
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
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

    private fun backupVm(activity: MainActivity): BackupViewModel = ViewModelProvider(
        activity.supportFragmentManager.fragments.filterIsInstance<BackupFragment>().first()
    )[BackupViewModel::class.java]

    private fun reportId(activity: MainActivity): String? =
        (backupVm(activity).importState.value as? UiState.Success)?.data?.commitId

    private fun navigate(scenario: ActivityScenario<MainActivity>, target: NavTarget) {
        scenario.onActivity { ViewModelProvider(it)[MainViewModel::class.java].navigateTo(target) }
        awaitActivity(scenario) { activity -> activity.supportFragmentManager.fragments.any {
            target.fragmentClass.isInstance(it) && !it.isHidden && it.view?.isShown == true
        } }
    }

    private fun awaitActivity(scenario: ActivityScenario<MainActivity>, predicate: (MainActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        do {
            instrumentation.waitForIdleSync()
            var ready = false
            scenario.onActivity { ready = predicate(it) }
            if (ready) return
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("备份报告页面未恢复预期状态")
    }

    /** XPopup 有退出动画，等待窗口实际消失，避免只看 Fragment 字段造成假通过。 */
    private fun awaitReportVisibility(visible: Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        do {
            try {
                onView(withText(com.zero.common.R.string.backup_import_report_title)).check(
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
        private val injectedVm: AtomicReference<BackupViewModel>
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
                                return BackupViewModel(application, SavedStateHandle(), operations) as T
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

    private class ReportOperations : BackupImportOperations {
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
            if (consumedCommitId.get() == null) commit else null

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
