package com.zero.babycare.babyinfo

import android.os.Bundle
import android.os.Parcel
import android.os.SystemClock
import android.widget.EditText
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.babycare.MainActivity
import com.zero.babycare.MainViewModel
import com.zero.babycare.R
import com.zero.babycare.navigation.NavTarget
import com.zero.babydata.entity.BabyInfo
import com.zero.babydata.room.BabyDatabase
import com.zero.babydata.room.BabyRepository
import com.zero.common.util.UnitConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Serializable
import java.util.concurrent.atomic.AtomicInteger

/** 数据写入只使用独立内存库；真实 Activity 用例只编辑未保存的合成表单。 */
@RunWith(AndroidJUnit4::class)
class BabyInfoRecoveryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val sessions = mutableListOf<Session>()
    private val target = NavTarget.BabyInfo.create(NavTarget.Settings())

    @After
    fun clearViewModels() = runBlocking {
        withContext(Dispatchers.Main) { sessions.forEach { it.store.clear() } }
    }

    @Test
    fun pendingSaveBlocksDuplicatesAndCannotClearOrNavigateReplacementDraft() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val operations = object : BabyInfoSaveOperations {
            override suspend fun create(baby: BabyInfo): Int {
                calls.incrementAndGet()
                started.complete(Unit)
                release.await()
                return 71
            }
            override suspend fun update(baby: BabyInfo) = false
        }
        val session = session(operations)
        lateinit var submittedDraft: BabyInfoDraft
        lateinit var replacement: BabyInfoDraft
        withContext(Dispatchers.Main) {
            submittedDraft = draft(session, target, "合成资料甲")
            session.vm.save(BabyInfo(name = "合成资料甲"), target)
            session.vm.save(BabyInfo(name = "合成资料甲"), target)
        }
        withTimeout(5_000) { started.await() }
        withContext(Dispatchers.Main) {
            session.vm.clearDraft()
            replacement = draft(session, target, "合成资料乙")
        }
        release.complete(Unit)
        val result = finished(session)
        assertEquals(1, calls.get())
        assertEquals(submittedDraft.draftId, result.draftId)
        assertFalse(result.belongsTo(replacement, target))
        assertFalse(result.belongsTo(submittedDraft, NavTarget.Settings()))
        withContext(Dispatchers.Main) {
            assertTrue(session.vm.consumeSaveResult(result))
            assertEquals(replacement, session.vm.getDraft(target.key()))
            assertFalse(session.vm.consumeSaveResult(result))
        }
    }

    @Test
    fun parcelledCreateRetryKeepsIdentityAndLaterProfileEdits() = withDatabase { repository ->
        val operations = operations(repository)
        val original = session(operations)
        lateinit var originalDraft: BabyInfoDraft
        withContext(Dispatchers.Main) {
            originalDraft = draft(original, target, "合成创建")
            original.vm.save(BabyInfo(name = "合成创建"), target)
        }
        val first = finished(original)
        // 数据已提交但结果未消费，模拟进程在此窗口消失；恢复使用全新的 VM。
        val snapshot = snapshot(original)
        withContext(Dispatchers.Main) { original.store.clear() }
        val laterProfile = repository.getAllBabyInfo().single().copy(name = "后来编辑的资料", birthHeight = 51.2f)
        assertTrue(repository.updateBabyInfoIfPresent(laterProfile))
        val restored = session(operations, snapshot)
        withContext(Dispatchers.Main) {
            assertEquals(originalDraft.stableId, restored.vm.getDraft(target.key())?.stableId)
            restored.vm.save(BabyInfo(name = "合成创建"), target)
        }
        val repeated = finished(restored)
        assertEquals(first.result, repeated.result)
        assertEquals(laterProfile.babyId, repeated.result?.babyId)
        assertEquals(originalDraft.stableId, repository.getAllBabyInfo().single().stableId)
        // VM 完成结果仅含身份，页面继续观察 Room 的最新资料，不能发布旧姓名/身高快照。
        assertEquals(laterProfile, repository.getAllBabyInfo().single())
        withContext(Dispatchers.Main) { assertTrue(restored.vm.consumeSaveResult(repeated)) }
        assertEquals(laterProfile, repository.getAllBabyInfo().single())
    }

    @Test
    fun restoredChangedOrDeletedCreateReportsFailureAndKeepsInput() = withDatabase { repository ->
        val operations = operations(repository)
        val original = session(operations)
        withContext(Dispatchers.Main) {
            draft(original, target, "合成创建")
            original.vm.save(BabyInfo(name = "合成创建"), target)
        }
        val first = finished(original)
        val snapshot = snapshot(original)
        val restored = session(operations, snapshot)
        withContext(Dispatchers.Main) {
            draft(restored, target, "修改后草稿")
            restored.vm.save(BabyInfo(name = "修改后草稿"), target)
        }
        val conflict = finished(restored)
        assertEquals(com.zero.common.R.string.baby_profile_save_conflict, conflict.errorRes)
        withContext(Dispatchers.Main) {
            restored.vm.consumeSaveResult(conflict)
            assertEquals("修改后草稿", restored.vm.getDraft(target.key())?.form?.name)
        }
        repository.deleteBabyInfo(requireNotNull(repository.getBabyInfo(requireNotNull(first.result).babyId)))
        withContext(Dispatchers.Main) { restored.vm.save(BabyInfo(name = "合成创建"), target) }
        val deleted = finished(restored)
        assertEquals(com.zero.common.R.string.baby_profile_deleted, deleted.errorRes)
        assertTrue(repository.getAllBabyInfo().isEmpty())
        assertNotNull(restored.vm.getDraft(target.key()))
    }

    @Test
    fun restoredMeasurementsUseDraftUnitsRatherThanNewDefaults() = runBlocking {
        val original = session(unusedOperations())
        val originalDraft = withContext(Dispatchers.Main) {
            draft(original, target, "合成单位").copy(
                form = BabyInfoFormSnapshot(name = "合成单位", weight = "7.25", height = "20.5"),
                weightUnit = UnitConfig.WEIGHT_UNIT_LB, heightUnit = UnitConfig.HEIGHT_UNIT_IN
            ).also { original.handle["baby_info_draft"] = it }
        }
        val restored = session(unusedOperations(), snapshot(original))
        withContext(Dispatchers.Main) {
            val draft = requireNotNull(restored.vm.getDraft(target.key()))
            restored.vm.refreshUnitState(draft)
            assertEquals(originalDraft, draft)
            assertEquals(UnitConfig.WEIGHT_UNIT_LB, restored.vm.unitState.value.weightUnit)
            assertEquals(com.zero.common.R.string.unit_lb_abbr, restored.vm.unitState.value.weightUnitLabelResId)
            assertEquals(UnitConfig.HEIGHT_UNIT_IN, restored.vm.unitState.value.heightUnit)
            assertEquals(3288.5447f, requireNotNull(restored.vm.parseBirthWeightToStorage(draft.form.weight)), 0.01f)
            assertEquals(52.07f, requireNotNull(restored.vm.parseBirthHeightToStorage(draft.form.height)), 0.001f)
        }
    }

    @Test
    fun activityRecreationKeepsIdentityAndUnsavedInputsButAnotherBabyGetsFreshForm() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            awaitActivity(scenario) { mainVm(it).babyDataLoaded.value }
            var existingId = 0
            scenario.onActivity {
                assumeTrue(mainVm(it).getAllBabies().isNotEmpty())
                existingId = mainVm(it).getAllBabies().first().babyId
                mainVm(it).navigateTo(target)
            }
            awaitActivity(scenario) { fragment(it).view?.isShown == true && viewModel(it).getDraft(target.key()) != null }
            lateinit var before: BabyInfoDraft
            scenario.onActivity { activity ->
                fragment(activity).requireView().apply {
                    findViewById<EditText>(R.id.etName).setText("AuditProfileDraft")
                    findViewById<EditText>(R.id.etWeight).setText("3.25")
                    findViewById<EditText>(R.id.etHeight).setText("51.5")
                }
                before = requireNotNull(viewModel(activity).getDraft(target.key()))
            }
            scenario.recreate()
            awaitActivity(scenario) { viewModel(it).getDraft(target.key())?.draftId == before.draftId && fragment(it).view?.isShown == true }
            scenario.onActivity { activity ->
                val restored = requireNotNull(viewModel(activity).getDraft(target.key()))
                assertEquals(before, restored)
                assertEquals("AuditProfileDraft", fragment(activity).requireView().findViewById<EditText>(R.id.etName).text.toString())
                activity.onBackPressedDispatcher.onBackPressed()
            }
            onView(withId(com.zero.components.R.id.tv_content)).check(matches(withText(com.zero.common.R.string.unsaved_record_tip)))
            onView(withId(com.zero.components.R.id.tv_cancel)).perform(click())
            val editTarget = NavTarget.BabyInfo.edit(existingId, NavTarget.Settings())
            scenario.onActivity { mainVm(it).navigateTo(editTarget) }
            awaitActivity(scenario) { viewModel(it).getDraft(editTarget.key()) != null }
            scenario.onActivity { activity ->
                val current = requireNotNull(viewModel(activity).getDraft(editTarget.key()))
                assertNotEquals(before.draftId, current.draftId)
                assertEquals(mainVm(activity).getBabyById(existingId)?.stableId, current.stableId)
                assertEquals(mainVm(activity).getBabyById(existingId)?.name, current.form.name)
            }
        } finally {
            scenario.onActivity { mainVm(it).navigateTo(NavTarget.Dashboard) }
            scenario.close()
        }
    }

    private suspend fun session(operations: BabyInfoSaveOperations, values: Map<String, Any?> = emptyMap()): Session =
        withContext(Dispatchers.Main) {
            val handle = SavedStateHandle(values)
            val vm = UpdateInfoViewModel(handle, operations)
            Session(vm, handle, ViewModelStore().apply { put("test", vm) }).also(sessions::add)
        }

    private fun draft(session: Session, target: NavTarget.BabyInfo, name: String): BabyInfoDraft {
        session.vm.saveDraft(target.key(), BabyInfoFormSnapshot(name = name), BabyInfoFormSnapshot(), null)
        return requireNotNull(session.vm.getDraft(target.key()))
    }

    private suspend fun finished(session: Session): BabyInfoSaveState.Finished = withTimeout(10_000) {
        session.vm.saveState.filterIsInstance<BabyInfoSaveState.Finished>().first()
    }

    @Suppress("DEPRECATION")
    private suspend fun snapshot(session: Session): Map<String, Any?> = withContext(Dispatchers.Main) {
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(Bundle().apply {
                session.handle.keys().forEach { putSerializable(it, session.handle.get<Serializable>(it)) }
            })
            parcel.setDataPosition(0)
            val restored = requireNotNull(parcel.readBundle(BabyInfoDraft::class.java.classLoader))
            restored.keySet().associateWith { restored.getSerializable(it) }
        } finally { parcel.recycle() }
    }

    private fun operations(repository: BabyRepository) = object : BabyInfoSaveOperations {
        override suspend fun create(baby: BabyInfo) = repository.insertBabyInfoOnce(baby)
        override suspend fun update(baby: BabyInfo) = repository.updateBabyInfoIfPresent(baby)
    }

    private fun unusedOperations() = object : BabyInfoSaveOperations {
        override suspend fun create(baby: BabyInfo): Int = error("此用例不得写入资料")
        override suspend fun update(baby: BabyInfo): Boolean = error("此用例不得写入资料")
    }

    private fun withDatabase(block: suspend (BabyRepository) -> Unit) {
        val database = Room.inMemoryDatabaseBuilder(instrumentation.targetContext, BabyDatabase::class.java).build()
        try { runBlocking { block(BabyRepository(database)) } } finally { database.close() }
    }

    private fun fragment(activity: MainActivity) = activity.supportFragmentManager.fragments.filterIsInstance<UpdateInfoFragment>().single()
    private fun viewModel(activity: MainActivity) = ViewModelProvider(fragment(activity))[UpdateInfoViewModel::class.java]
    private fun mainVm(activity: MainActivity) = ViewModelProvider(activity)[MainViewModel::class.java]

    private fun awaitActivity(scenario: ActivityScenario<MainActivity>, predicate: (MainActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000L
        do {
            instrumentation.waitForIdleSync()
            var ready = false
            scenario.onActivity { ready = predicate(it) }
            if (ready) return
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("宝宝资料页未恢复预期状态")
    }

    private data class Session(val vm: UpdateInfoViewModel, val handle: SavedStateHandle, val store: ViewModelStore)
}
