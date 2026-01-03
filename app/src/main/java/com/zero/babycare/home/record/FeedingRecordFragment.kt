package com.zero.babycare.home.record

import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.View
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import com.blankj.utilcode.util.LogUtils
import com.blankj.utilcode.util.StringUtils
import com.blankj.utilcode.util.ToastUtils
import com.zero.babycare.MainViewModel
import com.zero.babycare.databinding.FragmentFeedingRecordBinding
import com.zero.babycare.home.OngoingRecordManager
import com.zero.babycare.navigation.NavTarget
import com.zero.babycare.navigation.BackPressHandler
import com.zero.babydata.entity.FeedingRecord
import com.zero.babydata.entity.FeedingType
import com.zero.babydata.entity.SolidFoodType
import com.zero.common.R
import com.zero.common.ext.launchInLifecycle
import com.zero.common.util.DateUtils
import com.zero.common.util.DateUtils.timestampToMMddHHmm
import com.zero.common.util.UnitConfig
import com.zero.common.util.UnitConverter
import com.zero.components.base.BaseFragment
import com.zero.components.base.util.DialogHelper
import com.zero.components.base.vm.UiState
import com.zero.components.widget.RecordView.RecordState

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
class FeedingRecordFragment : BaseFragment<FragmentFeedingRecordBinding>(), BackPressHandler {
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

    private lateinit var timerController: RecordTimerController

    override fun initView(view: View, savedInstanceState: Bundle?) {
        super.initView(view, savedInstanceState)
        binding.btn.title = StringUtils.getString(R.string.feeding)

        setupFeedingTypeSelector()
        setupTimerController()
        setupToolbar()
        setupSaveButton()
        setupFirstTimeCheckbox()
        updateFeedingUnitDisplay()
        
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
        binding.timerPanel.timerView.release()
    }

    /**
     * 设置喂养类型选择器
     */
    private fun setupFeedingTypeSelector() {
        binding.rbBreast.isChecked = true
        updateFeedingTypeVisibility()

        binding.rgFeedingType.setOnCheckedChangeListener { _, checkedId ->
            if (isProgrammaticChange) return@setOnCheckedChangeListener
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

            val babyId = mainVm.getCurrentBabyInfo()?.babyId
            if (!isEditMode && babyId != null && OngoingRecordManager.isFeeding(babyId)) {
                // 同步进行中的喂养类型，避免恢复与状态卡不一致
                OngoingRecordManager.updateFeedingType(babyId, selectedFeedingType.type)
            }
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
                    android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, 28f, resources.displayMetrics).toInt()
                ).apply {
                    marginEnd = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, 6f, resources.displayMetrics).toInt()
                }
                setPadding(
                    android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, 10f, resources.displayMetrics).toInt(),
                    0,
                    android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, 10f, resources.displayMetrics).toInt(),
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
     * 更新喂养单位展示
     */
    private fun updateFeedingUnitDisplay() {
        val unit = UnitConfig.getFeedingUnit()
        binding.tvFeedingAmountUnit.text = StringUtils.getString(UnitConfig.getFeedingUnitLabelResId())
        updateFeedingAmountInput(unit)
    }

    /**
     * 根据单位调整输入框类型，保证 oz 支持小数
     */
    private fun updateFeedingAmountInput(unit: String) {
        val inputType = if (unit == UnitConfig.FEEDING_UNIT_OZ) {
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        } else {
            InputType.TYPE_CLASS_NUMBER
        }
        val maxLength = if (unit == UnitConfig.FEEDING_UNIT_OZ) 5 else 4
        binding.etFeedingAmount.inputType = inputType
        binding.etFeedingAmount.filters = arrayOf(InputFilter.LengthFilter(maxLength))
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

    private fun setupTimerController() {
        timerController = RecordTimerController(
            context = requireContext(),
            timerView = binding.timerPanel.timerView,
            startInput = binding.timerPanel.startInput,
            endInput = binding.timerPanel.endInput,
            startPicker = binding.timerPanel.startPicker,
            endPicker = binding.timerPanel.endPicker,
            config = RecordTimerController.Config(
                invalidEndTimeMessageRes = R.string.end_time_must_after_start,
                shouldIgnoreInput = { isProgrammaticChange }
            ),
            callbacks = RecordTimerController.Callbacks(
                onStartTimeChanged = { startTime ->
                    markAsUnsaved()
                    val babyId = mainVm.getCurrentBabyInfo()?.babyId
                    if (!isEditMode && babyId != null && OngoingRecordManager.isFeeding(babyId)) {
                        // 同步进行中的开始时间，避免恢复与状态卡不一致
                        OngoingRecordManager.updateFeedingStart(babyId, startTime)
                    }
                },
                onEndTimeChanged = { markAsUnsaved() },
                onTimerStart = {
                    mainVm.getCurrentBabyInfo()?.babyId?.let { babyId ->
                        OngoingRecordManager.startFeeding(babyId, selectedFeedingType.type)
                    }
                },
                onTimerResume = { markAsUnsaved() },
                onDirty = { markAsUnsaved() }
            )
        )
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
    override fun onSystemBackPressed(): Boolean {
        handleBack()
        return true
    }

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
        if (!binding.timerPanel.startInput.hasValidTime()) {
            return false
        }

        if (binding.timerPanel.startInput.isFutureTime()) {
            ToastUtils.showShort(R.string.time_cannot_be_future)
            return false
        }

        return true
    }

    /**
     * 校验结束时间
     */
    private fun validateEndTime(): Boolean {
        if (!binding.timerPanel.endInput.hasValidTime()) {
            return false
        }

        if (binding.timerPanel.endInput.isFutureTime()) {
            ToastUtils.showShort(R.string.time_cannot_be_future)
            return false
        }

        return true
    }

    /**
     * 校验时间范围（结束时间必须晚于开始时间）
     */
    private fun validateTimeRange(): Boolean {
        if (!binding.timerPanel.startInput.hasValidTime() || !binding.timerPanel.endInput.hasValidTime()) {
            return false
        }

        val startTime = binding.timerPanel.startInput.getTimestamp()
        val endTime = binding.timerPanel.endInput.getTimestamp()

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
        binding.timerPanel.timerView.isEnabled = !locked
        binding.timerPanel.timerView.isClickable = !locked
        binding.timerPanel.timerView.alpha = if (locked) 0.4f else 1f
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

        timerController.setStartTime(record.feedingStart, notify = false)
        if (record.feedingEnd > 0L) {
            timerController.setEndTime(record.feedingEnd, notify = false, updateDuration = true)
        } else {
            timerController.clearEndTime(notify = false)
        }

        val leftMinutes = DateUtils.millisecondsToMinutes(record.feedingDurationBreastLeft)
        val rightMinutes = DateUtils.millisecondsToMinutes(record.feedingDurationBreastRight)
        binding.etLeftBreastDuration.setText(if (leftMinutes > 0) leftMinutes.toString() else "")
        binding.etRightBreastDuration.setText(if (rightMinutes > 0) rightMinutes.toString() else "")

        when (selectedFeedingType) {
            FeedingType.FORMULA, FeedingType.MIXED -> {
                val amount = record.feedingAmount
                if (amount != null) {
                    val unit = UnitConfig.getFeedingUnit()
                    val displayValue = UnitConverter.feedingToDisplay(amount, unit)
                    binding.etFeedingAmount.setText(UnitConverter.formatFeedingAmount(displayValue, unit))
                } else {
                    binding.etFeedingAmount.setText("")
                }
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
        if (binding.timerPanel.timerView.currentShowState == RecordState.RECORDING) {
            binding.timerPanel.timerView.forcePause()
            // 更新结束时间
            binding.timerPanel.endInput.setTimestamp(System.currentTimeMillis())
        }

        // 校验必填项
        if (!binding.timerPanel.startInput.hasValidTime()) {
            ToastUtils.showShort(R.string.start_time_required)
            return
        }

        if (!binding.timerPanel.endInput.hasValidTime()) {
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

        val startTime = binding.timerPanel.startInput.getTimestamp()
        val endTime = binding.timerPanel.endInput.getTimestamp()

        // 计算时长：优先使用计时器时长，如果没有则使用时间差
        var duration = binding.timerPanel.timerView.getDuration()
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
                binding.etFeedingAmount.text.toString().toDoubleOrNull()?.let { value ->
                    UnitConverter.feedingToStorage(value, UnitConfig.getFeedingUnit())
                }
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
        
        // 清空时间输入与计时器
        timerController.reset()
        
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
            updateFeedingUnitDisplay()
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
        timerController.syncStartTime(startTime, clearEnd = true, resetTimer = false, notify = false)
        
        // 计算已经过的时间，恢复计时器状态
        val elapsed = System.currentTimeMillis() - startTime
        binding.timerPanel.timerView.startFromOffset(elapsed)
        
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
