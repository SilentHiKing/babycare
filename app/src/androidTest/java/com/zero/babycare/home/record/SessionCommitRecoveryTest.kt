package com.zero.babycare.home.record

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.babycare.home.OngoingRecordManager
import com.zero.babycare.home.OngoingRecordManager.OngoingSession
import com.zero.babycare.home.OngoingRecordManager.RecordType
import com.zero.babycare.home.OngoingRecordManager.SessionStatus
import com.zero.babycare.navigation.NavTarget
import com.zero.babydata.entity.BabyInfo
import com.zero.babydata.entity.FeedingRecord
import com.zero.babydata.entity.FeedingType
import com.zero.babydata.entity.SleepRecord
import com.zero.babydata.room.BabyDatabase
import com.zero.babydata.room.BabyRepository
import com.zero.common.mmkv.MMKVStore
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** 真实 VM 提交后还原提交前 MMKV 快照，重建跨存储中断状态，不随机终止 SQLite。 */
@RunWith(AndroidJUnit4::class)
class SessionCommitRecoveryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var database: BabyDatabase
    private lateinit var repository: BabyRepository
    private val stores = mutableListOf<ViewModelStore>()
    private val ownedSessionKeys = mutableListOf<String>()

    @Before
    fun setUp() = runBlocking {
        assumeTrue("真实 VM 会刷新提醒调度，本隔离用例只在合成 AVD 运行",
            android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"))
        database = Room.inMemoryDatabaseBuilder(instrumentation.targetContext, BabyDatabase::class.java).build()
        repository = BabyRepository(database)
        repository.insertBabyInfo(BabyInfo(babyId = BABY_ID, name = "合成会话提交测试"))
        // 先确认全部目标 key 均不存在，再写合成值；不覆盖用户计时或调用真实提醒发布。
        RecordType.entries.forEach { type ->
            assertTrue("合成测试会话 key 已被占用", MMKVStore.getString(sessionKey(type)).isNullOrEmpty())
        }
    }

    @After
    fun tearDown() {
        onMain { stores.forEach(ViewModelStore::clear) }
        ownedSessionKeys.forEach { MMKVStore.remove(it) }
        if (::database.isInitialized) database.close()
    }

    @Test
    fun reopeningCommittedSleepSessionWithNewDraftDoesNotInsertAnotherRecord() = runBlocking {
        val originalSession = installPausedSession(RecordType.SLEEP)
        val firstRequest = request(NavTarget.SleepRecord(returnTarget = NavTarget.Dashboard))
        val record = SleepRecord(babyId = BABY_ID, sleepStart = START, sleepEnd = END,
            sleepDuration = END - START, note = "", createdAt = END)

        val firstModel = newModel { SleepRecordViewModel(repository) }
        onMain { firstModel.save(record, firstRequest, originalSession.sessionId) }
        assertNull(awaitResult(firstModel.mutation).errorRes)
        assertEquals(1, repository.getAllSleepRecords(BABY_ID).size)
        assertSingleReceipt()
        assertNull(OngoingRecordManager.getSleepSession(BABY_ID))
        restorePreCommitSession(originalSession)

        // 从首页重新进入会生成新页面草稿，MMKV 恢复的仍是刚才已提交的同一暂停会话。
        val reopenedRequest = request(firstRequest.source)
        val reopenedSession = requireNotNull(OngoingRecordManager.getSleepSession(BABY_ID))
        assertEquals(originalSession.sessionId, reopenedSession.sessionId)
        val newModel = newModel { SleepRecordViewModel(repository) }
        onMain { newModel.save(record.copy(createdAt = END + 1L), reopenedRequest, reopenedSession.sessionId) }
        assertNull(awaitResult(newModel.mutation).errorRes)
        assertEquals("重新进入同一已提交会话不能因新页面草稿生成第二条睡眠", 1,
            repository.getAllSleepRecords(BABY_ID).size)
    }

    @Test
    fun reopeningCommittedFeedingSessionWithNewDraftDoesNotInsertAnotherRecord() = runBlocking {
        val originalSession = installPausedSession(RecordType.FEEDING)
        val firstRequest = request(NavTarget.FeedingRecord(returnTarget = NavTarget.Dashboard))
        val record = FeedingRecord(babyId = BABY_ID, feedingType = FeedingType.BREAST.type,
            feedingStart = START, feedingEnd = END, feedingDuration = END - START,
            note = "", createdAt = END)

        val firstModel = newModel { FeedingRecordViewModel(repository) }
        onMain { firstModel.save(record, firstRequest, originalSession.sessionId) }
        assertNull(awaitResult(firstModel.mutation).errorRes)
        assertEquals(1, repository.getAllFeedingRecords(BABY_ID).size)
        assertSingleReceipt()
        assertNull(OngoingRecordManager.getFeedingSession(BABY_ID))
        restorePreCommitSession(originalSession)

        val reopenedRequest = request(firstRequest.source)
        val reopenedSession = requireNotNull(OngoingRecordManager.getFeedingSession(BABY_ID))
        assertEquals(originalSession.sessionId, reopenedSession.sessionId)
        val newModel = newModel { FeedingRecordViewModel(repository) }
        onMain { newModel.save(record.copy(createdAt = END + 1L), reopenedRequest, reopenedSession.sessionId) }
        assertNull(awaitResult(newModel.mutation).errorRes)
        assertEquals("重新进入同一已提交会话不能因新页面草稿生成第二条喂养", 1,
            repository.getAllFeedingRecords(BABY_ID).size)
    }

    @Test
    fun commitFreezesOtherEntrancesAndRestoredSnapshotFindsTheReceipt() = runBlocking {
        val session = installPausedSession(RecordType.SLEEP)
        val target = request(NavTarget.SleepRecord(returnTarget = NavTarget.Dashboard))
        val record = SleepRecord(babyId = BABY_ID, sleepStart = START, sleepEnd = END,
            sleepDuration = END - START, note = "合成冻结快照", createdAt = END)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val blocker = launch(Dispatchers.IO) {
            database.withTransaction { entered.complete(Unit); release.await() }
        }
        entered.await()
        try {
            val model = newModel { SleepRecordViewModel(repository) }
            onMain { model.save(record, target, session.sessionId) }
            val frozen = awaitPending(RecordType.SLEEP)
            assertEquals(record, frozen.pendingSleep)
            OngoingRecordManager.resumeSleep(BABY_ID, session.sessionId)
            OngoingRecordManager.updateSleepStart(BABY_ID, START - 1000L, session.sessionId)
            assertEquals("其它入口不能在提交中延续或改变这段记录", frozen, OngoingRecordManager.getSleepSession(BABY_ID))

            val reopened = newModel { SleepRecordViewModel(repository) }
            val newTarget = request(target.source)
            onMain { reopened.sessions.recover(newTarget, session.sessionId) }
            assertTrue(requireNotNull(reopened.sessions.recovery.value).loading)
            release.complete(Unit)
            assertNull(awaitResult(model.mutation).errorRes)
            val recovered = awaitRecovery(reopened.sessions)
            assertTrue(recovered.committed)
            assertNull(recovered.errorRes)
            assertNull(OngoingRecordManager.getSleepSession(BABY_ID))

            // 模拟同步快照已落盘、Room 已提交而清理未落盘；冷入口只确认成功，不再次插入。
            restorePreCommitSession(frozen)
            val coldModel = newModel { SleepRecordViewModel(repository) }
            onMain { coldModel.sessions.recover(request(target.source)) }
            assertTrue(awaitRecovery(coldModel.sessions).committed)
            assertNull(OngoingRecordManager.getSleepSession(BABY_ID))
            assertEquals(1, repository.getAllSleepRecords(BABY_ID).size)
        } finally {
            release.complete(Unit)
            blocker.join()
        }
    }

    @Test
    fun cancelledUncommittedWriteRecoversWithoutPermanentFreeze() = runBlocking {
        val session = installPausedSession(RecordType.FEEDING)
        OngoingRecordManager.updateFeedingType(BABY_ID, FeedingType.MIXED.type, session.sessionId)
        val target = request(NavTarget.FeedingRecord(returnTarget = NavTarget.Dashboard))
        val record = FeedingRecord(babyId = BABY_ID, feedingType = FeedingType.MIXED.type,
            feedingStart = START, feedingEnd = END, feedingDuration = END - START,
            feedingAmount = 120, feedingDurationBreastLeft = 123_456L,
            feedingDurationBreastRight = 76_543L, note = "合成取消")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val blocker = launch(Dispatchers.IO) {
            database.withTransaction { entered.complete(Unit); release.await() }
        }
        entered.await()
        try {
            val model = newModel { FeedingRecordViewModel(repository) }
            onMain { model.save(record, target, session.sessionId) }
            awaitPending(RecordType.FEEDING)
            onMain { stores.forEach(ViewModelStore::clear); stores.clear() }
            release.complete(Unit)
            blocker.join()
            val reopened = newModel { FeedingRecordViewModel(repository) }
            onMain { reopened.sessions.recover(request(target.source)) }
            val recovery = awaitRecovery(reopened.sessions)
            assertNull(recovery.errorRes)
            assertTrue(!recovery.committed)
            assertEquals(record, requireNotNull(recovery.session).pendingFeeding)
            assertTrue(!requireNotNull(OngoingRecordManager.getFeedingSession(BABY_ID)).pendingSave)
            assertEquals(0, repository.getAllFeedingRecords(BABY_ID).size)
            // 第一个恢复结果尚未交给 View 时再次退出，奶量、双侧时长与备注仍须耐久保留。
            onMain { stores.forEach(ViewModelStore::clear); stores.clear() }
            OngoingRecordManager.resumeFeeding(BABY_ID, session.sessionId)
            OngoingRecordManager.pauseFeeding(BABY_ID, END + 30_000L, END - START + 5_000L, session.sessionId)
            val secondReopened = newModel { FeedingRecordViewModel(repository) }
            onMain { secondReopened.sessions.recover(request(target.source)) }
            val secondRecovery = awaitRecovery(secondReopened.sessions)
            assertNull(secondRecovery.errorRes)
            val secondSession = requireNotNull(secondRecovery.session)
            assertEquals(record, secondSession.pendingFeeding)
            assertEquals(END + 30_000L, secondSession.endAt)
            assertEquals(END - START + 5_000L, secondSession.activeDurationMs)
            // 原输入保留，修改后仍可保存；没有已提交回执时不能永久 CONTENT_CONFLICT。
            onMain { secondReopened.save(record.copy(note = "合成重试", feedingEnd = END + 30_000L,
                feedingDuration = END - START + 5_000L), request(target.source), session.sessionId) }
            assertNull(awaitResult(secondReopened.mutation).errorRes)
            assertEquals("合成重试", repository.getAllFeedingRecords(BABY_ID).single().note)
        } finally {
            release.complete(Unit)
            blocker.join()
        }
    }

    @Test
    fun changedCommittedPayloadIsRejectedButNewSessionAtSameTimeIsIndependent() = runBlocking {
        val original = installPausedSession(RecordType.SLEEP)
        val target = request(NavTarget.SleepRecord(returnTarget = NavTarget.Dashboard))
        val record = SleepRecord(babyId = BABY_ID, sleepStart = START, sleepEnd = END,
            sleepDuration = END - START, note = "原始合成值")
        val first = newModel { SleepRecordViewModel(repository) }
        onMain { first.save(record, target, original.sessionId) }
        assertNull(awaitResult(first.mutation).errorRes)
        restorePreCommitSession(original)
        val retry = newModel { SleepRecordViewModel(repository) }
        onMain { retry.save(record.copy(note = "另一个值"), request(target.source), original.sessionId) }
        assertEquals(com.zero.common.R.string.record_draft_already_saved, awaitResult(retry.mutation).errorRes)
        assertEquals(1, repository.getAllSleepRecords(BABY_ID).size)
        assertEquals("另一个值", requireNotNull(OngoingRecordManager.getSleepSession(BABY_ID)).pendingSleep?.note)

        // 冲突只能保留并明确放弃，不能通过解锁后换 token 静默插入；丢弃也不能删已保存记录。
        onMain { retry.sessions.recover(request(target.source), original.sessionId) }
        assertEquals(com.zero.common.R.string.record_draft_already_saved, awaitRecovery(retry.sessions).errorRes)
        OngoingRecordManager.cancelSession(BABY_ID, RecordType.SLEEP, reschedule = {})
        assertNull(OngoingRecordManager.getSleepSession(BABY_ID))
        assertEquals(1, repository.getAllSleepRecords(BABY_ID).size)

        val replacement = original.copy(sessionId = UUID.randomUUID().toString())
        restorePreCommitSession(replacement)
        val stale = newModel { SleepRecordViewModel(repository) }
        onMain { stale.save(record, request(target.source), original.sessionId) }
        assertEquals(com.zero.common.R.string.record_draft_already_saved, awaitResult(stale.mutation).errorRes)
        assertEquals(replacement, OngoingRecordManager.getSleepSession(BABY_ID))
        OngoingRecordManager.cancelSession(BABY_ID, RecordType.SLEEP, original.sessionId, reschedule = {})
        assertEquals("旧页面确认放弃不能清除后来开始的会话", replacement, OngoingRecordManager.getSleepSession(BABY_ID))
        val intentional = newModel { SleepRecordViewModel(repository) }
        onMain { intentional.save(record, request(target.source), replacement.sessionId) }
        assertNull(awaitResult(intentional.mutation).errorRes)
        assertEquals(2, repository.getAllSleepRecords(BABY_ID).size)
    }

    private suspend fun awaitPending(type: RecordType): OngoingSession = withTimeout(10_000L) {
        var session = OngoingRecordManager.getSession(BABY_ID, type)
        while (session?.pendingSave != true) {
            delay(10L)
            session = OngoingRecordManager.getSession(BABY_ID, type)
        }
        requireNotNull(session)
    }

    private suspend fun awaitRecovery(coordinator: SessionCommitCoordinator) = withTimeout(10_000L) {
        coordinator.recovery.filterNotNull().first { !it.loading }
    }

    /** 两次请求都由真实 VM 选择；只还原会话，不回滚 Room，也不指定旧回执应使用哪个 token。 */
    private fun restorePreCommitSession(original: OngoingSession) {
        onMain { stores.forEach(ViewModelStore::clear); stores.clear() }
        MMKVStore.put(sessionKey(original.type), original, encrypt = true)
    }

    private fun assertSingleReceipt() {
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM Record_Write_Receipts").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("首轮真实保存必须已提交持久回执", 1L, cursor.getLong(0))
        }
    }

    private fun installPausedSession(type: RecordType): OngoingSession {
        val session = OngoingSession(BABY_ID, type, START, END, SessionStatus.PAUSED,
            feedingType = FeedingType.BREAST.type, activeDurationMs = END - START,
            sessionId = UUID.randomUUID().toString())
        val key = sessionKey(type)
        // 直接写独占 key，避免开始计时入口触及系统通知，也不更改全局追踪列表。
        ownedSessionKeys += key
        MMKVStore.put(key, session, encrypt = true)
        return session
    }

    private fun request(source: NavTarget) = RecordMutationController.Target(
        UUID.randomUUID().toString(), BABY_ID, null, source, NavTarget.Dashboard)

    private fun sessionKey(type: RecordType) = "ongoing_session_${type.name.lowercase()}_$BABY_ID"

    private suspend fun awaitResult(controller: RecordMutationController) = withTimeout(10_000L) {
        controller.state.filterIsInstance<RecordMutationController.State.Finished>().first()
    }

    private fun <T : ViewModel> newModel(factory: () -> T): T = onMain {
        factory().also { stores += ViewModelStore().apply { put("record", it) } }
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return requireNotNull(result).getOrThrow()
    }

    private companion object {
        const val BABY_ID = 2_010_008_001
        const val START = 1_577_836_800_000L
        const val END = START + 600_000L
    }
}
