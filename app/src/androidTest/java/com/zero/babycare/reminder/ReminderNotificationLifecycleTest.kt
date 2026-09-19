package com.zero.babycare.reminder

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.zero.babycare.home.BabyRecordChangeBus
import com.zero.babycare.home.OngoingRecordManager
import com.zero.babycare.settings.SettingsViewModel
import com.zero.babydata.domain.BabyDataHelper
import com.zero.babydata.entity.BabyInfo
import com.zero.babydata.room.BabyDatabase
import com.zero.babydata.room.BabyRepository
import com.zero.common.mmkv.MMKVKeys
import com.zero.common.mmkv.MMKVStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** 仅在审查用 AVD 发布合成通知；候选完全注入，不向应用数据库写入宝宝或记录。 */
@RunWith(AndroidJUnit4::class)
class ReminderNotificationLifecycleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val first = BabyInfo(babyId = 2_000_000_001, name = "合成通知 A")
    // 旧 %10000 算法会使这两个宝宝使用同一通知和 PendingIntent。
    private val second = BabyInfo(babyId = first.babyId + 10_000, name = "合成通知 B")
    private val sampledAt = System.currentTimeMillis()
    private val candidate = ReminderCandidate(sampledAt - 1_000L, 1_577_836_800_000L, sampledAt + 60_000L)
    private var originallyEnabled = false
    private var restoreSettings = false
    private val originalKeys = mutableMapOf<String, Long>()

    @Before
    fun setUp() {
        assumeTrue("合成通知只允许在审查用 AVD 发布", Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        originallyEnabled = MMKVStore.getBoolean(MMKVKeys.SETTINGS_REMINDER_ENABLED, false)
        restoreSettings = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        // 投递用独立合成数据；先停止上一项的真实规划，避免它按应用库合理清理这些通知。
        WorkManager.getInstance(context).cancelAllWorkByTag(ReminderScheduler.TAG_REMINDER)
            .result.get(5, TimeUnit.SECONDS)
        listOf(first, second).forEach { baby ->
            assertNull(OngoingRecordManager.getFeedingSession(baby.babyId))
            assertNull(OngoingRecordManager.getSleepSession(baby.babyId))
            ReminderType.entries.forEach { type ->
                listOf(ReminderPolicy.lastReminderKey(type, baby.babyId), ReminderPolicy.notifiedSourceKey(type, baby.babyId))
                    .forEach { key -> originalKeys[key] = MMKVStore.getLong(key); MMKVStore.remove(key) }
            }
        }
        MMKVStore.put(MMKVKeys.SETTINGS_REMINDER_ENABLED, true)
        assertTrue("AVD 通知权限和提醒渠道必须开启", ReminderNotifier.canPostNotifications(context))
    }

    @After
    fun tearDown() {
        if (!restoreSettings) return
        listOf(first, second).forEach { baby ->
            ReminderNotifier.clearForBaby(context, baby.babyId)
            OngoingRecordManager.cancelFeeding(baby.babyId)
        }
        originalKeys.forEach { (key, value) -> if (value == 0L) MMKVStore.remove(key) else MMKVStore.put(key, value) }
        MMKVStore.put(MMKVKeys.SETTINGS_REMINDER_ENABLED, originallyEnabled)
        ReminderScheduler.ensureScheduled(context)
    }

    @Test
    fun differentBabiesHaveDistinctNotificationsAndScopedClearOnlyRemovesItsBaby() = runBlocking {
        assertEquals(ReminderDeliveryResult.DELIVERED, deliver(first))
        assertEquals(ReminderDeliveryResult.DELIVERED, deliver(second))
        awaitNotifications(2)
        val posted = syntheticNotifications()
        assertNotEquals(posted[0].notification.contentIntent, posted[1].notification.contentIntent)

        // 单宝宝撤回不影响另一宝宝；总线异步查询失效另有独立集成用例。
        ReminderNotifier.clearForBaby(context, first.babyId)
        awaitNotifications(1)
        assertEquals("babycare_reminder:${second.babyId}", syntheticNotifications().single().tag)
    }

    @Test
    fun disablingRemindersRemovesShownAndLegacyNotificationsAndSuppressesLateQuery() = runBlocking {
        assertEquals(ReminderDeliveryResult.DELIVERED, deliver(second))
        // 已升级应用也可能仍有旧版本的无 tag 提醒。
        manager.notify(21_002, NotificationCompat.Builder(context, "record_reminder")
            .setSmallIcon(com.zero.common.R.drawable.ic_baby_default).setContentTitle("合成旧提醒").build())
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val pending = async { deliver(first, beforeCandidate = { entered.complete(Unit); release.await() }) }
        entered.await()
        instrumentation.runOnMainSync { SettingsViewModel().setReminderEnabled(false) }
        release.complete(Unit)
        assertEquals(ReminderDeliveryResult.SUPPRESSED, pending.await())
        awaitNotifications(0)
        withTimeout(5_000L) { while (manager.activeNotifications.any { it.tag == null && it.id == 21_002 }) delay(20L) }
        assertEquals(0L, MMKVStore.getLong(ReminderPolicy.notifiedSourceKey(ReminderType.FEEDING, first.babyId)))
    }

    @Test
    fun recordChangeRejectsCandidateLoadedBeforeCommitWithoutMarkingItDelivered() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val pending = async { deliver(first, beforeCandidate = { entered.complete(Unit); release.await() }) }
        entered.await()
        BabyRecordChangeBus.notifyChanged(first.babyId)
        release.complete(Unit)
        assertEquals(ReminderDeliveryResult.SUPPRESSED, pending.await())
        awaitNotifications(0)
        assertEquals(0L, MMKVStore.getLong(ReminderPolicy.notifiedSourceKey(ReminderType.FEEDING, first.babyId)))
    }

    @Test
    fun startingTimerRemovesShownReminderAndRejectsLateCandidate() = runBlocking {
        assertEquals(ReminderDeliveryResult.DELIVERED, deliver(first))
        awaitNotifications(1)
        MMKVStore.remove(ReminderPolicy.notifiedSourceKey(ReminderType.FEEDING, first.babyId))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val pending = async { deliver(first, beforeCandidate = { entered.complete(Unit); release.await() }) }
        entered.await()
        instrumentation.runOnMainSync { OngoingRecordManager.startFeeding(first.babyId, startTime = candidate.dueTime) }
        release.complete(Unit)
        assertEquals(ReminderDeliveryResult.SUPPRESSED, pending.await())
        awaitNotifications(0)
    }

    @Test
    fun candidateExpiringDuringQueryIsNotPostedOrMarkedDelivered() = runBlocking {
        var now = candidate.dueTime
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val pending = async {
            deliver(first, beforeCandidate = { entered.complete(Unit); release.await() }, clock = { now })
        }
        entered.await()
        now = candidate.expiresAt + 1L
        release.complete(Unit)
        assertEquals(ReminderDeliveryResult.SUPPRESSED, pending.await())
        assertNoDeliveryRecorded()
    }

    @Test
    fun candidateExpiringAtFinalPublishCheckIsNotPostedOrMarkedDelivered() = runBlocking {
        var clockReads = 0
        val result = deliver(first, clock = {
            clockReads++
            if (clockReads == 1) candidate.expiresAt else candidate.expiresAt + 1L
        })
        assertEquals(ReminderDeliveryResult.SUPPRESSED, result)
        assertEquals("查询返回和真正发布前都必须检查截止时间", 2, clockReads)
        assertNoDeliveryRecorded()
    }

    @Test
    fun delayedWorkerCanStillDeliverAtInclusiveWindowEnd() = runBlocking {
        assertEquals(ReminderDeliveryResult.DELIVERED, deliver(first, clock = { candidate.expiresAt }))
        awaitNotifications(1)
        assertEquals(candidate.expiresAt, MMKVStore.getLong(ReminderPolicy.lastReminderKey(ReminderType.FEEDING, first.babyId)))
        assertEquals(candidate.sourceRecordTime, MMKVStore.getLong(ReminderPolicy.notifiedSourceKey(ReminderType.FEEDING, first.babyId)))
    }

    @Test
    fun committedBabyDeletionIsReconciledWhenStartupSchedulingResumes() = runBlocking {
        // 合成对象只写独立库；实际恢复 Worker 读取应用库时也必须确认该身份不存在。
        assertNull(BabyDataHelper.repository.getBabyInfo(first.babyId))
        val workManager = WorkManager.getInstance(context)
        workManager.cancelAllWorkByTag(ReminderScheduler.TAG_REMINDER).result.get(5, TimeUnit.SECONDS)
        val database = Room.inMemoryDatabaseBuilder(context, BabyDatabase::class.java).build()
        val repository = BabyRepository(database)
        try {
            repository.insertBabyInfo(first.copy())
            val source = object : ReminderDataSource {
                override suspend fun getBaby(babyId: Int) = repository.getBabyInfo(babyId)
                override suspend fun getCandidate(baby: BabyInfo, type: ReminderType) = candidate
            }
            assertEquals(ReminderDeliveryResult.DELIVERED, ReminderNotifier.notifyIfDue(
                context, first.babyId, ReminderType.FEEDING, candidate.dueTime, source
            ))
            awaitNotifications(1)

            // 明确重建 Room 已提交、clearForBaby 尚未执行的退出窗口，不随机中断 SQLite。
            repository.deleteBabyInfo(first)
            assertNull(repository.getBabyInfo(first.babyId))
            val previousPlanIds = workManager.getWorkInfosForUniqueWork("babycare_reminder_plan")
                .get(5, TimeUnit.SECONDS).map { it.id }.toSet()
            ReminderScheduler.ensureScheduledAndAwait(context)
            // 等真实启动规划完成，不能仅以 WorkManager 接受入队作为恢复成功。
            withTimeout(15_000L) {
                while (workManager.getWorkInfosForUniqueWork("babycare_reminder_plan")
                        .get(5, TimeUnit.SECONDS).none { it.id !in previousPlanIds && it.state == WorkInfo.State.SUCCEEDED }) {
                    delay(50L)
                }
            }
            withTimeoutOrNull(2_000L) {
                while (syntheticNotifications().isNotEmpty()) delay(20L)
            }
            assertEquals("删除提交后的启动恢复应撤下已不存在宝宝的通知", 0, syntheticNotifications().size)
        } finally {
            database.close()
        }
    }

    @Test
    fun missingBabyCleanupPreservesExistingBabyAndRejectsLateCandidate() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, BabyDatabase::class.java).build()
        val repository = BabyRepository(database)
        val release = CompletableDeferred<Unit>()
        try {
            repository.insertBabyInfo(first.copy())
            repository.insertBabyInfo(second.copy())
            val source = object : ReminderDataSource {
                override suspend fun getBaby(babyId: Int) = repository.getBabyInfo(babyId)
                override suspend fun getCandidate(baby: BabyInfo, type: ReminderType) = candidate
            }
            suspend fun deliverFromRoom(baby: BabyInfo) = ReminderNotifier.notifyIfDue(
                context, baby.babyId, ReminderType.FEEDING, candidate.dueTime, source
            )
            assertEquals(ReminderDeliveryResult.DELIVERED, deliverFromRoom(first))
            assertEquals(ReminderDeliveryResult.DELIVERED, deliverFromRoom(second))
            awaitNotifications(2)
            MMKVStore.remove(ReminderPolicy.notifiedSourceKey(ReminderType.FEEDING, first.babyId))
            val entered = CompletableDeferred<Unit>()
            val late = async { deliver(first, beforeCandidate = { entered.complete(Unit); release.await() }) }
            entered.await()
            repository.deleteBabyInfo(first)
            ReminderNotifier.reconcileMissingBabies(context, source)
            awaitNotifications(1)
            assertEquals("babycare_reminder:${second.babyId}", syntheticNotifications().single().tag)
            release.complete(Unit)
            assertEquals(ReminderDeliveryResult.SUPPRESSED, late.await())
            assertEquals(0L, MMKVStore.getLong(ReminderPolicy.notifiedSourceKey(ReminderType.FEEDING, first.babyId)))

            // 到期任务无需等下次启动规划，也应在 Room 返回缺失时撤掉原来的通知。
            repository.deleteBabyInfo(second)
            assertEquals(ReminderDeliveryResult.SUPPRESSED, deliverFromRoom(second))
            awaitNotifications(0)
        } finally {
            release.complete(Unit)
            database.close()
        }
    }

    private suspend fun assertNoDeliveryRecorded() {
        awaitNotifications(0)
        assertEquals(0L, MMKVStore.getLong(ReminderPolicy.lastReminderKey(ReminderType.FEEDING, first.babyId)))
        assertEquals(0L, MMKVStore.getLong(ReminderPolicy.notifiedSourceKey(ReminderType.FEEDING, first.babyId)))
    }

    private suspend fun deliver(
        baby: BabyInfo,
        beforeCandidate: suspend () -> Unit = {},
        clock: () -> Long = System::currentTimeMillis
    ): ReminderDeliveryResult =
        ReminderNotifier.notifyIfDue(context, baby.babyId, ReminderType.FEEDING, candidate.dueTime,
            object : ReminderDataSource {
                override suspend fun getBaby(babyId: Int) = baby
                override suspend fun getCandidate(baby: BabyInfo, type: ReminderType): ReminderCandidate {
                    beforeCandidate()
                    return candidate
                }
            }, clock)

    private fun syntheticNotifications() = manager.activeNotifications.filter {
        it.tag == "babycare_reminder:${first.babyId}" || it.tag == "babycare_reminder:${second.babyId}"
    }

    private suspend fun awaitNotifications(count: Int) = withTimeout(5_000L) {
        while (syntheticNotifications().size != count) delay(20L)
    }
}
