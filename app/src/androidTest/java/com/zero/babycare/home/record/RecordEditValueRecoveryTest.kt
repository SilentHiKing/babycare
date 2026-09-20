package com.zero.babycare.home.record

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.os.Parcel
import android.os.SystemClock
import android.view.View
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.zero.babycare.MainActivity
import com.zero.babycare.MainViewModel
import com.zero.babycare.R
import com.zero.babycare.home.record.event.EventRecordFragment
import com.zero.babycare.home.record.event.EventRecordViewModel
import com.zero.babycare.home.record.event.EventCategory
import com.zero.babycare.home.record.event.EventSubtype
import com.zero.babycare.home.record.event.MedicineUnitText
import com.zero.babycare.navigation.NavTarget
import com.zero.babydata.entity.BabyInfo
import com.zero.babydata.entity.EventExtraData
import com.zero.babydata.entity.EventRecord
import com.zero.babydata.entity.EventType
import com.zero.babydata.entity.FeedingRecord
import com.zero.babydata.entity.FeedingType
import com.zero.babydata.entity.GrowthData
import com.zero.babydata.entity.MedicineData
import com.zero.babydata.room.BabyDatabase
import com.zero.babydata.room.BabyRepository
import com.zero.common.mmkv.MMKVKeys
import com.zero.common.mmkv.MMKVStore
import com.zero.common.util.UnitConfig
import com.zero.components.widget.RecordTimerPanelView
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 仅在 AVD 运行；真实 Fragment/VM 写入隔离内存库，不向用户数据库插入或更新记录。 */
@RunWith(AndroidJUnit4::class)
class RecordEditValueRecoveryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun feedingEditPreservesActiveDurationAndOriginalAmountAfterUnitChangeAndRecreation() = withScreen { scenario, repository, babyId ->
        val record = FeedingRecord(feedingId = RECORD_ID, babyId = babyId, feedingType = FeedingType.FORMULA.type,
            feedingStart = START, feedingEnd = START + 1_800_000L, feedingDuration = 600_000L, feedingAmount = 100)
        runBlocking { repository.insertFeedingRecord(record) }
        MMKVStore.put(MMKVKeys.SETTINGS_FEEDING_UNIT, UnitConfig.FEEDING_UNIT_OZ)
        val target = NavTarget.FeedingRecord(record.feedingId, NavTarget.Dashboard)
        navigate(scenario, target)
        await(scenario) { currentView(it, target)?.findViewById<EditText>(R.id.etFeedingAmount)?.text?.toString() == "3.4" }
        scenario.onActivity { activity ->
            val root = requireNotNull(currentView(activity, target))
            assertEquals(600_000L, root.findViewById<RecordTimerPanelView>(R.id.timerPanel).timerView.getDuration())
            root.findViewById<EditText>(R.id.etNote).setText("合成喂养编辑")
            val restored = parcelDraft(currentFragment(activity, target))
            val amount = Gson().fromJson(restored.getString("amount_input"), RecordNumberInput::class.java)
            assertEquals(100, amount.feedingAmount("3.4"))
        }
        MMKVStore.put(MMKVKeys.SETTINGS_FEEDING_UNIT, UnitConfig.FEEDING_UNIT_ML)
        scenario.recreate()
        await(scenario) { currentView(it, target)?.findViewById<EditText>(R.id.etNote)?.text?.toString() == "合成喂养编辑" }
        scenario.onActivity { activity ->
            val root = requireNotNull(currentView(activity, target))
            assertEquals(activity.getString(com.zero.common.R.string.unit_oz_abbr), root.findViewById<TextView>(R.id.tvFeedingAmountUnit).text.toString())
            assertEquals(600_000L, root.findViewById<RecordTimerPanelView>(R.id.timerPanel).timerView.getDuration())
            assertTrue(root.findViewById<View>(R.id.tvSave).performClick())
        }
        onView(withId(com.zero.components.R.id.tv_confirm)).perform(click())
        await(scenario) { mainVm(it).navTarget.value == NavTarget.Dashboard }
        val saved = runBlocking { requireNotNull(repository.getFeedingRecordById(record.feedingId)) }
        assertEquals(100, saved.feedingAmount)
        assertEquals(600_000L, saved.feedingDuration)
        assertEquals("合成喂养编辑", saved.note)
    }

    @Test
    fun feedingNoteEditsPreserveUnexposedFieldsBeforeAndAfterRecreation() = withScreen { scenario, repository, babyId ->
        MMKVStore.put(MMKVKeys.SETTINGS_FEEDING_UNIT, UnitConfig.FEEDING_UNIT_ML)
        val expected = mutableListOf<FeedingRecord>()
        val actual = mutableListOf<FeedingRecord>()
        listOf(false, true).forEachIndexed { index, recreate ->
            // 合法旧库/备份字段没有对应表单控件；只改备注必须保留其值、身份和创建时间。
            val record = FeedingRecord(
                feedingId = RECORD_ID + index, babyId = babyId, feedingType = FeedingType.FORMULA.type,
                feedingStart = START, feedingEnd = START + 600_000L, feedingDuration = 600_000L,
                feedingAmount = 120, babyMood = FeedingRecord.MOOD_HUNGRY_CRYING,
                feedingLocation = FeedingRecord.LOCATION_OUTSIDE, note = "合成原备注", createdAt = START + 900_000L
            )
            runBlocking { repository.insertFeedingRecord(record) }
            val target = NavTarget.FeedingRecord(record.feedingId, NavTarget.Dashboard)
            navigate(scenario, target)
            await(scenario) { currentView(it, target)?.findViewById<EditText>(R.id.etNote)?.text?.toString() == record.note }
            val changedNote = if (recreate) "重建后只改备注" else "直接只改备注"
            scenario.onActivity { activity ->
                requireNotNull(currentView(activity, target)).findViewById<EditText>(R.id.etNote).setText(changedNote)
            }
            if (recreate) {
                scenario.recreate()
                await(scenario) { currentView(it, target)?.findViewById<EditText>(R.id.etNote)?.text?.toString() == changedNote }
            }
            scenario.onActivity { activity ->
                assertTrue(requireNotNull(currentView(activity, target)).findViewById<View>(R.id.tvSave).performClick())
            }
            onView(withId(com.zero.components.R.id.tv_confirm)).perform(click())
            await(scenario) { mainVm(it).navTarget.value == NavTarget.Dashboard }
            expected += record.copy(note = changedNote)
            actual += runBlocking { requireNotNull(repository.getFeedingRecordById(record.feedingId)) }
        }
        // 两条都经真实页面提交后比较完整实体，避免只覆盖直接保存而遗漏重建路径。
        assertEquals(expected, actual)
    }

    @Test
    fun growthEditsKeepOriginalPrecisionAndStoredUnitsAcrossFreshViews() = withScreen { scenario, repository, babyId ->
        val examples = listOf(EventType.GROWTH_WEIGHT to GrowthData(7.25, "kg"), EventType.GROWTH_HEIGHT to GrowthData(512.3, "mm"))
        examples.forEachIndexed { index, (type, original) ->
            MMKVStore.put(MMKVKeys.SETTINGS_WEIGHT_UNIT, UnitConfig.WEIGHT_UNIT_LB)
            MMKVStore.put(MMKVKeys.SETTINGS_HEIGHT_UNIT, UnitConfig.HEIGHT_UNIT_IN)
            val record = EventRecord(eventId = RECORD_ID + index, babyId = babyId, type = type, time = START, extraData = original.toJson())
            runBlocking { repository.insertEventRecord(record) }
            val target = NavTarget.EventRecord(editRecordId = record.eventId, returnTarget = NavTarget.Dashboard)
            navigate(scenario, target)
            await(scenario) { !currentView(it, target)?.findViewById<EditText>(R.id.etValue)?.text.isNullOrBlank() }
            scenario.onActivity { activity ->
                requireNotNull(currentView(activity, target)).findViewById<EditText>(R.id.etNote).setText("合成成长编辑")
                val restored = parcelDraft(currentFragment(activity, target))
                val input = Gson().fromJson(restored.getString("numeric_input"), RecordNumberInput::class.java)
                assertEquals(original, input.growth(requireNotNull(input.originalText)))
            }
            MMKVStore.put(MMKVKeys.SETTINGS_WEIGHT_UNIT, UnitConfig.WEIGHT_UNIT_KG)
            MMKVStore.put(MMKVKeys.SETTINGS_HEIGHT_UNIT, UnitConfig.HEIGHT_UNIT_CM)
            scenario.recreate()
            await(scenario) { currentView(it, target)?.findViewById<EditText>(R.id.etNote)?.text?.toString() == "合成成长编辑" }
            scenario.onActivity { activity ->
                val root = requireNotNull(currentView(activity, target))
                val label = if (type == EventType.GROWTH_WEIGHT) com.zero.common.R.string.unit_lb_abbr else com.zero.common.R.string.unit_in_abbr
                assertEquals(activity.getString(label), root.findViewById<TextView>(R.id.tvUnit).text.toString())
                assertTrue(root.findViewById<View>(R.id.btnSave).performClick())
            }
            await(scenario) { mainVm(it).navTarget.value == NavTarget.Dashboard }
            val saved = runBlocking { requireNotNull(repository.getEventRecordById(record.eventId)) }
            assertEquals(original, EventExtraData.parse(saved.type, saved.extraData))
            assertEquals("合成成长编辑", saved.note)
        }
    }

    @Test
    fun clearingActivityTimesCannotSaveOldViewModelTimestamps() = withScreen { scenario, repository, babyId ->
        val record = EventRecord(eventId = RECORD_ID, babyId = babyId, type = EventType.ACTIVITY_OUTDOOR,
            time = START, endTime = START + 600_000L, note = "原活动记录")
        runBlocking { repository.insertEventRecord(record) }
        val target = NavTarget.EventRecord(editRecordId = record.eventId, returnTarget = NavTarget.Dashboard)
        navigate(scenario, target)
        await(scenario) { currentView(it, target)?.findViewById<RecordTimerPanelView>(R.id.timerPanel)?.startInput?.getTimestamp() == START }
        scenario.onActivity { activity ->
            val fragment = currentFragment(activity, target)
            val root = fragment.requireView()
            val panel = root.findViewById<RecordTimerPanelView>(R.id.timerPanel)
            panel.startInput.setText("")
            root.findViewById<View>(R.id.btnSave).performClick()
            assertNotNull(panel.startInput.error)
            assertEquals(RecordMutationController.State.Idle, ViewModelProvider(fragment)[EventRecordViewModel::class.java].mutation.state.value)
            panel.startInput.setTimestamp(START, triggerCallback = true)
            panel.endInput.setTimestamp(record.endTime, triggerCallback = true)
            panel.endInput.setText("")
            root.findViewById<View>(R.id.btnSave).performClick()
            assertNotNull(panel.endInput.error)
            assertEquals(RecordMutationController.State.Idle, ViewModelProvider(fragment)[EventRecordViewModel::class.java].mutation.state.value)
        }
        assertEquals(record, runBlocking { repository.getEventRecordById(record.eventId) })
    }

    @Test
    fun medicineEditsKeepLegacyAndUnknownUnitsAfterRecreation() = withScreen { scenario, repository, babyId ->
        listOf("片", "방울", "spray", null).forEachIndexed { index, unit ->
            val original = MedicineData("合成药品", "1", unit)
            val record = EventRecord(eventId = RECORD_ID + index, babyId = babyId, type = EventType.HEALTH_MEDICINE,
                time = START, extraData = original.toJson())
            runBlocking { repository.insertEventRecord(record) }
            val target = NavTarget.EventRecord(editRecordId = record.eventId, returnTarget = NavTarget.Dashboard)
            navigate(scenario, target)
            await(scenario) { currentView(it, target)?.findViewById<EditText>(R.id.etMedicineName)?.text?.toString() == original.name }
            scenario.onActivity { activity ->
                val root = requireNotNull(currentView(activity, target))
                val label = MedicineUnitText.label(unit, activity::getString) ?: activity.getString(com.zero.common.R.string.please_select)
                assertEquals(label, root.findViewById<Spinner>(R.id.spinnerUnit).selectedItem.toString())
                root.findViewById<EditText>(R.id.etNote).setText("合成用药编辑")
                assertEquals(unit, parcelDraft(currentFragment(activity, target)).getString("medicine_input_unit"))
            }
            scenario.recreate()
            await(scenario) { currentView(it, target)?.findViewById<EditText>(R.id.etNote)?.text?.toString() == "合成用药编辑" }
            scenario.onActivity { activity -> assertTrue(requireNotNull(currentView(activity, target)).findViewById<View>(R.id.btnSave).performClick()) }
            await(scenario) { mainVm(it).navTarget.value == NavTarget.Dashboard }
            val saved = runBlocking { requireNotNull(repository.getEventRecordById(record.eventId)) }
            assertEquals(original, EventExtraData.parse(saved.type, saved.extraData))
        }
    }

    @Test
    fun deepLegacyActivityExtraSurvivesNoteOnlySaveAndRecreation() = withScreen { scenario, repository, babyId ->
        // 模拟旧版本已落库内容；当前导入深度限制不应使旧记录仅改备注时崩溃或丢失详情。
        val raw = "{\"data\":{\"x\":" + "[".repeat(5_000) + "0" + "]".repeat(5_000) + "}}"
        val record = EventRecord(eventId = RECORD_ID, babyId = babyId, type = EventType.ACTIVITY_OUTDOOR,
            time = START, endTime = START + 600_000L, extraData = raw, note = "原活动记录")
        runBlocking { repository.insertEventRecord(record) }
        val target = NavTarget.EventRecord(editRecordId = record.eventId, returnTarget = NavTarget.Dashboard)
        listOf(false, true).forEach { recreate ->
            navigate(scenario, target)
            await(scenario) { currentView(it, target)?.findViewById<RecordTimerPanelView>(R.id.timerPanel)?.startInput?.getTimestamp() == START }
            val changedNote = if (recreate) "重建后仅改备注" else "首次仅改备注"
            scenario.onActivity { activity ->
                requireNotNull(currentView(activity, target)).findViewById<EditText>(R.id.etNote).setText(changedNote)
                if (recreate) {
                    removeExtraInputBaseline(currentFragment(activity, target))
                }
            }
            if (recreate) {
                scenario.recreate()
                await(scenario) { currentView(it, target)?.findViewById<EditText>(R.id.etNote)?.text?.toString() == changedNote }
            }
            scenario.onActivity { activity ->
                assertTrue(requireNotNull(currentView(activity, target)).findViewById<View>(R.id.btnSave).performClick())
            }
            await(scenario) { mainVm(it).navTarget.value == NavTarget.Dashboard }
            val saved = runBlocking { requireNotNull(repository.getEventRecordById(record.eventId)) }
            assertEquals(raw, saved.extraData)
            assertEquals(changedNote, saved.note)
            assertEquals(record.time, saved.time)
            assertEquals(record.endTime, saved.endTime)
        }

        // 改为另一种记录时，旧活动扩展不能跟随详情为空的新类型保存。
        navigate(scenario, target)
        await(scenario) { currentView(it, target)?.findViewById<RecordTimerPanelView>(R.id.timerPanel)?.startInput?.getTimestamp() == START }
        scenario.onActivity { activity ->
            val model = ViewModelProvider(currentFragment(activity, target))[EventRecordViewModel::class.java]
            model.selectCategory(EventCategory.OTHER)
            model.selectSubtype(requireNotNull(EventSubtype.fromType(EventType.OTHER_BURP)))
        }
        await(scenario) { currentView(it, target)?.findViewById<RecordTimerPanelView>(R.id.timerPanel) == null }
        scenario.onActivity { activity ->
            assertTrue(requireNotNull(currentView(activity, target)).findViewById<View>(R.id.btnSave).performClick())
        }
        await(scenario) { mainVm(it).navTarget.value == NavTarget.Dashboard }
        val changedType = runBlocking { requireNotNull(repository.getEventRecordById(record.eventId)) }
        assertEquals(EventType.OTHER_BURP, changedType.type)
        assertEquals("", changedType.extraData)
    }

    @Test
    fun unchangedGrowthExtraKeepsUnknownFieldsAndExactNumbersButEditedDetailIsSavedAfterRecreation() = withScreen { scenario, repository, babyId ->
        MMKVStore.put(MMKVKeys.SETTINGS_WEIGHT_UNIT, UnitConfig.WEIGHT_UNIT_KG)
        val raw = " { \"value\" : 7.2534567890123456789012345, \"unit\":\"kg\", \"vendor\":{\"revision\":123456789012345678901234567890} } "
        val record = EventRecord(eventId = RECORD_ID, babyId = babyId, type = EventType.GROWTH_WEIGHT,
            time = START, extraData = raw)
        runBlocking { repository.insertEventRecord(record) }
        val target = NavTarget.EventRecord(editRecordId = record.eventId, returnTarget = NavTarget.Dashboard)
        navigate(scenario, target)
        await(scenario) { !currentView(it, target)?.findViewById<EditText>(R.id.etValue)?.text.isNullOrBlank() }
        scenario.onActivity { activity ->
            requireNotNull(currentView(activity, target)).findViewById<EditText>(R.id.etNote).setText("仅改成长备注")
            removeExtraInputBaseline(currentFragment(activity, target))
        }
        scenario.recreate()
        await(scenario) { currentView(it, target)?.findViewById<EditText>(R.id.etNote)?.text?.toString() == "仅改成长备注" }
        scenario.onActivity { activity ->
            assertTrue(requireNotNull(currentView(activity, target)).findViewById<View>(R.id.btnSave).performClick())
        }
        await(scenario) { mainVm(it).navTarget.value == NavTarget.Dashboard }
        val unchanged = runBlocking { requireNotNull(repository.getEventRecordById(record.eventId)) }
        assertEquals(raw, unchanged.extraData)
        assertEquals("仅改成长备注", unchanged.note)

        // 用户改过详情再重建，不能把改后的 UI 误作原始基线并写回旧数值。
        navigate(scenario, target)
        await(scenario) { !currentView(it, target)?.findViewById<EditText>(R.id.etValue)?.text.isNullOrBlank() }
        scenario.onActivity { activity ->
            requireNotNull(currentView(activity, target)).findViewById<EditText>(R.id.etValue).setText("8.5")
        }
        scenario.recreate()
        await(scenario) { currentView(it, target)?.findViewById<EditText>(R.id.etValue)?.text?.toString() == "8.5" }
        scenario.onActivity { activity ->
            assertTrue(requireNotNull(currentView(activity, target)).findViewById<View>(R.id.btnSave).performClick())
        }
        await(scenario) { mainVm(it).navTarget.value == NavTarget.Dashboard }
        val changed = runBlocking { requireNotNull(repository.getEventRecordById(record.eventId)) }
        assertEquals(GrowthData(8.5, "kg"), EventExtraData.parse(changed.type, changed.extraData))
    }

    private fun withScreen(block: (ActivityScenario<MainActivity>, BabyRepository, Int) -> Unit) {
        assumeTrue("隔离数据库测试仅在 AVD 运行，避免影响真机提醒与单位配置", Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        val units = mapOf(MMKVKeys.SETTINGS_FEEDING_UNIT to UnitConfig.getFeedingUnit(),
            MMKVKeys.SETTINGS_WEIGHT_UNIT to UnitConfig.getWeightUnit(), MMKVKeys.SETTINGS_HEIGHT_UNIT to UnitConfig.getHeightUnit())
        val database = Room.inMemoryDatabaseBuilder(app, BabyDatabase::class.java).build()
        val repository = BabyRepository(database)
        val injection = RecordInjection(repository)
        app.registerActivityLifecycleCallbacks(injection)
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            val current = ActivityScenario.launch(MainActivity::class.java)
            scenario = current
            await(current) { mainVm(it).babyDataLoaded.value }
            var babyId: Int? = null
            current.onActivity { babyId = mainVm(it).getCurrentBabyInfo()?.babyId }
            assumeTrue("AVD 需已有用于导航的合成宝宝", babyId != null)
            runBlocking { repository.insertBabyInfo(BabyInfo(babyId = requireNotNull(babyId), name = "隔离表单测试")) }
            block(current, repository, requireNotNull(babyId))
        } finally {
            scenario?.onActivity { mainVm(it).navigateTo(NavTarget.Dashboard) }
            scenario?.close()
            app.unregisterActivityLifecycleCallbacks(injection)
            units.forEach { (key, value) -> MMKVStore.put(key, value) }
            database.close()
        }
    }

    /** 正常 ViewModelProvider 注入页面依赖；配置重建沿用 VM，页面输入仍须由 Bundle 恢复。 */
    private class RecordInjection(private val repository: BabyRepository) : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            if (activity !is MainActivity) return
            activity.supportFragmentManager.registerFragmentLifecycleCallbacks(object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentPreCreated(fm: FragmentManager, f: Fragment, savedInstanceState: Bundle?) {
                    val modelClass = when (f) {
                        is FeedingRecordFragment -> FeedingRecordViewModel::class.java
                        is EventRecordFragment -> EventRecordViewModel::class.java
                        else -> return
                    }
                    ViewModelProvider(f, object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
                            FeedingRecordViewModel::class.java -> FeedingRecordViewModel(repository)
                            EventRecordViewModel::class.java -> EventRecordViewModel(repository)
                            else -> error("不支持的记录 VM")
                        } as T
                    })[modelClass]
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

    /** 模拟升级前 Bundle：保留原记录和输入，但没有本次新增的详情基线。 */
    private fun removeExtraInputBaseline(fragment: Fragment) {
        EventRecordFragment::class.java.getDeclaredField("originalExtraInputs").apply {
            isAccessible = true
        }.set(fragment, null)
        assertTrue(!parcelDraft(fragment).containsKey("original_extra_inputs"))
    }

    private fun parcelDraft(fragment: Fragment): Bundle {
        val state = Bundle().also(fragment::onSaveInstanceState)
        val parcel = Parcel.obtain()
        return try {
            parcel.writeBundle(state)
            parcel.setDataPosition(0)
            requireNotNull(parcel.readBundle(javaClass.classLoader)?.getBundle(RecordDraftState.KEY))
        } finally { parcel.recycle() }
    }

    private fun navigate(scenario: ActivityScenario<MainActivity>, target: NavTarget) {
        scenario.onActivity { mainVm(it).navigateTo(target) }
        await(scenario) { currentView(it, target)?.isShown == true }
    }
    private fun mainVm(activity: MainActivity) = ViewModelProvider(activity)[MainViewModel::class.java]
    private fun currentFragment(activity: MainActivity, target: NavTarget) = activity.supportFragmentManager.fragments.first { target.fragmentClass.isInstance(it) && !it.isHidden }
    private fun currentView(activity: MainActivity, target: NavTarget): View? = activity.supportFragmentManager.fragments.firstOrNull { target.fragmentClass.isInstance(it) && !it.isHidden }?.view
    private fun await(scenario: ActivityScenario<MainActivity>, condition: (MainActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000L
        do {
            instrumentation.waitForIdleSync()
            var ready = false
            scenario.onActivity { ready = condition(it) }
            if (ready) return
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("编辑表单未达到预期状态")
    }
    companion object {
        private const val RECORD_ID = 2_000_000_001
        private const val START = 1_577_836_800_000L
    }
}
