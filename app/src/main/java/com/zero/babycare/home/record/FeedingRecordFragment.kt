package com.zero.babycare.home.record

import android.app.AlertDialog
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
import com.zero.babycare.databinding.FragmentFeedingRecordBinding
import com.zero.babycare.home.DashboardFragment
import com.zero.babydata.entity.FeedingRecord
import com.zero.common.R
import com.zero.common.ext.launchInLifecycle
import com.zero.common.util.DateUtils
import com.zero.common.util.DateUtils.timestampToMMddHHmm
import com.zero.components.base.BaseFragment
import com.zero.components.base.vm.UiState
import com.zero.components.widget.RecordView.RecordState
import java.util.Calendar

class FeedingRecordFragment : BaseFragment<FragmentFeedingRecordBinding>() {
    companion object {
        // 喂养类型常量
        const val FEEDING_TYPE_BREAST = 0
        const val FEEDING_TYPE_FORMULA = 1
        const val FEEDING_TYPE_MIXED = 2

        // 最大喂奶时长警告阈值（分钟）
        const val MAX_FEEDING_DURATION_WARNING_MINUTES = 120

        fun create(): FeedingRecordFragment {
            return FeedingRecordFragment()
        }
    }

    private val vm by viewModels<FeedingRecordViewModel>()
    private val mainVm: MainViewModel by lazy {
        activityViewModels<MainViewModel>().value
    }

    // 当前选择的喂养类型
    private var selectedFeedingType = FEEDING_TYPE_BREAST

    // 是否有未保存的记录（用于退出提示）
    private var hasUnsavedChanges = false

    override fun initView(view: View, savedInstanceState: Bundle?) {
        super.initView(view, savedInstanceState)
        binding.btn.title = StringUtils.getString(R.string.feeding)

        setupBackPressHandler()
        setupFeedingTypeSelector()
        setupTimeInputs()
        setupTimerCounter()
        setupFinishButton()
        setupTimePickerButtons()
    }

    /**
     * 设置返回键处理（退出提示）
     */
    private fun setupBackPressHandler() {
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (hasUnsavedChanges) {
                        showExitConfirmDialog()
                    } else {
                        isEnabled = false
                        requireActivity().onBackPressed()
                    }
                }
            }
        )
    }

    /**
     * 设置喂养类型选择器
     */
    private fun setupFeedingTypeSelector() {
        binding.rbBreast.isChecked = true
        updateBreastDurationVisibility()

        binding.rgFeedingType.setOnCheckedChangeListener { _, checkedId ->
            selectedFeedingType = when (checkedId) {
                com.zero.babycare.R.id.rbBreast -> FEEDING_TYPE_BREAST
                com.zero.babycare.R.id.rbFormula -> FEEDING_TYPE_FORMULA
                com.zero.babycare.R.id.rbMixed -> FEEDING_TYPE_MIXED
                else -> FEEDING_TYPE_BREAST
            }
            updateBreastDurationVisibility()
            markAsUnsaved()
        }
    }

    /**
     * 更新左右乳房时长输入框的可见性
     */
    private fun updateBreastDurationVisibility() {
        binding.llBreastDuration.visibility = if (selectedFeedingType == FEEDING_TYPE_BREAST) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    /**
     * 设置时间输入监听
     */
    private fun setupTimeInputs() {
        // 开始时间输入监听
        binding.etStartTime.setOnTimeEnteredListener { _, _ ->
            // 开始时间变化时，清空结束时间并重置计时器
            binding.etEndTime.setText("")
            binding.rvCounter.reset()
            markAsUnsaved()

            // 校验开始时间
            validateStartTime()
        }

        binding.etStartTime.setOnTimestampChangedListener { timestamp ->
            // 如果结束时间已填写，重新校验时间合理性
            if (binding.etEndTime.hasValidTime()) {
                validateTimeRange()
            }
        }

        // 结束时间输入监听
        binding.etEndTime.setOnTimeEnteredListener { _, _ ->
            // 结束时间手动输入后，暂停计时器并显示计算出的时长
            binding.rvCounter.forcePause()
            markAsUnsaved()

            // 校验并更新时长显示
            if (validateTimeRange()) {
                updateDurationFromTimeRange()
            }
        }

        binding.etEndTime.setOnTimestampChangedListener { _ ->
            if (binding.etStartTime.hasValidTime() && binding.etEndTime.hasValidTime()) {
                if (validateTimeRange()) {
                    updateDurationFromTimeRange()
                }
            }
        }
    }

    /**
     * 设置计时器状态变化监听
     */
    private fun setupTimerCounter() {
        binding.rvCounter.statusChange = { current, next ->
            if (current == RecordState.INIT) {
                // 如果开始时间已填写，计算偏移量
                if (binding.etStartTime.hasValidTime()) {
                    val offset = System.currentTimeMillis() - binding.etStartTime.getTimestamp()
                    if (offset > 0) {
                        binding.rvCounter.setPauseOffset(offset)
                    }
                }
                // 更新开始时间显示
                binding.rvCounter.post {
                    binding.etStartTime.setTimestamp(binding.rvCounter.getStartTimestamp())
                }
                markAsUnsaved()
            }

            if (next == RecordState.RECORDING) {
                // 开始计时时清空结束时间
                binding.etEndTime.setText("")
            }

            if (next == RecordState.PAUSE) {
                // 暂停时自动填入结束时间
                binding.etEndTime.setTimestamp(System.currentTimeMillis())
            }
        }
    }

    /**
     * 设置时间选择器按钮
     */
    private fun setupTimePickerButtons() {
        binding.ivStartTime.setOnClickListener {
            showTimePicker { hour, minute ->
                val calendar = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, hour)
                    set(Calendar.MINUTE, minute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                // 如果选择的时间是未来时间，设为昨天
                if (calendar.timeInMillis > System.currentTimeMillis()) {
                    calendar.add(Calendar.DAY_OF_MONTH, -1)
                }
                binding.etStartTime.setTimestamp(calendar.timeInMillis)
                binding.etEndTime.setText("")
                binding.rvCounter.reset()
                markAsUnsaved()
            }
        }

        binding.ivEndTime.setOnClickListener {
            showTimePicker { hour, minute ->
                val calendar = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, hour)
                    set(Calendar.MINUTE, minute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                // 如果选择的时间是未来时间，设为昨天
                if (calendar.timeInMillis > System.currentTimeMillis()) {
                    calendar.add(Calendar.DAY_OF_MONTH, -1)
                }
                binding.etEndTime.setTimestamp(calendar.timeInMillis)
                binding.rvCounter.forcePause()

                if (validateTimeRange()) {
                    updateDurationFromTimeRange()
                }
                markAsUnsaved()
            }
        }
    }

    /**
     * 显示时间选择器
     */
    private fun showTimePicker(onTimeSelected: (hour: Int, minute: Int) -> Unit) {
        val calendar = Calendar.getInstance()
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
     * 设置完成按钮
     */
    private fun setupFinishButton() {
        binding.btn.setOnFinishListener {
            saveRecord()
        }
    }

    /**
     * 校验开始时间
     */
    private fun validateStartTime(): Boolean {
        if (!binding.etStartTime.hasValidTime()) {
            return false
        }

        if (binding.etStartTime.isFutureTime()) {
            ToastUtils.showShort(R.string.time_cannot_be_future)
            return false
        }

        return true
    }

    /**
     * 校验结束时间
     */
    private fun validateEndTime(): Boolean {
        if (!binding.etEndTime.hasValidTime()) {
            return false
        }

        if (binding.etEndTime.isFutureTime()) {
            ToastUtils.showShort(R.string.time_cannot_be_future)
            return false
        }

        return true
    }

    /**
     * 校验时间范围（结束时间必须晚于开始时间）
     */
    private fun validateTimeRange(): Boolean {
        if (!binding.etStartTime.hasValidTime() || !binding.etEndTime.hasValidTime()) {
            return false
        }

        val startTime = binding.etStartTime.getTimestamp()
        val endTime = binding.etEndTime.getTimestamp()

        if (!DateUtils.isEndAfterStart(startTime, endTime)) {
            ToastUtils.showShort(R.string.end_time_must_after_start)
            return false
        }

        return true
    }

    /**
     * 从时间范围计算并更新时长显示
     */
    private fun updateDurationFromTimeRange() {
        val startTime = binding.etStartTime.getTimestamp()
        val endTime = binding.etEndTime.getTimestamp()
        val duration = DateUtils.calculateDuration(startTime, endTime)
        binding.rvCounter.showDurationWithoutTimer(duration)
    }

    /**
     * 保存记录
     */
    private fun saveRecord() {
        // 如果正在计时，先暂停
        if (binding.rvCounter.currentShowState == RecordState.RECORDING) {
            binding.rvCounter.performClick()
        }

        // 校验必填项
        if (!binding.etStartTime.hasValidTime()) {
            ToastUtils.showShort(R.string.start_time_required)
            return
        }

        if (!binding.etEndTime.hasValidTime()) {
            ToastUtils.showShort(R.string.end_time_required)
            return
        }

        // 校验时间合理性
        if (!validateStartTime() || !validateEndTime()) {
            return
        }

        if (!validateTimeRange()) {
            return
        }

        // 校验 babyId
        val babyId = mainVm.getCurrentBabyInfo()?.babyId
        if (babyId == null || babyId < 0) {
            ToastUtils.showShort(R.string.no_baby_selected)
            return
        }

        val startTime = binding.etStartTime.getTimestamp()
        val endTime = binding.etEndTime.getTimestamp()

        // 计算时长：优先使用计时器时长，如果没有则使用时间差
        var duration = binding.rvCounter.getDuration()
        val timeRangeDuration = DateUtils.calculateDuration(startTime, endTime)

        // 如果计时器时长为0或超过时间范围，使用时间范围计算的时长
        if (duration <= 0 || duration > timeRangeDuration) {
            duration = timeRangeDuration
        }

        // 检查时长是否超过警告阈值
        if (DateUtils.isDurationExceedMinutes(duration, MAX_FEEDING_DURATION_WARNING_MINUTES)) {
            showDurationWarningDialog(babyId, startTime, endTime, duration)
            return
        }

        // 显示确认对话框
        showSaveConfirmDialog(babyId, startTime, endTime, duration)
    }

    /**
     * 显示时长过长警告对话框
     */
    private fun showDurationWarningDialog(
        babyId: Int,
        startTime: Long,
        endTime: Long,
        duration: Long
    ) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.confirm_save)
            .setMessage(R.string.feeding_duration_too_long)
            .setPositiveButton(R.string.confirm) { _, _ ->
                showSaveConfirmDialog(babyId, startTime, endTime, duration)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * 显示保存确认对话框
     */
    private fun showSaveConfirmDialog(
        babyId: Int,
        startTime: Long,
        endTime: Long,
        duration: Long
    ) {
        val durationMinutes = DateUtils.millisecondsToSmartMinutes(duration)
        val startTimeStr = timestampToMMddHHmm(startTime)
        val endTimeStr = timestampToMMddHHmm(endTime)

        val message = StringUtils.getString(
            R.string.feeding_summary,
            durationMinutes,
            startTimeStr,
            endTimeStr
        )

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.confirm_save)
            .setMessage(message)
            .setPositiveButton(R.string.confirm) { _, _ ->
                doSaveRecord(babyId, startTime, endTime, duration)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * 执行保存记录
     */
    private fun doSaveRecord(
        babyId: Int,
        startTime: Long,
        endTime: Long,
        duration: Long
    ) {
        // 获取左右乳房时长（仅母乳喂养时）
        val leftBreastDuration = if (selectedFeedingType == FEEDING_TYPE_BREAST) {
            binding.etLeftBreastDuration.text.toString().toLongOrNull()?.let {
                DateUtils.minutesToMilliseconds(it)
            } ?: 0L
        } else 0L

        val rightBreastDuration = if (selectedFeedingType == FEEDING_TYPE_BREAST) {
            binding.etRightBreastDuration.text.toString().toLongOrNull()?.let {
                DateUtils.minutesToMilliseconds(it)
            } ?: 0L
        } else 0L

        val feedingRecord = FeedingRecord().apply {
            this.babyId = babyId
            this.feedingType = selectedFeedingType
            this.feedingStart = startTime
            this.feedingEnd = endTime
            this.feedingDuration = duration
            this.feedingDurationBreastLeft = leftBreastDuration
            this.feedingDurationBreastRight = rightBreastDuration
            this.note = binding.etNote.text.toString().trim()
            this.createdAt = System.currentTimeMillis()
        }

        vm.insert(feedingRecord) {
            LogUtils.d("feedingRecord success")
            hasUnsavedChanges = false
            mainVm.switchFragment(DashboardFragment::class.java)
        }
    }

    /**
     * 显示退出确认对话框
     */
    private fun showExitConfirmDialog() {
        AlertDialog.Builder(requireContext())
            .setMessage(R.string.unsaved_record_tip)
            .setPositiveButton(R.string.confirm) { _, _ ->
                hasUnsavedChanges = false
                mainVm.switchFragment(DashboardFragment::class.java)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * 标记为有未保存的更改
     */
    private fun markAsUnsaved() {
        hasUnsavedChanges = true
    }

    override fun initData(view: View, savedInstanceState: Bundle?) {
        super.initData(view, savedInstanceState)

        // 加载上次喂奶记录
        mainVm.getCurrentBabyInfo()?.babyId?.let { babyId ->
            vm.loadLastFeedingRecord(babyId)
        }

        // 观察上次喂奶记录
        launchInLifecycle {
            vm.lastFeedingRecord.observe(viewLifecycleOwner) { lastRecord ->
                lastRecord?.let {
                    val lastTimeStr = timestampToMMddHHmm(it.feedingEnd)
                    binding.tvLastFeeding.text = StringUtils.getString(
                        R.string.last_feeding_time,
                        lastTimeStr
                    )
                    binding.tvLastFeeding.visibility = View.VISIBLE
                }
            }
        }

        // 观察 UI 状态
        launchInLifecycle {
            vm.uiState.collect { state ->
                when (state) {
                    is UiState.Success -> {
                        // 处理成功状态
                    }
                    else -> {
                        // 处理其他状态
                    }
                }
            }
        }
    }
}
