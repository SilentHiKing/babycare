package com.zero.babycare.babyinfo

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.os.Parcel
import android.os.SystemClock
import android.util.Base64
import android.widget.EditText
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.recyclerview.widget.RecyclerView
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
import com.zero.common.mmkv.MMKVKeys
import com.zero.common.mmkv.MMKVStore
import com.zero.common.theme.ThemeManager
import com.lxj.xpopup.core.BasePopupView
import com.lxj.xpopup.enums.PopupStatus
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Serializable
import java.io.ByteArrayInputStream
import java.io.ObjectInputStream
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone
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

    @Test
    fun pickedBirthdayKeepsItsInstantAfterTimezoneChangeAndRecreation() {
        assumeTrue("资料与进程时区隔离回归仅在 AVD 运行", Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        val app = instrumentation.targetContext.applicationContext as Application
        val originalZone = TimeZone.getDefault()
        val originalSelection = MMKVStore.getInt(MMKVKeys.CURRENT_BABY_ID)
        val database = Room.inMemoryDatabaseBuilder(app, BabyDatabase::class.java).build()
        val repository = BabyRepository(database)
        val sourceZone = TimeZone.getTimeZone("Asia/Shanghai")
        val picked = LocalDateTime.of(2020, 1, 2, 12, 34).atZone(ZoneId.of(sourceZone.id)).toInstant().toEpochMilli()
        val unchanged = picked - 86_400_000L + 12_345L
        val baby = BabyInfo(babyId = 2_000_000_111, name = "隔离生日",
            gender = if (ThemeManager.getSavedTheme() == ThemeManager.BabyTheme.GIRL) "girl" else "boy",
            birthDate = unchanged)
        runBlocking { repository.insertBabyInfo(baby) }
        val callbacks = BirthdayInjection(repository, operations(repository))
        app.registerActivityLifecycleCallbacks(callbacks)
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            TimeZone.setDefault(sourceZone)
            assertLegacyBirthdaySnapshotCompatibility(picked, unchanged)
            val current = ActivityScenario.launch(MainActivity::class.java).also { scenario = it }
            awaitActivity(current) { mainVm(it).babyDataLoaded.value && mainVm(it).getBabyById(baby.babyId) != null }
            val actual = mutableListOf<Long>()
            // 分别覆盖新建、主动选择新生日和未改生日；不改生日的原秒/毫秒也不能被显示精度抹掉。
            listOf("create", "edit", "unchanged").forEach { mode ->
                TimeZone.setDefault(sourceZone)
                val destination = if (mode == "create") NavTarget.BabyInfo.create(NavTarget.Dashboard)
                    else NavTarget.BabyInfo.edit(baby.babyId, NavTarget.Dashboard)
                if (mode == "unchanged") runBlocking {
                    repository.updateBabyInfoIfPresent(requireNotNull(repository.getBabyInfo(baby.babyId)).copy(birthDate = unchanged))
                }
                awaitActivity(current) { mode != "unchanged" || mainVm(it).getBabyById(baby.babyId)?.birthDate == unchanged }
                current.onActivity { mainVm(it).navigateTo(destination) }
                awaitActivity(current) { fragment(it).view?.isShown == true && viewModel(it).getDraft(destination.key()) != null }
                current.onActivity { activity ->
                    val root = fragment(activity).requireView()
                    root.findViewById<EditText>(R.id.etName).setText("隔离生日-$mode")
                    if (mode != "unchanged") {
                        // 只为真实选择器提供确定初值；确认仍必须经过生产弹窗回调，不能直接调用 VM 写时间。
                        root.findViewById<EditText>(R.id.etBirthday).setText("2020-01-02 12:34")
                        assertTrue(root.findViewById<android.view.View>(R.id.rowBirthday).performClick())
                    }
                }
                if (mode != "unchanged") {
                    awaitActivity(current) { birthdayPopup(fragment(it))?.popupStatus == PopupStatus.Show }
                    current.onActivity { activity ->
                        assertTrue(requireNotNull(birthdayPopup(fragment(activity)))
                            .findViewById<android.view.View>(com.zero.components.R.id.tv_confirm).performClick())
                    }
                    awaitActivity(current) { birthdayPopup(fragment(it))?.popupStatus == PopupStatus.Dismiss }
                }
                // 等效于后台系统时区变化后的进程默认值；不更改设备系统设置，并在 finally 恢复。
                TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
                current.recreate()
                awaitActivity(current) { fragment(it).view?.isShown == true &&
                    fragment(it).requireView().findViewById<EditText>(R.id.etName).text.toString() == "隔离生日-$mode" }
                awaitActivity(current) { fragment(it).requireView().findViewById<RecyclerView>(com.zero.components.R.id.rvActions)
                    .findViewHolderForAdapterPosition(0) != null }
                current.onActivity { activity ->
                    val actions = fragment(activity).requireView().findViewById<RecyclerView>(com.zero.components.R.id.rvActions)
                    assertTrue(requireNotNull(actions.findViewHolderForAdapterPosition(0)).itemView.performClick())
                }
                awaitActivity(current) { mainVm(it).navTarget.value == NavTarget.Dashboard }
                actual += runBlocking { repository.getAllBabyInfo().single { it.name == "隔离生日-$mode" }.birthDate }
            }
            assertEquals("选择时的时间戳必须跨时区恢复，未修改的历史时间保持全部精度", listOf(picked, picked, unchanged), actual)
        } finally {
            TimeZone.setDefault(originalZone)
            scenario?.onActivity { mainVm(it).navigateTo(NavTarget.Dashboard) }
            scenario?.close()
            app.unregisterActivityLifecycleCallbacks(callbacks)
            if (originalSelection > 0) MMKVStore.put(MMKVKeys.CURRENT_BABY_ID, originalSelection)
                else MMKVStore.remove(MMKVKeys.CURRENT_BABY_ID)
            database.close()
        }
    }

    /** 该六字段流由修改前的已编译类生成；不能用新类现写现读代替真实版本兼容验证。 */
    private fun assertLegacyBirthdaySnapshotCompatibility(picked: Long, unchanged: Long) = runBlocking {
        val encoded = "rO0ABXNyAC9jb20uemVyby5iYWJ5Y2FyZS5iYWJ5aW5mby5CYWJ5SW5mb0Zvcm1TbmFwc2hvdBpYWEN+AH2TAgAGTAAIYmlydGhkYXl0ABJMamF2YS9sYW5nL1N0cmluZztMAAlibG9vZFR5cGVxAH4AAUwACmdlbmRlckNvZGVxAH4AAUwABmhlaWdodHEAfgABTAAEbmFtZXEAfgABTAAGd2VpZ2h0cQB+AAF4cHQAEDIwMjAtMDEtMDIgMTI6MzR0AAFBdAADYm95dAAENTEuNXQADkxlZ2FjeUJpcnRoZGF5dAAEMy4yNQ=="
        val legacy = ObjectInputStream(ByteArrayInputStream(Base64.decode(encoded, Base64.DEFAULT))).use {
            it.readObject() as BabyInfoFormSnapshot
        }
        assertEquals(BabyInfoFormSnapshot("LegacyBirthday", "2020-01-02 12:34", "boy", "3.25", "51.5", "A"), legacy)
        assertNull(legacy.birthdayTimestamp)
        val original = session(unusedOperations())
        withContext(Dispatchers.Main) {
            original.vm.saveDraft(target.key(), legacy, legacy, null)
        }
        val restored = session(unusedOperations(), snapshot(original))
        withContext(Dispatchers.Main) {
            val form = requireNotNull(restored.vm.getDraft(target.key())).form
            assertEquals(legacy, form)
            // 旧流未改字段保留实体的秒/毫秒；旧创建没有原时区，只兼容原有当前时区文本解析。
            assertEquals(unchanged, restored.vm.resolveBirthdayTimestamp(form, form, unchanged))
            assertEquals(picked, restored.vm.resolveBirthdayTimestamp(form, BabyInfoFormSnapshot(), null))
        }
    }

    /** 页面读写均注入同一个独立 Room；没有往应用业务库写入合成宝宝或资料。 */
    private class BirthdayInjection(
        private val repository: BabyRepository,
        private val operations: BabyInfoSaveOperations
    ) : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            if (activity !is MainActivity) return
            ViewModelProvider(activity, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    MainViewModel(SavedStateHandle(), repository.observeAllBabyInfo()) as T
            })[MainViewModel::class.java]
            activity.supportFragmentManager.registerFragmentLifecycleCallbacks(object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentPreCreated(fm: FragmentManager, f: Fragment, savedInstanceState: Bundle?) {
                    if (f !is UpdateInfoFragment) return
                    ViewModelProvider(f, object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T =
                            UpdateInfoViewModel(SavedStateHandle(), operations) as T
                    })[UpdateInfoViewModel::class.java]
                }
            }, false)
        }
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    private fun birthdayPopup(fragment: UpdateInfoFragment): BasePopupView? =
        UpdateInfoFragment::class.java.getDeclaredField("formDialog").apply { isAccessible = true }.get(fragment) as? BasePopupView

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
