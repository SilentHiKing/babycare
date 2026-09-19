package com.zero.babycare.reminder

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.babycare.MainViewModel
import com.zero.babycare.navigation.NavTarget
import com.zero.babydata.entity.BabyInfo
import com.zero.common.mmkv.MMKVKeys
import com.zero.common.mmkv.MMKVStore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 使用可控 Room 快照替身验证冷启动通知导航，不向用户库写入合成宝宝。 */
@RunWith(AndroidJUnit4::class)
class ReminderNavigationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val stores = mutableListOf<ViewModelStore>()
    private val first = BabyInfo(babyId = 2_000_000_021, name = "合成宝宝 A")
    private val second = BabyInfo(babyId = 2_000_000_022, name = "合成宝宝 B")
    private var oldSelectedId = 0
    private var oldLegacy: String? = null

    @Before
    fun setUp() {
        oldSelectedId = MMKVStore.getInt(MMKVKeys.CURRENT_BABY_ID)
        oldLegacy = MMKVStore.getString(MMKVKeys.LEGACY_BABY_INFO)
    }

    @After
    fun tearDown() {
        onMain { stores.forEach(ViewModelStore::clear) }
        if (oldSelectedId == 0) MMKVStore.remove(MMKVKeys.CURRENT_BABY_ID)
        else MMKVStore.put(MMKVKeys.CURRENT_BABY_ID, oldSelectedId)
        MMKVStore.put(MMKVKeys.LEGACY_BABY_INFO, oldLegacy)
    }

    @Test
    fun notificationWaitsForFirstSnapshotAndMostRecentClickWins() = runBlocking {
        val snapshots = MutableSharedFlow<List<BabyInfo>>(replay = 1)
        val vm = createModel(snapshots)
        onMain {
            vm.openReminderTarget(first.babyId, ReminderType.FEEDING, first.stableId)
            vm.openReminderTarget(second.babyId, ReminderType.SLEEP, second.stableId)
            assertFalse(vm.babyDataLoaded.value)
            assertEquals(NavTarget.Dashboard, vm.navTarget.value)
            snapshots.tryEmit(listOf(first, second))
        }
        awaitLoaded(vm)
        onMain {
            assertEquals(second, vm.currentBaby.value)
            assertEquals(NavTarget.SleepRecord(returnTarget = NavTarget.Dashboard), vm.navTarget.value)
        }
    }

    @Test
    fun manualNavigationWhileLoadingPreventsLateNotificationFromStealingPage() = runBlocking {
        val snapshots = MutableSharedFlow<List<BabyInfo>>(replay = 1)
        val vm = createModel(snapshots)
        val destination = NavTarget.Settings(returnTarget = NavTarget.Dashboard)
        onMain {
            vm.openReminderTarget(first.babyId, ReminderType.FEEDING, first.stableId)
            vm.navigateTo(destination)
            snapshots.tryEmit(listOf(first, second))
        }
        awaitLoaded(vm)
        onMain { assertEquals(destination, vm.navTarget.value) }
    }

    @Test
    fun oldNotificationCannotSelectReplacedIdentityAndUsesLatestProfileSnapshot() = runBlocking {
        val replacement = first.copy(stableId = second.stableId, name = "当前资料")
        val snapshots = MutableSharedFlow<List<BabyInfo>>(replay = 1)
        val vm = createModel(snapshots)
        onMain {
            vm.openReminderTarget(first.babyId, ReminderType.FEEDING, first.stableId)
            snapshots.tryEmit(listOf(replacement))
        }
        awaitLoaded(vm)
        onMain {
            assertEquals(NavTarget.Dashboard, vm.navTarget.value)
            assertEquals(replacement, vm.currentBaby.value)
            vm.openReminderTarget(replacement.babyId, ReminderType.FEEDING, replacement.stableId)
            assertEquals(NavTarget.FeedingRecord(returnTarget = NavTarget.Dashboard), vm.navTarget.value)
            assertEquals(replacement, vm.currentBaby.value)
        }
    }

    private fun createModel(snapshots: MutableSharedFlow<List<BabyInfo>>) = onMain {
        MainViewModel(SavedStateHandle(), snapshots).also {
            stores += ViewModelStore().apply { put("main", it) }
        }
    }

    private suspend fun awaitLoaded(vm: MainViewModel) {
        withTimeout(5_000L) { vm.babyDataLoaded.first { it } }
        instrumentation.waitForIdleSync()
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return requireNotNull(result).getOrThrow()
    }
}
