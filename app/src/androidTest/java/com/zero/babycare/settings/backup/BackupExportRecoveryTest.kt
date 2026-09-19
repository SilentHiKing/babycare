package com.zero.babycare.settings.backup

import android.app.Application
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.UUID

/** 只使用合成报告和独立临时文件，覆盖选择器外部停留、进程恢复与结果消费。 */
@RunWith(AndroidJUnit4::class)
class BackupExportRecoveryTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val sessions = mutableListOf<Session>()
    private val requests = mutableListOf<BackupExportRequest>()
    private lateinit var testDirectory: File

    @Before
    fun setUp() {
        testDirectory = File(app.cacheDir, "backup-export-test-${UUID.randomUUID()}")
        check(testDirectory.mkdirs())
    }

    @After
    fun tearDown() = runBlocking {
        withContext(Dispatchers.Main) { sessions.forEach { it.store.clear() } }
        requests.forEach { cachedFile(it).delete() }
        testDirectory.deleteRecursively()
        Unit
    }

    @Test
    fun pickerProcessRecoveryWritesOriginalContentAndRestoresUnconsumedResult() = runBlocking {
        val content = "{\"synthetic\":\"${"x".repeat(64_000)}\"}"
        val original = createSession()
        val request = prepareReport(original, content)
        val saved = snapshot(original)
        withContext(Dispatchers.Main) { original.store.clear() }
        assertTrue(cachedFile(request).exists())

        val restored = createSession(saved)
        val output = File(testDirectory, "exported.json")
        withContext(Dispatchers.Main) { restored.vm.writePendingExport(Uri.fromFile(output)) }
        assertEquals(BackupExportOutcome.SUCCEEDED, awaitResult(restored).outcome)
        assertEquals(content, output.readText())
        assertTrue(cachedFile(request).exists())

        // 完成但页面尚未展示时再次恢复，结果仍在；只有 View 明确消费后才清除正文。
        val completedState = snapshot(restored)
        withContext(Dispatchers.Main) { restored.store.clear() }
        val completed = createSession(completedState)
        assertEquals(BackupExportOutcome.SUCCEEDED, awaitResult(completed).outcome)
        consumeAndAwaitCleanup(completed, request)
    }

    @Test
    fun failedAndCancelledExportsKeepContentUntilTheirResultIsConsumed() = runBlocking {
        listOf(BackupExportOutcome.FAILED, BackupExportOutcome.CANCELLED).forEach { expected ->
            val session = createSession()
            val request = prepareReport(session, "{\"synthetic\":true}")
            val uri = if (expected == BackupExportOutcome.FAILED) {
                Uri.fromFile(File(testDirectory, "missing-parent/result.json"))
            } else null
            withContext(Dispatchers.Main) { session.vm.writePendingExport(uri) }
            assertEquals(expected, awaitResult(session).outcome)
            assertTrue(cachedFile(request).exists())
            consumeAndAwaitCleanup(session, request)
        }
    }

    @Test
    fun interruptedWriteRestartsFromSavedDestinationAndTruncatesPartialContent() = runBlocking {
        val content = "{\"synthetic\":1}"
        val original = createSession()
        val request = prepareReport(original, content)
        val output = File(testDirectory, "partial.json").apply { writeText("obsolete".repeat(100)) }
        val saved = snapshot(original).toMutableMap().apply {
            put(BackupViewModel.EXPORT_URI_KEY, Uri.fromFile(output).toString())
        }
        withContext(Dispatchers.Main) { original.store.clear() }
        val restored = createSession(saved)
        assertEquals(BackupExportOutcome.SUCCEEDED, awaitResult(restored).outcome)
        assertEquals(content, output.readText())
        consumeAndAwaitCleanup(restored, request)
    }

    private suspend fun prepareReport(session: Session, content: String): BackupExportRequest {
        val source = File(testDirectory, "report-${UUID.randomUUID()}.json").apply { writeText(content) }
        withContext(Dispatchers.Main) {
            session.vm.prepareReportExport(BackupReportItem(source.name, source.absolutePath, 0L))
        }
        val request = withTimeout(10_000) {
            session.vm.reportExportState.filterIsInstance<UiState.Success<BackupExportRequest>>()
                .first().data!!
        }
        requests += request
        withContext(Dispatchers.Main) { session.vm.resetReportExportState() }
        return request
    }

    private suspend fun createSession(values: Map<String, Any?> = emptyMap()): Session =
        withContext(Dispatchers.Main) {
            val handle = SavedStateHandle(values)
            val vm = BackupViewModel(app, handle)
            Session(vm, handle, ViewModelStore().apply { put("test", vm) }).also { sessions += it }
        }

    private suspend fun awaitResult(session: Session): BackupDocumentExportResult = withTimeout(10_000) {
        session.vm.documentExportState.filterIsInstance<BackupDocumentExportState.Finished>().first().result
    }

    private suspend fun consumeAndAwaitCleanup(session: Session, request: BackupExportRequest) {
        withContext(Dispatchers.Main) {
            session.vm.consumeDocumentExportResult()
            assertEquals(BackupDocumentExportState.Idle, session.vm.documentExportState.value)
            assertNull(session.handle.get<BackupExportRequest>(BackupViewModel.PENDING_EXPORT_KEY))
            assertNull(session.handle.get<BackupDocumentExportResult>(BackupViewModel.EXPORT_RESULT_KEY))
        }
        withTimeout(10_000) { while (cachedFile(request).exists()) delay(10) }
        assertFalse(cachedFile(request).exists())
    }

    private suspend fun snapshot(session: Session): Map<String, Any?> {
        val values = withContext(Dispatchers.Main) {
            HashMap(session.handle.keys().associateWith { session.handle.get<Any?>(it) })
        }
        val bytes = ByteArrayOutputStream().use { output ->
            ObjectOutputStream(output).use { it.writeObject(values) }
            output.toByteArray()
        }
        // 64 KB 合成正文不应进入 SavedState，真实的大备份同样不会触发 Binder 大小限制。
        assertTrue(bytes.size < 4_096)
        @Suppress("UNCHECKED_CAST")
        return ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() as Map<String, Any?> }
    }

    private fun cachedFile(request: BackupExportRequest): File =
        File(app.noBackupFilesDir, "pending_backup_exports/${request.token}.json")

    private data class Session(val vm: BackupViewModel, val handle: SavedStateHandle, val store: ViewModelStore)
}
