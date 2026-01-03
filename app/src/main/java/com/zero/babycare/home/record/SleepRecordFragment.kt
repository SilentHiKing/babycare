package com.zero.babycare.home.record

import android.os.Bundle
import android.view.View
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import com.blankj.utilcode.util.StringUtils
import com.blankj.utilcode.util.ToastUtils
import com.zero.babycare.MainViewModel
import com.zero.babycare.databinding.FragmentSleepRecordBinding
import com.zero.babycare.home.OngoingRecordManager
import com.zero.babycare.navigation.NavTarget
import com.zero.babycare.navigation.BackPressHandler
import com.zero.babydata.entity.SleepRecord
import com.zero.common.R
import com.zero.common.util.DateUtils
import com.zero.components.base.BaseFragment
import com.zero.components.base.util.DialogHelper
import com.zero.components.widget.RecordView.RecordState

/**
 * 睡眠记录页面
 * 
 * ## 核心组件交互逻辑：
 * 
 * ### etStartTime（入睡时间输入框）
 * - 用户可以手动输入4位数字（如2330），自动格式化为 MM-dd HH:mm:ss
 * - 点击图标可弹出时间选择器
 * - 入睡时间变化时：清空醒来时间、重置计时器
 * 
 * ### etEndTime（醒来时间输入框）
 * - 用户可以手动输入4位数字
 * - 智能跨天：如果入睡时间是23:50，输入0630会自动识别为第二天06:30
 * - 醒来时间输入后：暂停计时器、即时校验时间范围
 * 
 * ### rvCounter（计时器）
 * - 三种状态：INIT（初始）→ RECORDING（计时中）→ PAUSE（暂停）
 * - 点击开始：自动填入入睡时间，开始计时
 * - 点击暂停：自动填入醒来时间
 * - 继续计时：如果醒来时间被手动修改过，会提示用户确认
 * - 支持暂停后继续，累计总时长
 * 
 * ### 交互流程：
 * 1. 方式一（使用计时器）：点击计时器开始 → 自动填入入睡时间 → 再次点击暂停 → 自动填入醒来时间
 * 2. 方式二（手动输入）：输入入睡时间 → 输入醒来时间 → 根据时间差显示时长
 * 3. 混合方式：手动输入入睡时间 → 点击计时器开始 → 从该时间点开始计时
 */
class SleepRecordFragment : BaseFragment<FragmentSleepRecordBinding>(), BackPressHandler {

    companion object {
        fun create(): SleepRecordFragment {
            return SleepRecordFragment()
        }
    }

    private val vm by viewModels<SleepRecordViewModel>()
    private val mainVm: MainViewModel by lazy {
        activityViewModels<MainViewModel>().value
    }

    private var editRecordId: Int? = null
    private var editingRecord: SleepRecord? = null
    private var isEditMode = false

    // 是否有未保存的记录
    private var hasUnsavedChanges = false

    // 标记是否是程序设置时间（避免循环触发）
    private var isProgrammaticChange = false

    private lateinit var timerController: RecordTimerController

    override fun initView(view: View, savedInstanceState: Bundle?) {
        super.initView(view, savedInstanceState)
        binding.btn.title = StringUtils.getString(R.string.sleep_record)

        setupTimerController()
        setupToolbar()
        setupSaveButton()

        // 初始化时重置页面数据
        resolveEditMode()
        resetPage()
        if (isEditMode) {
            loadEditRecord()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.timerPanel.timerView.release()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        // View 未创建时不执行
        if (view == null) return
        
        if (hidden) {
            // 隐藏时不重置计时器（保留进行中状态）
        } else {
            resolveEditMode()
            if (isEditMode) {
                loadEditRecord()
                return
            }
            // 检查是否有进行中的睡眠记录
            val babyId = mainVm.getCurrentBabyInfo()?.babyId
            if (babyId != null && OngoingRecordManager.isSleeping(babyId)) {
                // 恢复进行中的记录
                restoreOngoingSleep(babyId)
            } else {
                resetPage()
            }
            loadLastSleepRecord()
        }
    }

    /**
     * 恢复进行中的睡眠记录
     */
    private fun restoreOngoingSleep(babyId: Int) {
        val startTime = OngoingRecordManager.getOngoingSleepStart(babyId) ?: return
        
        isProgrammaticChange = true
        
        // 设置入睡时间
        timerController.syncStartTime(startTime, clearEnd = true, resetTimer = false, notify = false)
        
        // 计算已经过的时间，恢复计时器状态
        val elapsed = System.currentTimeMillis() - startTime
        binding.timerPanel.timerView.startFromOffset(elapsed)
        
        hasUnsavedChanges = true
        isProgrammaticChange = false
    }

    private fun setupTimerController() {
        timerController = RecordTimerController(
            context = requireContext(),
            timerView = binding.timerPanel.timerView,
            startInput = binding.timerPanel.startInput,
            endInput = binding.timerPanel.endInput,
            startPicker = binding.timerPanel.startPicker,
            endPicker = binding.timerPanel.endPicker,
            config = RecordTimerController.Config(
                invalidEndTimeMessageRes = R.string.sleep_end_must_after_start,
                shouldIgnoreInput = { isProgrammaticChange }
            ),
            callbacks = RecordTimerController.Callbacks(
                onStartTimeChanged = { startTime ->
                    markAsUnsaved()
                    val babyId = mainVm.getCurrentBabyInfo()?.babyId
                    if (!isEditMode && babyId != null && OngoingRecordManager.isSleeping(babyId)) {
                        // 同步进行中的开始时间，避免恢复与状态卡不一致
                        OngoingRecordManager.updateSleepStart(babyId, startTime)
                    }
                },
                onEndTimeChanged = { markAsUnsaved() },
                onTimerStart = {
                    mainVm.getCurrentBabyInfo()?.babyId?.let { babyId ->
                        OngoingRecordManager.startSleep(babyId)
                    }
                },
                onTimerResume = { markAsUnsaved() },
                onDirty = { markAsUnsaved() }
            )
        )
    }

    /**
     * 设置工具栏
     */
    private fun setupToolbar() {
        binding.btn.showBackButton {
            handleBack()
        }
    }

    /**
     * 设置保存按钮
     */
    private fun setupSaveButton() {
        binding.tvSave.setOnClickListener {
            saveSleepRecord()
        }
    }

    private fun resolveEditMode() {
        val navTarget = mainVm.navTarget.value as? NavTarget.SleepRecord
        editRecordId = navTarget?.editRecordId
        isEditMode = editRecordId != null
        if (!isEditMode) {
            editingRecord = null
        }
        applyTimerLockState()
    }

    private fun applyTimerLockState() {
        val returnTarget = (mainVm.navTarget.value as? NavTarget.SleepRecord)?.returnTarget
        val locked = isEditMode && returnTarget is NavTarget.Statistics
        binding.timerPanel.timerView.isEnabled = !locked
        binding.timerPanel.timerView.isClickable = !locked
        binding.timerPanel.timerView.alpha = if (locked) 0.4f else 1f
    }

    private fun loadEditRecord() {
        val recordId = editRecordId ?: return
        vm.loadSleepRecordById(recordId) { record ->
            if (record == null) {
                mainVm.navigateTo(getReturnTarget())
                return@loadSleepRecordById
            }
            editingRecord = record
            applyRecordToUi(record)
        }
    }

    private fun applyRecordToUi(record: SleepRecord) {
        isProgrammaticChange = true

        timerController.setStartTime(record.sleepStart, notify = false)
        timerController.setEndTime(record.sleepEnd, notify = false, updateDuration = true)
        binding.etNote.setText(record.note)

        hasUnsavedChanges = false
        isProgrammaticChange = false
    }

    /**
     * 保存睡眠记录
     */
    private fun saveSleepRecord() {
        // 如果正在计时，先暂停
        if (binding.timerPanel.timerView.currentShowState == RecordState.RECORDING) {
            binding.timerPanel.timerView.forcePause()
            // 更新醒来时间
            binding.timerPanel.endInput.setTimestamp(System.currentTimeMillis())
        }

        val babyId = mainVm.getCurrentBabyInfo()?.babyId
        if (babyId == null) {
            ToastUtils.showShort(R.string.no_baby_selected)
            return
        }

        val startTime = binding.timerPanel.startInput.getTimestamp()
        val endTime = binding.timerPanel.endInput.getTimestamp()

        // 校验
        if (startTime <= 0) {
            ToastUtils.showShort(R.string.sleep_start_required)
            return
        }
        if (endTime <= 0) {
            ToastUtils.showShort(R.string.sleep_end_required)
            return
        }
        if (endTime <= startTime) {
            ToastUtils.showShort(R.string.sleep_end_must_after_start)
            return
        }

        // 计算时长：优先使用计时器时长，如果没有则使用时间差
        var duration = binding.timerPanel.timerView.getDuration()
        val timeRangeDuration = DateUtils.calculateDuration(startTime, endTime)
        if (duration <= 0 || duration > timeRangeDuration) {
            duration = timeRangeDuration
        }

        val sleepRecord = SleepRecord(
            sleepId = editingRecord?.sleepId ?: 0,
            babyId = babyId,
            sleepStart = startTime,
            sleepEnd = endTime,
            sleepDuration = duration,
            note = binding.etNote.text.toString(),
            createdAt = editingRecord?.createdAt ?: System.currentTimeMillis()
        )

        val onSaved = {
            ToastUtils.showShort(R.string.sleep_save_success)
            if (!isEditMode) {
                // 清除进行中状态
                OngoingRecordManager.endSleep(babyId)
                view?.post { resetPage() }
            }
            hasUnsavedChanges = false
            mainVm.navigateTo(getReturnTarget())
        }

        if (isEditMode) {
            vm.update(sleepRecord, onSaved)
        } else {
            vm.insert(sleepRecord, onSaved)
        }
    }

    /**
     * 加载上次睡眠记录
     */
    private fun loadLastSleepRecord() {
        val babyId = mainVm.getCurrentBabyInfo()?.babyId ?: return
        vm.loadLastSleepRecord(babyId)
        vm.lastSleepRecord.observe(viewLifecycleOwner) { record ->
            if (record != null) {
                binding.tvLastSleep.text = StringUtils.getString(
                    R.string.last_sleep_time,
                    DateUtils.timestampToMMddHHmm(record.sleepEnd)
                )
                binding.tvLastSleep.visibility = View.VISIBLE
            } else {
                binding.tvLastSleep.visibility = View.GONE
            }
        }
    }

    /**
     * 重置页面数据
     */
    private fun resetPage() {
        isProgrammaticChange = true
        
        // 清空时间输入与计时器
        timerController.reset()
        
        // 清空备注
        binding.etNote.setText("")
        
        // 重置未保存标记
        hasUnsavedChanges = false
        
        isProgrammaticChange = false
    }

    /**
     * 标记为有未保存的更改
     */
    private fun markAsUnsaved() {
        hasUnsavedChanges = true
    }

    /**
     * 处理返回
     */
    override fun onSystemBackPressed(): Boolean {
        handleBack()
        return true
    }

    private fun handleBack() {
        if (hasUnsavedChanges) {
            DialogHelper.showConfirmDialog(
                context = requireContext(),
                title = StringUtils.getString(R.string.tip),
                content = StringUtils.getString(R.string.unsaved_record_tip),
                confirmText = StringUtils.getString(R.string.confirm),
                cancelText = StringUtils.getString(R.string.cancel),
                onConfirm = {
                    hasUnsavedChanges = false
                    if (!isEditMode) {
                        mainVm.getCurrentBabyInfo()?.babyId?.let { babyId ->
                            OngoingRecordManager.cancelSleep(babyId)
                        }
                    }
                    mainVm.navigateTo(getReturnTarget())
                }
            )
        } else {
            mainVm.navigateTo(getReturnTarget())
        }
    }

    private fun getReturnTarget(): NavTarget {
        return (mainVm.navTarget.value as? NavTarget.SleepRecord)?.returnTarget ?: NavTarget.Dashboard
    }
}
