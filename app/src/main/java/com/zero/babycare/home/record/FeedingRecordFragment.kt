package com.zero.babycare.home.record

import android.app.TimePickerDialog
import com.zero.components.base.util.DialogHelper
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
import com.zero.babycare.home.OngoingRecordManager
import com.zero.babycare.navigation.NavTarget
import com.zero.babydata.entity.FeedingRecord
import com.zero.babydata.entity.FeedingType
import com.zero.babydata.entity.SolidFoodType
import com.zero.common.R
import com.zero.common.ext.launchInLifecycle
import com.zero.common.util.DateUtils
import com.zero.common.util.DateUtils.timestampToMMddHHmm
import com.zero.components.base.BaseFragment
import com.zero.components.base.vm.UiState
import com.zero.components.widget.RecordView.RecordState
import java.util.Calendar

/**
 * 喂养记录页面
 * 
 * ## 核心组件交互逻辑：
 * 
 * ### etStartTime（开始时间输入框）
 * - 用户可以手动输入4位数字（如0830），自动格式化为 MM-dd HH:mm:ss
 * - 点击图标可弹出时间选择器（默认显示已填入的时间）
 * - 开始时间变化时：清空结束时间、重置计时器
 * 
 * ### etEndTime（结束时间输入框）
 * - 用户可以手动输入4位数字
 * - 智能跨天：如果开始时间是23:50，输入0030会自动识别为第二天00:30
 * - 结束时间输入后：暂停计时器、根据时间差显示时长、即时校验时间范围
 * 
 * ### rvCounter（计时器）
 * - 三种状态：INIT（初始）→ RECORDING（计时中）→ PAUSE（暂停）
 * - 点击开始：自动填入开始时间，开始计时
 * - 点击暂停：自动填入结束时间
 * - 继续计时：如果结束时间被手动修改过，会提示用户确认
 * - 支持暂停后继续，累计总时长
 * 
 * ### 交互流程：
 * 1. 方式一（使用计时器）：点击计时器开始 → 自动填入开始时间 → 再次点击暂停 → 自动填入结束时间
 * 2. 方式二（手动输入）：输入开始时间 → 输入结束时间 → 根据时间差显示时长
 * 3. 混合方式：手动输入开始时间 → 点击计时器开始 → 从该时间点开始计时
 */
class FeedingRecordFragment : BaseFragment<FragmentFeedingRecordBinding>() {
    companion object {
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

    private var editRecordId: Int? = null
    private var editingRecord: FeedingRecord? = null
    private var isEditMode = false

    // 当前选择的喂养类型
    private var selectedFeedingType = FeedingType.BREAST
    
    // 当前选择的辅食分类
    private var selectedSolidCategory: Int? = null
    
    // 当前选择的辅食子类型
    private var selectedSolidSubtype: Int? = null

    // 是否有未保存的记录（用于退出提示）
    private var hasUnsavedChanges = false

    // 标记是否是程序设置时间（避免循环触发）
    private var isProgrammaticChange = false

    // 记录暂停时的结束时间戳（用于检测是否被手动修改）
    private var pausedEndTimestamp = 0L

    override fun initView(view: View, savedInstanceState: Bundle?) {
        super.initView(view, savedInstanceState)
        binding.btn.title = StringUtils.getString(R.string.feeding)

        setupBackPressHandler()
        setupFeedingTypeSelector()
        setupTimeInputs()
        setupTimerCounter()
        setupToolbar()
        setupTimePickerButtons()
        setupSaveButton()
        setupFirstTimeCheckbox()
        
        // 初始化时重置页面数据
        resolveEditMode()
        resetPage()
        if (isEditMode) {
            loadEditRecord()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // 释放计时器资源
        binding.rvCounter.release()
    }

    /**
     * 设置返回键处理（退出提示）
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
     * 设置喂养类型选择器
     */
    private fun setupFeedingTypeSelector() {
        binding.rbBreast.isChecked = true
        updateFeedingTypeVisibility()

        binding.rgFeedingType.setOnCheckedChangeListener { _, checkedId ->
            selectedFeedingType = when (checkedId) {
                com.zero.babycare.R.id.rbBreast -> FeedingType.BREAST
                com.zero.babycare.R.id.rbFormula -> FeedingType.FORMULA
                com.zero.babycare.R.id.rbMixed -> FeedingType.MIXED
                com.zero.babycare.R.id.rbSolid -> FeedingType.SOLID_FOOD
                com.zero.babycare.R.id.rbOther -> FeedingType.OTHER
                else -> FeedingType.BREAST
            }
            updateFeedingTypeVisibility()
            markAsUnsaved()
        }
        
        // 设置辅食分类选择器
        setupSolidCategorySelector()
    }
    
    /**
     * 设置辅食分类选择器
     */
    private fun setupSolidCategorySelector() {
        binding.rgSolidCategory.setOnCheckedChangeListener { _, checkedId ->
            selectedSolidCategory = when (checkedId) {
                com.zero.babycare.R.id.rbSolidStaple -> SolidFoodType.CATEGORY_STAPLE
                com.zero.babycare.R.id.rbSolidVegetable -> SolidFoodType.CATEGORY_VEGETABLE
                com.zero.babycare.R.id.rbSolidFruit -> SolidFoodType.CATEGORY_FRUIT
                com.zero.babycare.R.id.rbSolidProtein -> SolidFoodType.CATEGORY_PROTEIN
                com.zero.babycare.R.id.rbSolidDairy -> SolidFoodType.CATEGORY_DAIRY
                com.zero.babycare.R.id.rbSolidDrink -> SolidFoodType.CATEGORY_DRINK
                com.zero.babycare.R.id.rbSolidSupplement -> SolidFoodType.CATEGORY_SUPPLEMENT
                com.zero.babycare.R.id.rbSolidSnack -> SolidFoodType.CATEGORY_SNACK
                com.zero.babycare.R.id.rbSolidOther -> SolidFoodType.CATEGORY_OTHER
                else -> null
            }
            updateSolidSubtypes()
            markAsUnsaved()
        }
    }
    
    /**
     * 更新辅食子类型选项
     */
    private fun updateSolidSubtypes() {
        val category = selectedSolidCategory
        if (category == null || category == SolidFoodType.CATEGORY_OTHER) {
            binding.hsvSolidSubtype.visibility = View.GONE
            binding.llSolidFoodName.visibility = View.VISIBLE
            selectedSolidSubtype = if (category == SolidFoodType.CATEGORY_OTHER) SolidFoodType.OTHER else null
            updateSolidUnit()
            updateAllergenTip()
            return
        }
        
        val subtypes = SolidFoodType.getSubtypes(category)
        if (subtypes.isEmpty()) {
            binding.hsvSolidSubtype.visibility = View.GONE
            binding.llSolidFoodName.visibility = View.VISIBLE
            return
        }
        
        // 动态创建子类型 RadioButton
        binding.rgSolidSubtype.removeAllViews()
        subtypes.forEach { (type, stringResId) ->
            val radioButton = android.widget.RadioButton(requireContext()).apply {
                id = View.generateViewId()
                tag = type
                text = StringUtils.getString(stringResId)
                layoutParams = android.widget.RadioGroup.LayoutParams(
                    android.widget.RadioGroup.LayoutParams.WRAP_CONTENT,
                    resources.getDimensionPixelSize(com.zero.common.R.dimen.dp_28)
                ).apply {
                    marginEnd = resources.getDimensionPixelSize(com.zero.common.R.dimen.dp_6)
                }
                setPadding(
                    resources.getDimensionPixelSize(com.zero.common.R.dimen.dp_10),
                    0,
                    resources.getDimensionPixelSize(com.zero.common.R.dimen.dp_10),
                    0
                )
                setBackgroundResource(com.zero.common.R.drawable.selector_solid_subtype_bg)
                buttonDrawable = null
                gravity = android.view.Gravity.CENTER
                setTextColor(resources.getColorStateList(com.zero.common.R.color.selector_radio_text_color, null))
                textSize = 12f
            }
            binding.rgSolidSubtype.addView(radioButton)
        }
        
        binding.rgSolidSubtype.setOnCheckedChangeListener { _, checkedId ->
            val selectedRadio = binding.rgSolidSubtype.findViewById<android.widget.RadioButton>(checkedId)
            selectedSolidSubtype = selectedRadio?.tag as? Int
            updateSolidFoodNameVisibility()
            updateSolidUnit()
            updateAllergenTip()
            markAsUnsaved()
        }
        
        binding.hsvSolidSubtype.visibility = View.VISIBLE
        // 默认选中第一个
        if (binding.rgSolidSubtype.childCount > 0) {
            (binding.rgSolidSubtype.getChildAt(0) as? android.widget.RadioButton)?.isChecked = true
        }
    }
    
    /**
     * 更新食物名称输入框可见性
     */
    private fun updateSolidFoodNameVisibility() {
        val subtype = selectedSolidSubtype
        binding.llSolidFoodName.visibility = if (subtype != null && SolidFoodType.requiresFoodName(subtype)) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }
    
    /**
     * 更新辅食单位
     */
    private fun updateSolidUnit() {
        val subtype = selectedSolidSubtype ?: return
        binding.tvSolidUnit.text = SolidFoodType.getDefaultUnit(subtype)
    }
    
    /**
     * 更新过敏风险提示
     */
    private fun updateAllergenTip() {
        val subtype = selectedSolidSubtype
        val isFirstTime = binding.cbFirstTime.isChecked
        binding.tvAllergenTip.visibility = if (subtype != null && isFirstTime && SolidFoodType.isAllergenRisk(subtype)) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }
    
    /**
     * 设置首次尝试勾选框监听
     */
    private fun setupFirstTimeCheckbox() {
        binding.cbFirstTime.setOnCheckedChangeListener { _, _ ->
            updateAllergenTip()
            markAsUnsaved()
        }
    }

    /**
     * 根据喂养类型更新所有相关区域的可见性
     */
    private fun updateFeedingTypeVisibility() {
        // 母乳 - 显示左右乳房时长
        binding.llBreastDuration.visibility = if (selectedFeedingType == FeedingType.BREAST) {
            View.VISIBLE
        } else {
            View.GONE
        }
        
        // 配方奶/混合 - 显示喂奶量
        binding.llFeedingAmount.visibility = when (selectedFeedingType) {
            FeedingType.FORMULA, FeedingType.MIXED -> View.VISIBLE
            else -> View.GONE
        }
        
        // 辅食 - 显示辅食选项区域
        binding.llSolidFood.visibility = if (selectedFeedingType == FeedingType.SOLID_FOOD) {
            View.VISIBLE
        } else {
            View.GONE
        }
        
        // 其他 - 显示自定义输入框
        binding.llOtherFood.visibility = if (selectedFeedingType == FeedingType.OTHER) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }
    
    /**
     * 更新左右乳房时长输入框的可见性
     */
    private fun updateBreastDurationVisibility() {
        binding.llBreastDuration.visibility = if (selectedFeedingType == FeedingType.BREAST) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }
    
    /**
     * 更新喂奶量输入框的可见性（配方奶和混合喂养时显示）
     */
    private fun updateFeedingAmountVisibility() {
        binding.llFeedingAmount.visibility = when (selectedFeedingType) {
            FeedingType.FORMULA, FeedingType.MIXED -> View.VISIBLE
            else -> View.GONE
        }
    }

    /**
     * 设置时间输入监听
     */
    private fun setupTimeInputs() {
        // ==================== 开始时间监听 ====================
        binding.etStartTime.setOnTimeEnteredListener { _, _ ->
            if (isProgrammaticChange) return@setOnTimeEnteredListener
            
            // 开始时间变化时：清空结束时间、重置计时器
            onStartTimeChanged()
        }

        // ==================== 结束时间监听 ====================
        binding.etEndTime.setOnTimeEnteredListener { _, _ ->
            if (isProgrammaticChange) return@setOnTimeEnteredListener
            
            // 结束时间手动输入后：暂停计时器，计算并显示时长
            onEndTimeManuallyEntered()
        }
    }

    /**
     * 开始时间变化时的处理
     */
    private fun onStartTimeChanged() {
        LogUtils.d("onStartTimeChanged: ${binding.etStartTime.getTimestamp()}")
        
        // 清空结束时间
        binding.etEndTime.clear()
        pausedEndTimestamp = 0L
        
        // 重置计时器
        binding.rvCounter.reset()
        
        // 更新结束时间的参考时间戳（用于跨天判断）
        updateEndTimeReference()
        
        markAsUnsaved()
    }

    /**
     * 结束时间手动输入后的处理
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
                ToastUtils.showShort(R.string.end_time_must_after_start)
            } else {
                // 更新时长显示
                updateDurationFromTimeRange()
            }
        }
    }

    /**
     * 更新结束时间的参考时间戳（用于智能跨天判断）
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
                    // 检查结束时间是否被手动修改过
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
     * 检查结束时间是否被手动修改过
     */
    private fun isEndTimeManuallyModified(): Boolean {
        if (pausedEndTimestamp == 0L) return false
        val currentEndTime = binding.etEndTime.getTimestamp()
        // 如果当前结束时间与暂停时记录的不同，说明被修改过
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
        
        // 如果开始时间已填写，计算从那个时间到现在的偏移量
        if (binding.etStartTime.hasValidTime()) {
            val startTimestamp = binding.etStartTime.getTimestamp()
            val offset = System.currentTimeMillis() - startTimestamp
            if (offset > 0) {
                binding.rvCounter.setPauseOffset(offset)
            }
        }
        
        // 清空结束时间
        binding.etEndTime.clear()
        pausedEndTimestamp = 0L
        
        // 延迟更新开始时间显示（等待计时器启动后）
        binding.rvCounter.post {
            val timerStartTime = binding.rvCounter.getStartTimestamp()
            if (timerStartTime > 0) {
                binding.etStartTime.setTimestamp(timerStartTime)
                // 同步更新结束时间的参考时间戳
                updateEndTimeReference()
                
                // 记录进行中状态到 MMKV
                mainVm.getCurrentBabyInfo()?.babyId?.let { babyId ->
                    OngoingRecordManager.startFeeding(babyId, selectedFeedingType.type)
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
        
        // 清空结束时间
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
        
        // 自动填入结束时间为当前时间
        val currentTime = System.currentTimeMillis()
        binding.etEndTime.setTimestamp(currentTime)
        // 记录暂停时的结束时间，用于检测是否被修改
        pausedEndTimestamp = currentTime
        
        isProgrammaticChange = false
    }

    /**
     * 设置时间选择器按钮
     */
    private fun setupTimePickerButtons() {
        // 开始时间选择器
        binding.ivStartTime.setOnClickListener {
            showTimePickerWithDefault(binding.etStartTime.getTimestamp()) { hour, minute ->
                val timestamp = calculateSmartTimestampForStartTime(hour, minute)
                
                isProgrammaticChange = true
                binding.etStartTime.setTimestamp(timestamp)
                isProgrammaticChange = false
                
                // 手动触发开始时间变化处理
                onStartTimeChanged()
            }
        }

        // 结束时间选择器
        binding.ivEndTime.setOnClickListener {
            // 先更新参考时间
            updateEndTimeReference()
            
            showTimePickerWithDefault(binding.etEndTime.getTimestamp()) { hour, minute ->
                val timestamp = calculateSmartTimestampForEndTime(hour, minute)
                
                isProgrammaticChange = true
                binding.etEndTime.setTimestamp(timestamp)
                isProgrammaticChange = false
                
                // 手动触发结束时间变化处理
                onEndTimeManuallyEntered()
            }
        }
    }

    /**
     * 计算开始时间的智能时间戳
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
     * 计算结束时间的智能时间戳
     */
    private fun calculateSmartTimestampForEndTime(hour: Int, minute: Int): Long {
        val now = Calendar.getInstance()
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // 如果有开始时间，基于开始时间判断
        if (binding.etStartTime.hasValidTime()) {
            val startTimestamp = binding.etStartTime.getTimestamp()
            val startCalendar = Calendar.getInstance().apply { timeInMillis = startTimestamp }
            
            val startHour = startCalendar.get(Calendar.HOUR_OF_DAY)
            val startMinute = startCalendar.get(Calendar.MINUTE)
            
            // 设置结束时间为开始时间的同一天
            calendar.set(Calendar.YEAR, startCalendar.get(Calendar.YEAR))
            calendar.set(Calendar.MONTH, startCalendar.get(Calendar.MONTH))
            calendar.set(Calendar.DAY_OF_MONTH, startCalendar.get(Calendar.DAY_OF_MONTH))
            
            // 如果结束时间的时分小于开始时间，说明跨天了
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
     * @param defaultTimestamp 默认显示的时间戳，如果为0则使用当前时间
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
            saveRecord()
        }
    }

    /**
     * 处理返回
     */
    private fun handleBack() {
        if (hasUnsavedChanges) {
            showExitConfirmDialog()
        } else {
            mainVm.navigateTo(getReturnTarget())
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
     * 校验左右乳房时长
     * @param totalDuration 总喂奶时长（毫秒）
     */
    private fun validateBreastDuration(totalDuration: Long): Boolean {
        if (selectedFeedingType != FeedingType.BREAST) {
            return true
        }

        val leftDuration = binding.etLeftBreastDuration.text.toString().toLongOrNull() ?: 0L
        val rightDuration = binding.etRightBreastDuration.text.toString().toLongOrNull() ?: 0L
        
        // 转换为毫秒
        val totalBreastDuration = DateUtils.minutesToMilliseconds(leftDuration + rightDuration)
        
        if (totalBreastDuration > totalDuration) {
            ToastUtils.showShort(R.string.breast_duration_exceed)
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

    private fun resolveEditMode() {
        val navTarget = mainVm.navTarget.value as? NavTarget.FeedingRecord
        editRecordId = navTarget?.editRecordId
        isEditMode = editRecordId != null
        if (!isEditMode) {
            editingRecord = null
        }
        applyTimerLockState()
    }

    private fun applyTimerLockState() {
        val returnTarget = (mainVm.navTarget.value as? NavTarget.FeedingRecord)?.returnTarget
        val locked = isEditMode && returnTarget is NavTarget.Statistics
        binding.rvCounter.isEnabled = !locked
        binding.rvCounter.isClickable = !locked
        binding.rvCounter.alpha = if (locked) 0.4f else 1f
    }

    private fun loadEditRecord() {
        val recordId = editRecordId ?: return
        vm.loadFeedingRecordById(recordId) { record ->
            if (record == null) {
                mainVm.navigateTo(getReturnTarget())
                return@loadFeedingRecordById
            }
            editingRecord = record
            applyRecordToUi(record)
        }
    }

    private fun applyRecordToUi(record: FeedingRecord) {
        isProgrammaticChange = true

        selectedFeedingType = FeedingType.fromType(record.feedingType)
        when (selectedFeedingType) {
            FeedingType.BREAST -> binding.rbBreast.isChecked = true
            FeedingType.FORMULA -> binding.rbFormula.isChecked = true
            FeedingType.MIXED -> binding.rbMixed.isChecked = true
            FeedingType.SOLID_FOOD -> binding.rbSolid.isChecked = true
            FeedingType.OTHER -> binding.rbOther.isChecked = true
        }
        updateFeedingTypeVisibility()

        binding.etStartTime.setTimestamp(record.feedingStart)
        updateEndTimeReference()
        if (record.feedingEnd > 0L) {
            binding.etEndTime.setTimestamp(record.feedingEnd)
        }
        updateDurationFromTimeRange()

        val leftMinutes = DateUtils.millisecondsToMinutes(record.feedingDurationBreastLeft)
        val rightMinutes = DateUtils.millisecondsToMinutes(record.feedingDurationBreastRight)
        binding.etLeftBreastDuration.setText(if (leftMinutes > 0) leftMinutes.toString() else "")
        binding.etRightBreastDuration.setText(if (rightMinutes > 0) rightMinutes.toString() else "")

        when (selectedFeedingType) {
            FeedingType.FORMULA, FeedingType.MIXED -> {
                binding.etFeedingAmount.setText(record.feedingAmount?.toString().orEmpty())
            }
            FeedingType.SOLID_FOOD -> {
                val solidType = record.solidFoodType
                if (solidType != null) {
                    selectedSolidCategory = if (solidType == SolidFoodType.OTHER) {
                        SolidFoodType.CATEGORY_OTHER
                    } else {
                        SolidFoodType.getCategory(solidType)
                    }
                    when (selectedSolidCategory) {
                        SolidFoodType.CATEGORY_STAPLE -> binding.rbSolidStaple.isChecked = true
                        SolidFoodType.CATEGORY_VEGETABLE -> binding.rbSolidVegetable.isChecked = true
                        SolidFoodType.CATEGORY_FRUIT -> binding.rbSolidFruit.isChecked = true
                        SolidFoodType.CATEGORY_PROTEIN -> binding.rbSolidProtein.isChecked = true
                        SolidFoodType.CATEGORY_DAIRY -> binding.rbSolidDairy.isChecked = true
                        SolidFoodType.CATEGORY_DRINK -> binding.rbSolidDrink.isChecked = true
                        SolidFoodType.CATEGORY_SUPPLEMENT -> binding.rbSolidSupplement.isChecked = true
                        SolidFoodType.CATEGORY_SNACK -> binding.rbSolidSnack.isChecked = true
                        SolidFoodType.CATEGORY_OTHER -> binding.rbSolidOther.isChecked = true
                    }
                    updateSolidSubtypes()
                    if (selectedSolidCategory != SolidFoodType.CATEGORY_OTHER) {
                        for (index in 0 until binding.rgSolidSubtype.childCount) {
                            val radio = binding.rgSolidSubtype.getChildAt(index) as? android.widget.RadioButton
                            if (radio?.tag == solidType) {
                                radio.isChecked = true
                                break
                            }
                        }
                    }
                    selectedSolidSubtype = solidType
                }

                binding.etSolidFoodName.setText(record.foodName.orEmpty())
                binding.etSolidAmount.setText(record.feedingAmount?.toString().orEmpty())
                binding.cbFirstTime.isChecked = record.isFirstTime
                updateSolidFoodNameVisibility()
                updateSolidUnit()
                updateAllergenTip()
            }
            FeedingType.OTHER -> {
                binding.etOtherFoodName.setText(record.foodName.orEmpty())
            }
            else -> Unit
        }

        binding.etNote.setText(record.note)

        hasUnsavedChanges = false
        isProgrammaticChange = false
    }

    /**
     * 保存记录
     */
    private fun saveRecord() {
        // 如果正在计时，先暂停
        if (binding.rvCounter.currentShowState == RecordState.RECORDING) {
            binding.rvCounter.forcePause()
            // 更新结束时间
            binding.etEndTime.setTimestamp(System.currentTimeMillis())
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

        // 校验左右乳房时长
        if (!validateBreastDuration(duration)) {
            return
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
        DialogHelper.showConfirmDialog(
            context = requireContext(),
            title = StringUtils.getString(R.string.confirm_save),
            content = StringUtils.getString(R.string.feeding_duration_too_long),
            confirmText = StringUtils.getString(R.string.confirm),
            cancelText = StringUtils.getString(R.string.cancel),
            onConfirm = { showSaveConfirmDialog(babyId, startTime, endTime, duration) }
        )
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

        DialogHelper.showConfirmDialog(
            context = requireContext(),
            title = StringUtils.getString(R.string.confirm_save),
            content = message,
            confirmText = StringUtils.getString(R.string.confirm),
            cancelText = StringUtils.getString(R.string.cancel),
            onConfirm = { doSaveRecord(babyId, startTime, endTime, duration) }
        )
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
        val leftBreastDuration = if (selectedFeedingType == FeedingType.BREAST) {
            binding.etLeftBreastDuration.text.toString().toLongOrNull()?.let {
                DateUtils.minutesToMilliseconds(it)
            } ?: 0L
        } else 0L

        val rightBreastDuration = if (selectedFeedingType == FeedingType.BREAST) {
            binding.etRightBreastDuration.text.toString().toLongOrNull()?.let {
                DateUtils.minutesToMilliseconds(it)
            } ?: 0L
        } else 0L
        
        // 获取喂奶量（配方奶/混合喂养时）
        val feedingAmount = when (selectedFeedingType) {
            FeedingType.FORMULA, FeedingType.MIXED -> {
                binding.etFeedingAmount.text.toString().toIntOrNull()
            }
            else -> null
        }
        
        // 获取辅食相关数据
        val solidFoodType = if (selectedFeedingType == FeedingType.SOLID_FOOD) {
            selectedSolidSubtype
        } else null
        
        val foodName = when (selectedFeedingType) {
            FeedingType.SOLID_FOOD -> binding.etSolidFoodName.text.toString().trim().takeIf { it.isNotEmpty() }
            FeedingType.OTHER -> binding.etOtherFoodName.text.toString().trim().takeIf { it.isNotEmpty() }
            else -> null
        }
        
        val isFirstTime = if (selectedFeedingType == FeedingType.SOLID_FOOD) {
            binding.cbFirstTime.isChecked
        } else false
        
        // 辅食的喂食量
        val solidAmount = if (selectedFeedingType == FeedingType.SOLID_FOOD) {
            binding.etSolidAmount.text.toString().toIntOrNull()
        } else null

        val feedingRecord = FeedingRecord().apply {
            this.feedingId = editingRecord?.feedingId ?: 0
            this.babyId = babyId
            this.feedingType = selectedFeedingType.type
            this.feedingStart = startTime
            this.feedingEnd = endTime
            this.feedingDuration = duration
            this.feedingDurationBreastLeft = leftBreastDuration
            this.feedingDurationBreastRight = rightBreastDuration
            this.feedingAmount = feedingAmount ?: solidAmount
            this.solidFoodType = solidFoodType
            this.foodName = foodName
            this.isFirstTime = isFirstTime
            this.note = binding.etNote.text.toString().trim()
            this.createdAt = editingRecord?.createdAt ?: System.currentTimeMillis()
        }

        val onSaved = Runnable {
            LogUtils.d("feedingRecord success")
            if (!isEditMode) {
                // 清除进行中状态
                OngoingRecordManager.endFeeding(babyId)
                // 重置页面数据，防止再次进入时显示历史数据
                view?.post { resetPage() }
            }
            mainVm.navigateTo(getReturnTarget())
        }

        if (isEditMode) {
            vm.update(feedingRecord, onSaved)
        } else {
            vm.insert(feedingRecord, onSaved)
        }
    }

    /**
     * 显示退出确认对话框
     */
    private fun showExitConfirmDialog() {
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
                        OngoingRecordManager.cancelFeeding(babyId)
                    }
                }
                mainVm.navigateTo(getReturnTarget())
            }
        )
    }

    private fun getReturnTarget(): NavTarget {
        return (mainVm.navTarget.value as? NavTarget.FeedingRecord)?.returnTarget ?: NavTarget.Dashboard
    }

    /**
     * 标记为有未保存的更改
     */
    private fun markAsUnsaved() {
        hasUnsavedChanges = true
    }

    /**
     * 重置页面数据
     * 在保存成功后或页面初始化时调用
     */
    private fun resetPage() {
        isProgrammaticChange = true
        
        // 重置喂养类型
        selectedFeedingType = FeedingType.BREAST
        binding.rbBreast.isChecked = true
        updateFeedingTypeVisibility()
        
        // 重置辅食相关
        selectedSolidCategory = null
        selectedSolidSubtype = null
        binding.rgSolidCategory.clearCheck()
        binding.rgSolidSubtype.removeAllViews()
        binding.hsvSolidSubtype.visibility = View.GONE
        binding.llSolidFoodName.visibility = View.GONE
        binding.etSolidFoodName.setText("")
        binding.etSolidAmount.setText("")
        binding.cbFirstTime.isChecked = false
        binding.tvAllergenTip.visibility = View.GONE
        
        // 重置其他类型
        binding.etOtherFoodName.setText("")
        
        // 清空时间输入
        binding.etStartTime.clear()
        binding.etEndTime.clear()
        binding.etEndTime.clearReferenceTimestamp()
        pausedEndTimestamp = 0L
        
        // 重置计时器
        binding.rvCounter.reset()
        
        // 清空左右乳房时长
        binding.etLeftBreastDuration.setText("")
        binding.etRightBreastDuration.setText("")
        
        // 清空喂奶量
        binding.etFeedingAmount.setText("")
        
        // 清空备注
        binding.etNote.setText("")
        
        // 重置未保存标记
        hasUnsavedChanges = false
        
        isProgrammaticChange = false
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
            // 检查是否有进行中的喂养记录
            val babyId = mainVm.getCurrentBabyInfo()?.babyId
            if (babyId != null && OngoingRecordManager.isFeeding(babyId)) {
                // 恢复进行中的记录
                restoreOngoingFeeding(babyId)
            } else {
                resetPage()
            }
            loadLastFeedingRecord()
        }
    }

    /**
     * 恢复进行中的喂养记录
     */
    private fun restoreOngoingFeeding(babyId: Int) {
        val startTime = OngoingRecordManager.getOngoingFeedingStart(babyId) ?: return
        
        isProgrammaticChange = true
        
        // 恢复喂养类型
        selectedFeedingType = FeedingType.fromType(OngoingRecordManager.getOngoingFeedingType(babyId))
        when (selectedFeedingType) {
            FeedingType.BREAST -> binding.rbBreast.isChecked = true
            FeedingType.FORMULA -> binding.rbFormula.isChecked = true
            FeedingType.MIXED -> binding.rbMixed.isChecked = true
            FeedingType.SOLID_FOOD -> binding.rbSolid.isChecked = true
            FeedingType.OTHER -> binding.rbOther.isChecked = true
        }
        updateFeedingTypeVisibility()
        
        // 设置开始时间
        binding.etStartTime.setTimestamp(startTime)
        updateEndTimeReference()
        
        // 计算已经过的时间，恢复计时器状态
        val elapsed = System.currentTimeMillis() - startTime
        binding.rvCounter.startFromOffset(elapsed)
        
        hasUnsavedChanges = true
        isProgrammaticChange = false
    }

    /**
     * 加载上次喂奶记录
     */
    private fun loadLastFeedingRecord() {
        mainVm.getCurrentBabyInfo()?.babyId?.let { babyId ->
            vm.loadLastFeedingRecord(babyId)
        }
    }

    override fun initData(view: View, savedInstanceState: Bundle?) {
        super.initData(view, savedInstanceState)

        // 加载上次喂奶记录
        loadLastFeedingRecord()

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
