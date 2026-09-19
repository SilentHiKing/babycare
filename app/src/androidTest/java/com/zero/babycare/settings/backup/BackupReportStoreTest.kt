package com.zero.babycare.settings.backup

import android.app.Application
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.Gson
import com.zero.babydata.backup.BackupImportReport
import com.zero.babydata.backup.BackupImportStrategy
import com.zero.babydata.backup.BabyBackupEnvelope
import com.zero.babydata.backup.CommittedBackupImport
import com.zero.components.base.vm.UiState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** 只在 UUID 临时目录写合成报告；不连接应用业务库和提交 journal。 */
@RunWith(AndroidJUnit4::class)
class BackupReportStoreTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun sameSecondCommitsStayDistinctAndRetryRemainsIdempotentAcrossStores() = files { directory ->
        val first = FileBackupReportStore(directory)
        val second = FileBackupReportStore(directory)
        val a = commit(1)
        val b = commit(2)
        coroutineScope {
            (0 until 8).map { index -> async { (if (index % 2 == 0) first else second).archive(a) } }.awaitAll()
        }
        val one = first.archive(a)
        val two = second.archive(b)
        File(one.filePath).setLastModified(1_700_000_000_000L)
        File(two.filePath).setLastModified(1_700_000_000_000L)
        assertNotEquals(one.filePath, two.filePath)
        assertEquals(2, first.list().size)
        assertEquals(a.report, second.read(one).report)
        assertEquals(b.report, second.read(two).report)
        val legacy = File(directory, "import_report_20200101_010101.json").apply { writeText(Gson().toJson(a.report)) }
        val legacyItem = first.list().single { it.fileName == legacy.name }
        assertEquals(a.report, first.read(legacyItem).report)
        assertEquals(legacy.readText(), first.export(legacyItem).json)
        // 合法目录别名经安全校验后仍须保留请求标识，否则详情的等值检查会永久拒绝结果。
        val alias = legacyItem.copy(filePath = File(directory, "../${directory.name}/${legacy.name}").absolutePath)
        val loadedAlias = first.read(alias)
        assertEquals(alias.filePath, loadedAlias.filePath)
        assertEquals(a.report, loadedAlias.report)
    }

    @Test
    fun interruptedFirstWriteAndLegacyBackupRecoverAndDeleteWithoutResurrection() = files { directory ->
        val store = FileBackupReportStore(directory)
        val committed = commit(1)
        val target = File(directory, "${committed.commitId}.json")
        // API 24–28 也必须使用 .new；进程退出遗留的半文件不能成为下次重试的永久冲突。
        File(target.path + ".new").writeText("{partial")
        val item = store.archive(committed)
        assertEquals(committed.report, store.read(item).report)
        assertFalse(File(target.path + ".new").exists())
        assertTrue(target.renameTo(File(target.path + ".bak")))
        assertEquals(item.filePath, store.list().single().filePath)
        assertTrue(target.exists())
        File(target.path + ".bak").writeText(Gson().toJson(committed.report))
        File(target.path + ".new").writeText("{partial")
        store.delete(item)
        assertTrue(store.list().isEmpty())
        assertFalse(File(target.path + ".bak").exists())
        assertFalse(File(target.path + ".new").exists())
    }

    @Test
    fun archiveFailureCannotConsumeAndRecreatedViewModelRetriesTheSameArchive() = files { directory ->
        val disk = FileBackupReportStore(directory)
        var failArchive = true
        val store = object : BackupReportStore by disk {
            override suspend fun archive(commit: CommittedBackupImport): BackupReportItem {
                if (failArchive) throw IOException("合成磁盘故障")
                return disk.archive(commit)
            }
        }
        val operations = Operations(commit(1))
        var session = session(operations, store)
        try {
            awaitSuccess(session.vm)
            withContext(Dispatchers.Main) { session.vm.consumeImportResult(operations.commit.commitId) }
            val failure = withTimeout(10_000) { session.vm.reportSaveState.filterIsInstance<UiState.Error>().first() }
            assertEquals(com.zero.common.R.string.backup_import_report_save_failed, failure.messageRes)
            assertNull("可延迟展示的错误不缓存生成时语言", failure.message)
            assertEquals(0, operations.consumeCalls.get())
            assertTrue(disk.list().isEmpty())
            assertTrue(session.vm.importState.value is UiState.Success)

            failArchive = false
            operations.failConsume = true
            withContext(Dispatchers.Main) { session.vm.consumeImportResult(operations.commit.commitId) }
            withTimeout(10_000) { operations.consumeAttempted.await() }
            session.close()
            assertEquals(1, disk.list().size)
            operations.failConsume = false
            session = session(operations, FileBackupReportStore(directory))
            awaitSuccess(session.vm)
            withContext(Dispatchers.Main) { session.vm.consumeImportResult(operations.commit.commitId) }
            withTimeout(10_000) { session.vm.importState.filter { it == UiState.None }.first() }
            assertEquals(2, operations.consumeCalls.get())
            assertEquals(1, disk.list().size)
        } finally { session.close() }
    }

    @Test
    fun olderListCannotRestoreDeletedRowsAndDeleteFailureKeepsTheRemainingList() = files { directory ->
        val disk = FileBackupReportStore(directory)
        val item = disk.archive(commit(1))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val returned = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val delayed = object : BackupReportStore by disk {
            override suspend fun list(): List<BackupReportItem> {
                val result = disk.list()
                if (calls.incrementAndGet() == 1) {
                    entered.complete(Unit)
                    release.await()
                    returned.complete(Unit)
                }
                return result
            }
        }
        val session = session(Operations(null), delayed)
        try {
            withContext(Dispatchers.Main) { session.vm.loadReportList() }
            withTimeout(10_000) { entered.await() }
            withContext(Dispatchers.Main) { session.vm.deleteReport(item) }
            withTimeout(10_000) { session.vm.reportListState.filterIsInstance<UiState.Success<List<BackupReportItem>>>()
                .filter { it.data?.isEmpty() == true }.first() }
            release.complete(Unit)
            withTimeout(10_000) { returned.await() }
            withContext(Dispatchers.Main) {
                assertTrue((session.vm.reportListState.value as UiState.Success).data!!.isEmpty())
            }
            val remaining = disk.archive(commit(2))
            assertTrue(directory.setWritable(false, false))
            try {
                withContext(Dispatchers.Main) { session.vm.clearReports() }
                withTimeout(10_000) { session.vm.reportClearState.filterIsInstance<UiState.Error>().first() }
                assertEquals(remaining.filePath, disk.list().single().filePath)
            } finally { check(directory.setWritable(true, true)) }
        } finally { release.complete(Unit); session.close() }
    }

    private suspend fun session(operations: BackupImportOperations, reports: BackupReportStore): Session =
        withContext(Dispatchers.Main) {
            val model = BackupViewModel(app, SavedStateHandle(), operations, reports)
            Session(model, ViewModelStore().apply { put("test", model) })
        }

    private suspend fun awaitSuccess(vm: BackupViewModel) = withTimeout(10_000) {
        vm.importState.filterIsInstance<UiState.Success<CommittedBackupImport>>().first()
    }

    private data class Session(val vm: BackupViewModel, val store: ViewModelStore) {
        suspend fun close() {
            val job = vm.viewModelScope.coroutineContext[Job]
            withContext(Dispatchers.Main) { store.clear() }
            job?.join()
        }
    }

    private fun commit(count: Int) = CommittedBackupImport(UUID.randomUUID().toString(), BackupImportReport(
        BackupImportStrategy.MERGE, count, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
    ), emptyList())

    private class Operations(val pending: CommittedBackupImport?) : BackupImportOperations {
        val commit get() = requireNotNull(pending)
        val consumeCalls = AtomicInteger()
        val consumeAttempted = CompletableDeferred<Unit>()
        var failConsume = false
        private var consumed = false
        override suspend fun recoverReport() = if (consumed) null else pending
        override suspend fun consumeReport(commitId: String) {
            check(commitId == commit.commitId)
            consumeCalls.incrementAndGet()
            consumeAttempted.complete(Unit)
            if (failConsume) throw IOException("合成确认故障")
            consumed = true
        }
        override suspend fun read(uri: Uri): BabyBackupEnvelope = error("测试不读备份")
        override suspend fun execute(envelope: BabyBackupEnvelope, mode: BackupImportMode, dedupMinutes: Int): CommittedBackupImport =
            error("测试不导入备份")
        override fun release(uri: Uri) = Unit
    }

    private fun files(block: suspend (File) -> Unit) = runBlocking {
        val directory = File(app.cacheDir, "report-store-test-${UUID.randomUUID()}").apply { check(mkdirs()) }
        try { withTimeout(20_000) { block(directory) } }
        finally { directory.setWritable(true, true); directory.deleteRecursively() }
    }
}
