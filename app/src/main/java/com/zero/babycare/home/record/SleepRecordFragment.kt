package com.zero.babycare.home.record

import android.app.TimePickerDialog
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import com.blankj.utilcode.util.LogUtils
import com.blankj.utilcode.util.StringUtils
import com.blankj.utilcode.util.ToastUtils
import com.zero.babycare.MainViewModel
import com.zero.babycare.databinding.FragmentSleepRecordBinding
import com.zero.babycare.home.OngoingRecordManager
import com.zero.babycare.navigation.NavTarget
import com.zero.babydata.entity.SleepRecord
import com.zero.common.R
import com.zero.common.util.DateUtils
import com.zero.components.base.BaseFragment
import com.zero.components.base.util.DialogHelper
import com.zero.components.widget.RecordView.RecordState
import java.util.Calendar

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
class SleepRecordFragment : BaseFragment<FragmentSleepRecordBinding>() {

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

    // 记录暂停时的结束时间戳（用于检测是否被手动修改）
    private var pausedEndTimestamp = 0L

    override fun initView(view: View, savedInstanceState: Bundle?) {
        super.initView(view, savedInstanceState)
        binding.btn.title = StringUtils.getString(R.string.sleep_record)

        setupBackPressHandler()
        setupTimeInputs()
        setupTimerCounter()
        setupToolbar()
        setupTimePickerButtons()
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
        binding.rvCounter.release()
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
        binding.etStartTime.setTimestamp(startTime)
        updateEndTimeReference()
        
        // 计算已经过的时间，恢复计时器状态
        val elapsed = System.currentTimeMillis() - startTime
        binding.rvCounter.startFromOffset(elapsed)
        
        hasUnsavedChanges = true
        isProgrammaticChange = false
    }

    /**
     * 设置返回按钮处理
     */
    private fun setupBackPressHandler() {
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    handleBack()
                }
            }
        )
    }

    /**
     * 设置时间输入框
     */
    private fun setupTimeInputs() {
        // ==================== 入睡时间监听 ====================
        binding.etStartTime.setOnTimeEnteredListener { _, _ ->
            if (isProgrammaticChange) return@setOnTimeEnteredListener
            // 入睡时间变化时：清空醒来时间、重置计时器
            onStartTimeChanged()
        }

        // ==================== 醒来时间监听 ====================
        binding.etEndTime.setOnTimeEnteredListener { _, _ ->
            if (isProgrammaticChange) return@setOnTimeEnteredListener
            // 醒来时间手动输入后：暂停计时器，计算并显示时长
            onEndTimeManuallyEntered()
        }
    }

    /**
     * 入睡时间变化时的处理
     */
    private fun onStartTimeChanged() {
        LogUtils.d("onStartTimeChanged: ${binding.etStartTime.getTimestamp()}")
        
        // 清空醒来时间
        binding.etEndTime.clear()
        pausedEndTimestamp = 0L
        
        // 重置计时器
        binding.rvCounter.reset()
        
        // 更新醒来时间的参考时间戳（用于跨天判断）
        updateEndTimeReference()
        
        markAsUnsaved()
    }

    /**
     * 醒来时间手动输入后的处理
     */
    private fun onEndTimeManuallyEntered() {
        LogUtils.d("onEndTimeManuallyEntered: ${binding.etEndTime.getTimestamp()}")
        
        // 暂停计时器
        if (binding.rvCounter.currentShowState == RecordState.RECORDING) {
            binding.rvCounter.forcePause()
        }
        
        markAsUnsaved()

        // 即时校验时间范围并更新时长显示
        if (binding.etStartTime.hasValidTime() && binding.etEndTime.hasValidTime()) {
            val startTime = binding.etStartTime.getTimestamp()
            val endTime = binding.etEndTime.getTimestamp()
            
            if (!DateUtils.isEndAfterStart(startTime, endTime)) {
                // 即时提示错误
                ToastUtils.showShort(R.string.sleep_end_must_after_start)
            } else {
                // 更新时长显示（在计时器内）
                updateDurationFromTimeRange()
            }
        }
    }

    /**
     * 更新醒来时间的参考时间戳（用于智能跨天判断）
     */
    private fun updateEndTimeReference() {
        if (binding.etStartTime.hasValidTime()) {
            binding.etEndTime.setReferenceTimestamp(binding.etStartTime.getTimestamp())
        } else {
            binding.etEndTime.clearReferenceTimestamp()
        }
    }

    /**
     * 设置计时器状态变化监听
     */
    private fun setupTimerCounter() {
        binding.rvCounter.statusChange = { current, next ->
            LogUtils.d("Timer state change: $current -> $next")
            
            when {
                // 从初始状态开始计时
                current == RecordState.INIT && next == RecordState.RECORDING -> {
                    handleTimerStart()
                }
                
                // 从暂停状态继续计时
                current == RecordState.PAUSE && next == RecordState.RECORDING -> {
                    // 检查醒来时间是否被手动修改过
                    if (isEndTimeManuallyModified()) {
                        // 显示确认对话框，用户确认后才真正继续计时
                        showResumeTimerConfirmDialog()
                    } else {
                        // 直接继续计时
                        handleTimerResume()
                        binding.rvCounter.resumeFromPause()
                    }
                }
                
                // 暂停计时
                next == RecordState.PAUSE -> {
                    handleTimerPause()
                }
            }
        }
    }

    /**
     * 检查醒来时间是否被手动修改过
     */
    private fun isEndTimeManuallyModified(): Boolean {
        if (pausedEndTimestamp == 0L) return false
        val currentEndTime = binding.etEndTime.getTimestamp()
        // 如果当前醒来时间与暂停时记录的不同，说明被修改过
        return currentEndTime != pausedEndTimestamp && currentEndTime > 0
    }

    /**
     * 显示继续计时确认对话框
     */
    private fun showResumeTimerConfirmDialog() {
        DialogHelper.showConfirmDialog(
            context = requireContext(),
            title = StringUtils.getString(R.string.continue_timing),
            content = StringUtils.getString(R.string.end_time_will_be_cleared),
            confirmText = StringUtils.getString(R.string.confirm),
            cancelText = StringUtils.getString(R.string.cancel),
            onConfirm = {
                // 用户确认继续计时
                handleTimerResume()
                binding.rvCounter.resumeFromPause()
            }
        )
    }

    /**
     * 处理计时器开始（从 INIT 状态）
     */
    private fun handleTimerStart() {
        isProgrammaticChange = true
        
        // 如果入睡时间已填写，计算从那个时间到现在的偏移量
        if (binding.etStartTime.hasValidTime()) {
            val startTimestamp = binding.etStartTime.getTimestamp()
            val offset = System.currentTimeMillis() - startTimestamp
            if (offset > 0) {
                binding.rvCounter.setPauseOffset(offset)
            }
        }
        
        // 清空醒来时间
        binding.etEndTime.clear()
        pausedEndTimestamp = 0L
        
        // 延迟更新入睡时间显示（等待计时器启动后）
        binding.rvCounter.post {
            val timerStartTime = binding.rvCounter.getStartTimestamp()
            if (timerStartTime > 0) {
                binding.etStartTime.setTimestamp(timerStartTime)
                // 同步更新醒来时间的参考时间戳
                updateEndTimeReference()
                
                // 记录进行中状态到 MMKV
                mainVm.getCurrentBabyInfo()?.babyId?.let { babyId ->
                    OngoingRecordManager.startSleep(babyId)
                }
            }
            isProgrammaticChange = false
        }
        
        markAsUnsaved()
    }

    /**
     * 处理计时器继续（从 PAUSE 状态）
     */
    private fun handleTimerResume() {
        isProgrammaticChange = true
        
        // 清空醒来时间
        binding.etEndTime.clear()
        pausedEndTimestamp = 0L
        
        isProgrammaticChange = false
        markAsUnsaved()
    }

    /**
     * 处理计时器暂停
     */
    private fun handleTimerPause() {
        isProgrammaticChange = true
        
        // 自动填入醒来时间为当前时间
        val currentTime = System.currentTimeMillis()
        binding.etEndTime.setTimestamp(currentTime)
        // 记录暂停时的醒来时间，用于检测是否被修改
        pausedEndTimestamp = currentTime
        
        isProgrammaticChange = false
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
     * 设置时间选择器按钮
     */
    private fun setupTimePickerButtons() {
        // 入睡时间选择器
        binding.ivStartTime.setOnClickListener {
            showTimePickerWithDefault(binding.etStartTime.getTimestamp()) { hour, minute ->
                val timestamp = calculateSmartTimestampForStartTime(hour, minute)
                
                isProgrammaticChange = true
                binding.etStartTime.setTimestamp(timestamp)
                isProgrammaticChange = false
                
                // 手动触发入睡时间变化处理
                onStartTimeChanged()
            }
        }

        // 醒来时间选择器
        binding.ivEndTime.setOnClickListener {
            // 先更新参考时间
            updateEndTimeReference()
            
            showTimePickerWithDefault(binding.etEndTime.getTimestamp()) { hour, minute ->
                val timestamp = calculateSmartTimestampForEndTime(hour, minute)
                
                isProgrammaticChange = true
                binding.etEndTime.setTimestamp(timestamp)
                isProgrammaticChange = false
                
                // 手动触发醒来时间变化处理
                onEndTimeManuallyEntered()
            }
        }
    }

    /**
     * 计算入睡时间的智能时间戳
     */
    private fun calculateSmartTimestampForStartTime(hour: Int, minute: Int): Long {
        val now = Calendar.getInstance()
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        
        // 如果选择的时间是未来时间，设为昨天
        if (calendar.timeInMillis > now.timeInMillis) {
            calendar.add(Calendar.DAY_OF_MONTH, -1)
        }
        
        return calendar.timeInMillis
    }

    /**
     * 计算醒来时间的智能时间戳
     */
    private fun calculateSmartTimestampForEndTime(hour: Int, minute: Int): Long {
        val now = Calendar.getInstance()
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // 如果有入睡时间，基于入睡时间判断
        if (binding.etStartTime.hasValidTime()) {
            val startTimestamp = binding.etStartTime.getTimestamp()
            val startCalendar = Calendar.getInstance().apply { timeInMillis = startTimestamp }
            
            val startHour = startCalendar.get(Calendar.HOUR_OF_DAY)
            val startMinute = startCalendar.get(Calendar.MINUTE)
            
            // 设置醒来时间为入睡时间的同一天
            calendar.set(Calendar.YEAR, startCalendar.get(Calendar.YEAR))
            calendar.set(Calendar.MONTH, startCalendar.get(Calendar.MONTH))
            calendar.set(Calendar.DAY_OF_MONTH, startCalendar.get(Calendar.DAY_OF_MONTH))
            
            // 如果醒来时间的时分小于入睡时间，说明跨天了
            if (hour < startHour || (hour == startHour && minute < startMinute)) {
                calendar.add(Calendar.DAY_OF_MONTH, 1)
                // 但不能超过当前时间
                if (calendar.timeInMillis > now.timeInMillis) {
                    calendar.add(Calendar.DAY_OF_MONTH, -1)
                }
            }
        }
        
        // 最终检查：不能超过当前时间
        if (calendar.timeInMillis > now.timeInMillis) {
            calendar.add(Calendar.DAY_OF_MONTH, -1)
        }
        
        return calendar.timeInMillis
    }

    /**
     * 显示时间选择器（带默认值）
     */
    private fun showTimePickerWithDefault(
        defaultTimestamp: Long,
        onTimeSelected: (hour: Int, minute: Int) -> Unit
    ) {
        val calendar = if (defaultTimestamp > 0) {
            Calendar.getInstance().apply { timeInMillis = defaultTimestamp }
        } else {
            Calendar.getInstance()
        }
        
        TimePickerDialog(
            requireContext(),
            { _, hourOfDay, minute ->
                onTimeSelected(hourOfDay, minute)
            },
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            true
        ).show()
    }

    /**
     * 设置保存按钮
     */
    private fun setupSaveButton() {
        binding.tvSave.setOnClickListener {
            saveSleepRecord()
        }
    }

    /**
     * 从时间范围计算并更新时长显示（在计时器内显示）
     */
    private fun updateDurationFromTimeRange() {
        val startTime = binding.etStartTime.getTimestamp()
        val endTime = binding.etEndTime.getTimestamp()
        val duration = DateUtils.calculateDuration(startTime, endTime)
        binding.rvCounter.showDurationWithoutTimer(duration)
    }

    private fun resolveEditMode() {
        val navTarget = mainVm.navTarget.value as? NavTarget.SleepRecord
        editRecordId = navTarget?.editRecordId
        isEditMode = editRecordId != null
        if (!isEditMode) {
            editingRecord = null
        }
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

        binding.etStartTime.setTimestamp(record.sleepStart)
        updateEndTimeReference()
        binding.etEndTime.setTimestamp(record.sleepEnd)
        updateDurationFromTimeRange()
        binding.etNote.setText(record.note)

        hasUnsavedChanges = false
        isProgrammaticChange = false
    }

    /**
     * 保存睡眠记录
     */
    private fun saveSleepRecord() {
        // 如果正在计时，先暂停
        if (binding.rvCounter.currentShowState == RecordState.RECORDING) {
            binding.rvCounter.forcePause()
            // 更新醒来时间
            binding.etEndTime.setTimestamp(System.currentTimeMillis())
        }

        val babyId = mainVm.getCurrentBabyInfo()?.babyId
        if (babyId == null) {
            ToastUtils.showShort(R.string.no_baby_selected)
            return
        }

        val startTime = binding.etStartTime.getTimestamp()
        val endTime = binding.etEndTime.getTimestamp()

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

        val sleepRecord = SleepRecord(
            sleepId = editingRecord?.sleepId ?: 0,
            babyId = babyId,
            sleepStart = startTime,
            sleepEnd = endTime,
            sleepDuration = endTime - startTime,
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
        
        // 清空时间输入
        binding.etStartTime.clear()
        binding.etEndTime.clear()
        binding.etEndTime.clearReferenceTimestamp()
        pausedEndTimestamp = 0L
        
        // 重置计时器
        binding.rvCounter.reset()
        
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
