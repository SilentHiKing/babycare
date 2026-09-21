package com.zero.babycare.settings.backup

import android.app.Application
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zero.babydata.backup.*
import com.zero.babydata.domain.TimerRecordKind
import com.zero.babydata.entity.BabyInfo
import com.zero.babydata.entity.SleepRecord
import com.zero.babydata.room.BabyDatabase
import com.zero.babydata.room.BabyRepository
import com.zero.common.mmkv.MMKVKeys
import com.zero.common.mmkv.MMKVStore
import com.zero.common.util.TimerClockSnapshot
import com.zero.components.base.vm.UiState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.io.File

/** 独立库、合成高位 ID 与受控调度；绝不向应用真实数据库导入样本。 */
@RunWith(AndroidJUnit4::class)
class BackupCommitRecoveryTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun postCommitFailureReturnsSuccessAndRetainsRetryableCleanup() = database { db ->
        val effects = Effects(failRefresh = true)
        val operations = ApplicationBackupImportOperations(app, db, effects)
        val result = operations.execute(envelope(), BackupImportMode.MERGE, 0)
        assertEquals(1, result.report.babyInserted)
        assertEquals(1, db.babyInfoDao().getAllBabyInfo().size)
        val journal = BackupImportJournal(db)
        assertEquals(result.commitId, journal.pendingCleanup().single().commitId)
        assertEquals(result.commitId, operations.recoverReport()?.commitId)
        operations.consumeReport(result.commitId)
        assertNull(journal.oldestUnconsumed())
        effects.failRefresh = false
        assertNull(operations.recoverReport())
        assertTrue(journal.pendingCleanup().isEmpty())
    }

    @Test
    fun cancellationAfterCommitAndReopenRestoreReportBeforeOldUriWithoutReimport() = runBlocking {
        val name = "backup-app-commit-${UUID.randomUUID()}.db"
        val reports = File(app.cacheDir, "backup-commit-reports-${UUID.randomUUID()}")
        var db = Room.databaseBuilder(app, BabyDatabase::class.java, name).build()
        val entered = CompletableDeferred<Unit>()
        val effects = object : BackupImportSideEffects {
            override suspend fun clearReplacedBabyState(babyIds: List<Int>) = Unit
            override suspend fun refreshRecords() { entered.complete(Unit); awaitCancellation() }
        }
        var store: ViewModelStore? = null
        try {
            val original = ApplicationBackupImportOperations(app, db, effects)
            val job = launch(Dispatchers.IO) { original.execute(envelope(), BackupImportMode.MERGE, 0) }
            withTimeout(10_000) { entered.await() }
            job.cancelAndJoin()
            val expectedId = requireNotNull(BackupImportJournal(db).oldestUnconsumed()).commitId
            db.close()
            db = Room.databaseBuilder(app, BabyDatabase::class.java, name).build()
            val recovered = ApplicationBackupImportOperations(app, db, Effects())
            var reads = 0
            var writes = 0
            val operations = object : BackupImportOperations by recovered {
                override suspend fun read(uri: Uri): BabyBackupEnvelope { reads++; error("已提交结果不应读取旧 URI") }
                override suspend fun execute(envelope: BabyBackupEnvelope, mode: BackupImportMode, dedupMinutes: Int): CommittedBackupImport {
                    writes++; error("恢复不得重放导入")
                }
            }
            val handle = SavedStateHandle(mapOf(BackupViewModel.PENDING_IMPORT_URI_KEY to "file:/old-synthetic-backup.json"))
            val vm = withContext(Dispatchers.Main) {
                BackupViewModel(app, handle, operations, FileBackupReportStore(reports)).also { model ->
                    store = ViewModelStore().apply { put("test", model) }
                }
            }
            val result = withTimeout(10_000) { vm.importState.filterIsInstance<UiState.Success<CommittedBackupImport>>().first().data!! }
            assertEquals(expectedId, result.commitId)
            assertEquals(0, reads)
            assertEquals(0, writes)
            assertNull(handle.get<String>(BackupViewModel.PENDING_IMPORT_URI_KEY))
            assertEquals(1, db.babyInfoDao().getAllBabyInfo().size)
            assertTrue(BackupImportJournal(db).pendingCleanup().isEmpty())
            withContext(Dispatchers.Main) { vm.consumeImportResult(expectedId) }
            withTimeout(10_000) { vm.importState.filter { it == UiState.None }.first() }
            assertNull(BackupImportJournal(db).oldestUnconsumed())
        } finally {
            withContext(Dispatchers.Main) { store?.clear() }
            db.close(); app.deleteDatabase(name)
            reports.deleteRecursively()
        }
    }

    @Test
    fun noChangeMergeKeepsNotifiedSourcesAndOngoingSessions() = database { db ->
        val babyId = SYNTHETIC_BASE
        val otherId = SYNTHETIC_BASE + 1
        withSyntheticCaches(listOf(babyId, otherId)) {
            val baby = BabyInfo(babyId = babyId, name = "合成宝宝")
            db.babyInfoDao().insertBabyInfo(baby)
            db.babyInfoDao().insertBabyInfo(BabyInfo(babyId = otherId, name = "另一合成宝宝"))
            val timers = BabyRepository(db).timers
            val sessions = listOf(babyId, otherId).associateWith { id ->
                timers.startSleep(SleepRecord(babyId = id, sleepStart = 1_000L), TimerClockSnapshot(1_000L))
            }
            val data = envelope(baby)
            val effects = cacheEffects()
            val report = ApplicationBackupImportOperations(app, db, effects).execute(data, BackupImportMode.MERGE, 0).report
            assertEquals(0, report.babyInserted)
            assertEquals(1, report.babyMatched)
            listOf(babyId, otherId).forEach { id ->
                prefixes.forEach { assertEquals(123L, MMKVStore.getLong(it + id)) }
                assertEquals(sessions[id], timers.findUnfinished(TimerRecordKind.SLEEP, id))
            }
        }
    }

    @Test
    fun overwriteCleanupOnlyTouchesOldIdsAndPreservesNewSessionStartedAfterCommit() = database { db ->
        val oldId = SYNTHETIC_BASE + 2
        val newId = oldId + 1
        withSyntheticCaches(listOf(oldId, newId)) {
            val baby = BabyInfo(babyId = oldId, name = "合成宝宝")
            db.babyInfoDao().insertBabyInfo(baby)
            val timers = BabyRepository(db).timers
            timers.startSleep(SleepRecord(babyId = oldId, sleepStart = 1_000L), TimerClockSnapshot(1_000L))
            val data = envelope(baby)
            val productionEffects = cacheEffects()
            val effects = object : BackupImportSideEffects by productionEffects {
                override suspend fun clearReplacedBabyState(babyIds: List<Int>) {
                    // 真正处于覆盖事务提交后、清理前窗口；新会话写入隔离 Room 的新宝宝行。
                    assertEquals(newId, db.babyInfoDao().getAllBabyInfo().single().babyId)
                    timers.startSleep(SleepRecord(babyId = newId, sleepStart = 2_000L), TimerClockSnapshot(2_000L))
                    productionEffects.clearReplacedBabyState(babyIds)
                }
            }
            ApplicationBackupImportOperations(app, db, effects).execute(data, BackupImportMode.OVERWRITE, 0)
            assertEquals(newId, db.babyInfoDao().getAllBabyInfo().single().babyId)
            assertNull(timers.findUnfinished(TimerRecordKind.SLEEP, oldId))
            prefixes.forEach { assertEquals(0L, MMKVStore.getLong(it + oldId)) }
            assertEquals(2_000L, timers.findUnfinished(TimerRecordKind.SLEEP, newId)?.startAt)
            prefixes.forEach { assertEquals(123L, MMKVStore.getLong(it + newId)) }
        }
    }

    private fun cacheEffects(): BackupImportSideEffects {
        val production = ApplicationBackupImportSideEffects(app)
        return object : BackupImportSideEffects {
            override suspend fun clearReplacedBabyState(babyIds: List<Int>) = production.clearReplacedBabyState(babyIds)
            override suspend fun refreshRecords() = Unit // 不调度真实用户的提醒。
        }
    }

    private suspend fun withSyntheticCaches(ids: List<Int>, block: suspend () -> Unit) {
        assumeTrue(ids.all { id -> prefixes.all { MMKVStore.getLong(it + id) == 0L } })
        try {
            ids.forEach { id ->
                prefixes.forEach { MMKVStore.put(it + id, 123L) }
            }
            block()
        } finally {
            ids.forEach { id -> prefixes.forEach { MMKVStore.remove(it + id) } }
        }
    }

    private class Effects(var failRefresh: Boolean = false) : BackupImportSideEffects {
        override suspend fun clearReplacedBabyState(babyIds: List<Int>) = Unit
        override suspend fun refreshRecords() { if (failRefresh) error("合成调度失败") }
    }

    private fun envelope(baby: BabyInfo = BabyInfo(name = "合成宝宝")) = BabyBackupEnvelope(BABY_BACKUP_VERSION, 1L, "test", BabyBackupData(
        listOf(BackupBaby(baby.stableId, baby.name, baby.gender, baby.birthDate, baby.birthWeight, baby.birthHeight, baby.bloodType, baby.extra)),
        emptyList(), emptyList(), emptyList(), emptyList()
    ))

    private fun database(block: suspend (BabyDatabase) -> Unit) = runBlocking(Dispatchers.IO) {
        val db = Room.inMemoryDatabaseBuilder(app, BabyDatabase::class.java).build()
        try { block(db) } finally { db.close() }
    }

    private companion object {
        const val SYNTHETIC_BASE = 1_610_000_001
        val prefixes = listOf(MMKVKeys.SETTINGS_FEEDING_REMINDER_LAST_PREFIX, MMKVKeys.SETTINGS_SLEEP_REMINDER_LAST_PREFIX,
            MMKVKeys.SETTINGS_FEEDING_REMINDER_SOURCE_PREFIX, MMKVKeys.SETTINGS_SLEEP_REMINDER_SOURCE_PREFIX)
    }
}
