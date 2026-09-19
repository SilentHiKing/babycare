package com.zero.babycare.settings.backup

import android.app.Application
import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.zero.babycare.MainActivity
import com.zero.babycare.MainViewModel
import com.zero.babycare.navigation.NavTarget
import com.zero.babydata.backup.BackupBaby
import com.zero.babydata.backup.BackupImportReport
import com.zero.babydata.backup.BackupImportStrategy
import com.zero.babydata.backup.BabyBackupData
import com.zero.babydata.backup.BabyBackupEnvelope
import com.zero.components.base.vm.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** 合成文件和受控写入口；真实 Activity 测试只到确认/取消，绝不修改用户数据库。 */
@RunWith(AndroidJUnit4::class)
class BackupImportRecoveryTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val sessions = mutableListOf<Session>()
    private lateinit var directory: File

    @Before
    fun setUp() {
        directory = File(app.cacheDir, "backup-import-test-${UUID.randomUUID()}")
        check(directory.mkdirs())
    }

    @After
    fun tearDown() = runBlocking {
        withContext(Dispatchers.Main) { sessions.forEach { it.store.clear() } }
        directory.deleteRecursively()
        Unit
    }

    @Test
    fun processRecoveryReparsesUriAndRequiresNewMergeConfirmation() = runBlocking {
        val operations = RecordingOperations()
        val original = session(operations)
        val source = sourceFile("x".repeat(64_000))
        withContext(Dispatchers.Main) { original.vm.prepareImport(Uri.fromFile(source)) }
        val selection = awaitPrompt(original)
        assertEquals(BackupImportMode.MERGE, selection.mode)
        withContext(Dispatchers.Main) { original.vm.selectImportMode(selection.id, BackupImportMode.OVERWRITE) }
        val oldConfirmation = awaitPrompt(original)
        assertEquals(BackupImportPrompt.Step.CONFIRM, oldConfirmation.step)
        val restoredState = parcelSnapshot(original)
        withContext(Dispatchers.Main) { original.store.clear() }

        val restored = session(operations, restoredState)
        val prompt = awaitPrompt(restored)
        assertEquals(BackupImportPrompt.Step.SELECT_MODE, prompt.step)
        assertEquals(BackupImportMode.MERGE, prompt.mode)
        assertNotEquals(oldConfirmation.id, prompt.id)
        assertEquals(2, operations.readCount.get())
        assertEquals(0, operations.writeCount.get())
        withContext(Dispatchers.Main) {
            restored.vm.confirmImport(oldConfirmation.id)
            restored.vm.confirmImport(prompt.id)
        }
        assertEquals(0, operations.writeCount.get())

        withContext(Dispatchers.Main) { restored.vm.selectImportMode(prompt.id, BackupImportMode.MERGE) }
        val confirmation = awaitPrompt(restored)
        withContext(Dispatchers.Main) {
            restored.vm.confirmImport(confirmation.id)
            restored.vm.confirmImport(confirmation.id)
        }
        withTimeout(10_000) {
            restored.vm.importState.filterIsInstance<UiState.Success<BackupImportReport>>().first()
        }
        assertEquals(1, operations.writeCount.get())
        assertEquals(BackupImportMode.MERGE, operations.lastMode)
        assertNull(restored.handle.get<String>(BackupViewModel.PENDING_IMPORT_URI_KEY))

        // 用户确认后的快照没有可执行指令，新 VM 不读取也不再次写入。
        val completed = session(operations, parcelSnapshot(restored))
        assertEquals(UiState.None, completed.vm.importPromptState.value)
        assertEquals(1, operations.writeCount.get())
    }

    @Test
    fun replacedAndCancelledPromptsCannotImportAnotherFile() = runBlocking {
        val operations = RecordingOperations()
        val current = session(operations)
        withContext(Dispatchers.Main) { current.vm.prepareImport(Uri.fromFile(sourceFile())) }
        val first = awaitPrompt(current)
        withContext(Dispatchers.Main) { current.vm.selectImportMode(first.id, BackupImportMode.OVERWRITE) }
        val oldConfirmation = awaitPrompt(current)
        withContext(Dispatchers.Main) { current.vm.prepareImport(Uri.fromFile(sourceFile())) }
        val replacement = awaitPrompt(current)
        withContext(Dispatchers.Main) {
            current.vm.cancelImport(oldConfirmation.id)
            current.vm.confirmImport(oldConfirmation.id)
            current.vm.selectImportMode(first.id, BackupImportMode.OVERWRITE)
        }
        assertEquals(replacement, awaitPrompt(current))
        assertEquals(0, operations.writeCount.get())
        withContext(Dispatchers.Main) { current.vm.cancelImport(replacement.id) }
        assertEquals(UiState.None, current.vm.importPromptState.value)
        assertNull(current.handle.get<String>(BackupViewModel.PENDING_IMPORT_URI_KEY))
        val restored = session(operations, parcelSnapshot(current))
        assertEquals(UiState.None, restored.vm.importPromptState.value)
        assertEquals(0, operations.writeCount.get())
    }

    @Test
    fun unavailableRestoredDocumentOnlyShowsRecoverableError() = runBlocking {
        val operations = RecordingOperations()
        val restored = session(operations, mapOf(
            BackupViewModel.PENDING_IMPORT_URI_KEY to Uri.fromFile(File(directory, "missing.json")).toString()
        ))
        withTimeout(10_000) { restored.vm.importPromptState.filterIsInstance<UiState.Error>().first() }
        assertNull(restored.handle.get<String>(BackupViewModel.PENDING_IMPORT_URI_KEY))
        assertEquals(0, operations.writeCount.get())
        withContext(Dispatchers.Main) { restored.vm.consumeImportPromptError() }
        assertEquals(UiState.None, restored.vm.importPromptState.value)
    }

    @Test
    fun choosingSameDocumentWaitsForOldPermissionReleaseBeforeReadingAgain() = runBlocking {
        val operations = RecordingOperations(blockFirstRelease = true)
        val current = session(operations)
        val uri = Uri.fromFile(sourceFile())
        withContext(Dispatchers.Main) { current.vm.prepareImport(uri) }
        val first = awaitPrompt(current)
        withContext(Dispatchers.Main) { current.vm.cancelImport(first.id) }
        assertTrue(operations.releaseStarted.await(5, TimeUnit.SECONDS))
        try {
            withContext(Dispatchers.Main) { current.vm.prepareImport(uri) }
            // 旧释放尚未完成时，新预览必须等待；旧实现会先发布新预览再撤销其授权。
            delay(150)
            assertEquals(1, operations.readCount.get())
            assertEquals(UiState.Loading, current.vm.importPromptState.value)
        } finally {
            operations.allowRelease.countDown()
        }
        val replacement = awaitPrompt(current)
        assertEquals(2, operations.readCount.get())
        assertTrue(operations.hasPermission.get())
        assertEquals(BackupImportMode.MERGE, replacement.mode)
        assertEquals(0, operations.writeCount.get())
        withContext(Dispatchers.Main) { current.vm.cancelImport(replacement.id) }
    }

    @Test
    fun activityRecreationAndHiddenPageKeepConfirmationWithoutWriting() {
        val source = sourceFile()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            awaitActivity(scenario) { ViewModelProvider(it)[MainViewModel::class.java].babyDataLoaded.value }
            navigate(scenario, NavTarget.Backup())
            scenario.onActivity { backupVm(it).prepareImport(Uri.fromFile(source)) }
            awaitActivity(scenario) { prompt(it)?.step == BackupImportPrompt.Step.SELECT_MODE }
            onView(withText(com.zero.common.R.string.backup_import_mode_merge)).check(matches(isDisplayed()))
            // 第一次确认仅选择默认“合并”，没有触碰数据库。
            onView(withId(com.zero.components.R.id.tv_confirm)).perform(click())
            awaitActivity(scenario) { prompt(it)?.step == BackupImportPrompt.Step.CONFIRM }
            var expectedId: String? = null
            scenario.onActivity { expectedId = prompt(it)?.id }
            scenario.recreate()
            awaitActivity(scenario) { prompt(it)?.id == expectedId }
            onView(withId(com.zero.components.R.id.tv_content)).check(matches(isDisplayed()))

            navigate(scenario, NavTarget.Settings())
            // hide/show 不一定触发 Fragment 生命周期降级，必须单独撤下窗口。
            SystemClock.sleep(350)
            onView(withId(com.zero.components.R.id.tv_content)).check(doesNotExist())
            scenario.onActivity { assertEquals(expectedId, prompt(it)?.id) }
            navigate(scenario, NavTarget.Backup())
            onView(withId(com.zero.components.R.id.tv_content)).check(matches(isDisplayed()))
            onView(withId(com.zero.components.R.id.tv_cancel)).perform(click())
            awaitActivity(scenario) { backupVm(it).importPromptState.value == UiState.None }
            scenario.onActivity { assertEquals(UiState.None, backupVm(it).importState.value) }
        } finally {
            // 失败时也只取消合成文件的预览，不调用任何实际导入入口。
            scenario.onActivity { activity ->
                prompt(activity)?.let { backupVm(activity).cancelImport(it.id) }
            }
            scenario.close()
        }
    }

    private fun sourceFile(extra: String = ""): File = File(directory, "${UUID.randomUUID()}.json").apply {
        val baby = BackupBaby(UUID.randomUUID().toString(), "合成测试", "boy", 0L, 0f, 0f, "", extra)
        writeText(Gson().toJson(BabyBackupEnvelope(1, 1L, "test", BabyBackupData(
            listOf(baby), emptyList(), emptyList(), emptyList(), emptyList()
        ))))
    }

    private suspend fun session(operations: RecordingOperations, values: Map<String, Any?> = emptyMap()): Session =
        withContext(Dispatchers.Main) {
            val handle = SavedStateHandle(values)
            val vm = BackupViewModel(app, handle, operations)
            Session(vm, handle, ViewModelStore().apply { put("test", vm) }).also(sessions::add)
        }

    private suspend fun awaitPrompt(session: Session): BackupImportPrompt = withTimeout(10_000) {
        session.vm.importPromptState.filterIsInstance<UiState.Success<BackupImportPrompt>>().first().data!!
    }

    private suspend fun parcelSnapshot(session: Session): Map<String, Any?> = withContext(Dispatchers.Main) {
        val parcel = Parcel.obtain()
        try {
            val saved = Bundle().apply {
                session.handle.keys().forEach { key -> putString(key, session.handle.get<String>(key)) }
            }
            parcel.writeBundle(saved)
            // 64 KB 的正文与预览不得进入 Bundle，恢复凭据只有 URI。
            assertTrue(parcel.dataSize() < 4_096)
            parcel.setDataPosition(0)
            val restored = requireNotNull(parcel.readBundle(Bundle::class.java.classLoader))
            restored.keySet().associateWith { restored.getString(it) }
        } finally {
            parcel.recycle()
        }
    }

    private fun backupVm(activity: MainActivity): BackupViewModel = ViewModelProvider(
        activity.supportFragmentManager.fragments.filterIsInstance<BackupFragment>().first()
    )[BackupViewModel::class.java]

    private fun prompt(activity: MainActivity): BackupImportPrompt? =
        (backupVm(activity).importPromptState.value as? UiState.Success)?.data

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
        throw AssertionError("备份导入页面未恢复预期状态")
    }

    private inner class RecordingOperations(private val blockFirstRelease: Boolean = false) : BackupImportOperations {
        private val fileOperations = ApplicationBackupImportOperations(app)
        val readCount = AtomicInteger()
        val writeCount = AtomicInteger()
        val releaseStarted = CountDownLatch(1)
        val allowRelease = CountDownLatch(1)
        val hasPermission = AtomicBoolean(false)
        private val releaseCount = AtomicInteger()
        @Volatile var lastMode: BackupImportMode? = null

        override suspend fun read(uri: Uri): BabyBackupEnvelope {
            readCount.incrementAndGet()
            return fileOperations.read(uri).also { hasPermission.set(true) }
        }

        override suspend fun execute(envelope: BabyBackupEnvelope, mode: BackupImportMode, dedupMinutes: Int): BackupImportReport {
            writeCount.incrementAndGet()
            lastMode = mode
            return BackupImportReport(
                if (mode == BackupImportMode.MERGE) BackupImportStrategy.MERGE else BackupImportStrategy.OVERWRITE,
                1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
            )
        }

        override fun release(uri: Uri) {
            if (blockFirstRelease && releaseCount.incrementAndGet() == 1) {
                releaseStarted.countDown()
                check(allowRelease.await(5, TimeUnit.SECONDS)) { "合成授权释放未解除阻塞" }
            }
            fileOperations.release(uri)
            hasPermission.set(false)
        }
    }

    private data class Session(val vm: BackupViewModel, val handle: SavedStateHandle, val store: ViewModelStore)
}
