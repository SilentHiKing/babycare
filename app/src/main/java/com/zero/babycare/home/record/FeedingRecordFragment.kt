package com.zero.babycare.home.record

import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import androidx.core.widget.doAfterTextChanged
import com.google.gson.Gson
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
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
import com.zero.components.widget.ToolbarAction
import java.util.Locale
import kotlinx.coroutines.flow.combine
import java.util.UUID

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
    private var mutationInProgress = false
    private val formFreeze = RecordFormFreeze()
    private var sessionChecking = false
    private var sessionCheckFailed = false
    private var sessionCommitting = false
    private var preserveRecoveredDraft = false
    private var renderedRecovery: SessionCommitCoordinator.Recovery? = null

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

    private var timerController: RecordTimerController? = null
    private var retainedDraft: Bundle? = null
    private var draftForView: Bundle? = null
    private var draftBabyId: Int? = null
    private var draftId = UUID.randomUUID().toString()
    private var amountInput = RecordNumberInput(UnitConfig.FEEDING_UNIT_ML)
    private var leftDurationInput = RecordNumberInput("min")
    private var rightDurationInput = RecordNumberInput("min")

    override fun initView(view: View, savedInstanceState: Bundle?) {
        super.initView(view, savedInstanceState)
        binding.btn.title = getString(R.string.feeding)

        setupFeedingTypeSelector()
        setupTimerController()
        setupToolbar()
        setupSaveButton()
        setupFirstTimeCheckbox()
        listOf(binding.etNote, binding.etFeedingAmount, binding.etLeftBreastDuration,
            binding.etRightBreastDuration, binding.etSolidFoodName, binding.etSolidAmount,
            binding.etOtherFoodName).forEach { input -> input.doAfterTextChanged { markAsUnsaved() } }
        
        // 初始化时重置页面数据
        resolveEditMode()
        draftForView = (savedInstanceState?.getBundle(RecordDraftState.KEY) ?: retainedDraft)
            ?.takeIf { mainVm.navTarget.value is NavTarget.FeedingRecord &&
                RecordDraftState.matches(it, mainVm.getCurrentBabyInfo()?.babyId, editRecordId) }
        draftBabyId = draftForView?.getInt("baby_id")?.takeIf { it > 0 }
            ?: mainVm.getCurrentBabyInfo()?.babyId
        draftId = draftForView?.getString("draft_id") ?: UUID.randomUUID().toString()
        amountInput = restoreNumberInput(draftForView, "amount_input", UnitConfig.getFeedingUnit())
        leftDurationInput = restoreNumberInput(draftForView, "left_duration_input", "min")
        rightDurationInput = restoreNumberInput(draftForView, "right_duration_input", "min")
        updateFeedingUnitDisplay()
        if (draftForView == null) resetPage()
        if (isEditMode && draftForView?.getString("editing_record") == null) {
            loadEditRecord()
        }
        launchInLifecycle {
            mainVm.currentBaby.combine(mainVm.navTarget) { baby, target -> baby?.babyId to target }
                .collect { (babyId, target) ->
                    if (!isHidden && target is NavTarget.FeedingRecord && babyId != null) {
                        val changed = (draftBabyId != null && draftBabyId != babyId) || editRecordId != target.editRecordId
                        if (draftBabyId == null) draftBabyId = babyId
                        if (changed) openCurrentTarget()
                    }
                }
        }
        observeMutation()
        observeSessionRecovery()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (view != null && !isHidden) outState.putBundle(RecordDraftState.KEY, captureDraft())
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        val restoredDraft = draftForView != null
        draftForView?.let { draft ->
            isProgrammaticChange = true
            editingRecord = draft.getString("editing_record")?.let { Gson().fromJson(it, FeedingRecord::class.java) }
                ?: editingRecord
            RecordDraftState.restoreHierarchy(binding.root, draft)
            selectedFeedingType = FeedingType.fromType(draft.getInt("feeding_type"))
            selectedSolidCategory = draft.getInt("solid_category").takeIf { it != 0 }
            // 动态 RadioButton 的 View ID 重建后会变化，必须按稳定的业务类型恢复。
            updateFeedingTypeVisibility()
            updateSolidSubtypes()
            selectedSolidSubtype = draft.getInt("solid_subtype").takeIf { it != 0 }
            for (index in 0 until binding.rgSolidSubtype.childCount) {
                val radio = binding.rgSolidSubtype.getChildAt(index) as? android.widget.RadioButton
                if (radio?.tag == selectedSolidSubtype) radio?.isChecked = true
            }
            updateSolidFoodNameVisibility()
            updateSolidUnit()
            updateAllergenTip()
            draft.getBundle("timer")?.let { RecordDraftState.restoreTimer(binding.timerPanel, timerController, it) }
            hasUnsavedChanges = draft.getBoolean("dirty")
            isProgrammaticChange = false
            applyTimerLockState()
            updateToolbarAction()
        }
        draftForView = null
        retainedDraft = null
        if (!isHidden && mainVm.navTarget.value is NavTarget.FeedingRecord) {
            requestSessionRecovery(preserveDraft = restoredDraft)
        }
    }

    private fun captureDraft(): Bundle = Bundle().apply {
        putInt("edit_id", editRecordId ?: 0)
        putString("draft_id", draftId)
        putInt("baby_id", draftBabyId ?: mainVm.getCurrentBabyInfo()?.babyId ?: 0)
        putBoolean("dirty", hasUnsavedChanges)
        putInt("feeding_type", selectedFeedingType.type)
        putInt("solid_category", selectedSolidCategory ?: 0)
        putInt("solid_subtype", selectedSolidSubtype ?: 0)
        putString("amount_input", Gson().toJson(amountInput))
        putString("left_duration_input", Gson().toJson(leftDurationInput))
        putString("right_duration_input", Gson().toJson(rightDurationInput))
        editingRecord?.let { putString("editing_record", Gson().toJson(it)) }
        putBundle("timer", RecordDraftState.saveTimer(binding.timerPanel, timerController))
        RecordDraftState.saveHierarchy(binding.root, this)
    }

    private fun restoreNumberInput(draft: Bundle?, key: String, fallbackUnit: String): RecordNumberInput =
        draft?.getString(key)?.let { Gson().fromJson(it, RecordNumberInput::class.java) }
            ?: RecordNumberInput(fallbackUnit)

    override fun onDestroyView() {
        retainedDraft = if (!isHidden) captureDraft() else null
        timerController?.dispose()
        timerController = null
        formFreeze.clear()
        binding.timerPanel.timerView.release()
        super.onDestroyView()
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

            val babyId = currentDraftBabyId()
            val sessionId = timerController?.ongoingSessionId
            if (!isEditMode && babyId != null && sessionId != null) {
                // 同步进行中的喂养类型，避免恢复与状态卡不一致
                OngoingRecordManager.updateFeedingType(babyId, selectedFeedingType.type, expectedSessionId = sessionId)
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
        val subtypeHeight = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_DIP,
            48f,
            resources.displayMetrics
        ).toInt()
        val subtypeGap = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_DIP,
            8f,
            resources.displayMetrics
        ).toInt()
        val subtypeHorizontalPadding = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_DIP,
            14f,
            resources.displayMetrics
        ).toInt()
        val subtypeTextColor = ContextCompat.getColorStateList(
            requireContext(),
            com.zero.common.R.color.selector_radio_text_color
        )
        subtypes.forEach { (type, stringResId) ->
            val radioButton = android.widget.RadioButton(requireContext()).apply {
                id = View.generateViewId()
                tag = type
                text = getString(stringResId)
                layoutParams = android.widget.RadioGroup.LayoutParams(
                    android.widget.RadioGroup.LayoutParams.WRAP_CONTENT,
                    subtypeHeight
                ).apply {
                    marginEnd = subtypeGap
                }
                setPadding(
                    subtypeHorizontalPadding,
                    0,
                    subtypeHorizontalPadding,
                    0
                )
                setBackgroundResource(com.zero.common.R.drawable.selector_solid_subtype_bg)
                buttonDrawable = null
                gravity = android.view.Gravity.CENTER
                TextViewCompat.setTextAppearance(this, com.zero.common.R.style.TextAppearance_BabyCare_Label)
                // selector_radio_text_color 依赖当前主题属性，必须通过 ContextCompat 带主题解析，避免动态控件退成异常粉色。
                setTextColor(subtypeTextColor)
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
        binding.tvSolidUnit.text = getString(SolidFoodType.getDefaultUnitResId(subtype))
    }

    /**
     * 更新喂养单位展示
     */
    private fun updateFeedingUnitDisplay() {
        val unit = amountInput.unit
        binding.tvFeedingAmountUnit.setText(if (unit == UnitConfig.FEEDING_UNIT_OZ) R.string.unit_oz_abbr else R.string.unit_ml_abbr)
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
        binding.rowFirstTime.setOnClickListener {
            // grouped row 扩大点击热区，底层仍然复用 cbFirstTime 的布尔状态和过敏提示逻辑。
            binding.cbFirstTime.isChecked = !binding.cbFirstTime.isChecked
        }
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
                mode = TimerMode.FEEDING,
                shouldIgnoreInput = { isProgrammaticChange }
            ),
            callbacks = RecordTimerController.Callbacks(
                onStartTimeChanged = { startTime ->
                    markAsUnsaved()
                    val babyId = currentDraftBabyId()
                    val sessionId = timerController?.ongoingSessionId
                    if (!isEditMode && babyId != null && sessionId != null) {
                        // 同步进行中的开始时间，避免恢复与状态卡不一致
                        OngoingRecordManager.updateFeedingStart(babyId, startTime, expectedSessionId = sessionId)
                    }
                },
                onEndTimeChanged = { markAsUnsaved() },
                onTimerStart = { startTime ->
                    if (!isEditMode) currentDraftBabyId()?.let { babyId ->
                        OngoingRecordManager.startFeeding(
                            babyId, selectedFeedingType.type, startTime,
                            automaticStart = timerController?.isStartTimeAutomatic == true
                        )
                        OngoingRecordManager.getFeedingSession(babyId)?.let { session ->
                            timerController?.restoreAutomaticStart(session.automaticStart, session.clockAnchor, session.sessionId)
                        }
                    }
                },
                onTimerResume = {
                    markAsUnsaved()
                    val sessionId = timerController?.ongoingSessionId
                    if (!isEditMode && sessionId != null) currentDraftBabyId()?.let {
                        OngoingRecordManager.resumeFeeding(it, expectedSessionId = sessionId)
                    }
                },
                onTimerPause = { endTime ->
                    val sessionId = timerController?.ongoingSessionId
                    if (!isEditMode && sessionId != null) currentDraftBabyId()?.let { babyId ->
                        OngoingRecordManager.pauseFeeding(
                            babyId = babyId,
                            endTime = endTime,
                            activeDurationMs = binding.timerPanel.timerView.getDuration(),
                            expectedSessionId = sessionId
                        )
                    }
                },
                onClockSynchronized = { sessionId, start, clock ->
                    if (!isEditMode) currentDraftBabyId()?.let {
                        vm.synchronizeTimerStart(it, sessionId, start, clock)
                    }
                },
                onDirty = { markAsUnsaved() }
            )
        )
    }


    private fun setupToolbar() {
        binding.btn.showBackButton {
            handleBack()
        }
        updateToolbarAction()
    }

    private fun updateToolbarAction() {
        if (!isEditMode) {
            binding.btn.hideAction()
            return
        }
        val label = getString(R.string.delete_record)
        binding.btn.setActions(
            listOf(
                ToolbarAction(
                    text = label,
                    contentDescription = label,
                    enabled = !mutationInProgress && editingRecord != null
                )
            )
        ) { showDeleteConfirmDialog() }
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
        if (mutationInProgress || sessionChecking || sessionCommitting) {
            mainVm.navigateTo(getReturnTarget())
            return
        }
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
            showFieldValidationError(binding.timerPanel.startInput, R.string.start_time_required)
            return false
        }

        if (binding.timerPanel.startInput.isFutureTime()) {
            showFieldValidationError(binding.timerPanel.startInput, R.string.time_cannot_be_future)
            return false
        }

        return true
    }

    /**
     * 校验结束时间
     */
    private fun validateEndTime(): Boolean {
        if (!binding.timerPanel.endInput.hasValidTime()) {
            showFieldValidationError(binding.timerPanel.endInput, R.string.end_time_required)
            return false
        }

        if (binding.timerPanel.endInput.isFutureTime()) {
            showFieldValidationError(binding.timerPanel.endInput, R.string.time_cannot_be_future)
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
            showFieldValidationError(binding.timerPanel.endInput, R.string.end_time_must_after_start)
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

        val leftDuration = leftDurationInput.durationMillis(binding.etLeftBreastDuration.text.toString())
        val rightDuration = rightDurationInput.durationMillis(binding.etRightBreastDuration.text.toString())
        val totalBreastDuration = leftDuration + rightDuration
        
        if (totalBreastDuration > totalDuration) {
            val target = if (rightDuration > 0) {
                binding.etRightBreastDuration
            } else {
                binding.etLeftBreastDuration
            }
            showFieldValidationError(target, R.string.breast_duration_exceed)
            return false
        }

        return true
    }

    private fun resolveEditMode() {
        val navTarget = mainVm.navTarget.value as? NavTarget.FeedingRecord
        editRecordId = navTarget?.editRecordId
        isEditMode = editRecordId != null
        if (!isEditMode || editingRecord?.feedingId != editRecordId) {
            editingRecord = null
        }
        applyTimerLockState()
        updateToolbarAction()
    }

    private fun applyTimerLockState() {
        val returnTarget = (mainVm.navTarget.value as? NavTarget.FeedingRecord)?.returnTarget
        val locked = (isEditMode && returnTarget is NavTarget.Statistics) ||
            mutationInProgress || sessionChecking || sessionCommitting || sessionCheckFailed
        binding.timerPanel.timerView.isEnabled = !locked
        binding.timerPanel.timerView.isClickable = !locked
        binding.timerPanel.timerView.alpha = if (locked) 0.4f else 1f
    }

    private fun loadEditRecord() {
        val recordId = editRecordId ?: return
        val expectedView = view
        val expectedBabyId = draftBabyId
        val expectedDraftId = draftId
        vm.loadFeedingRecordById(recordId) { record ->
            if (view !== expectedView || isHidden || editRecordId != recordId || draftId != expectedDraftId) return@loadFeedingRecordById
            if (record == null || (expectedBabyId != null && record.babyId != expectedBabyId) ||
                (draftBabyId != null && record.babyId != draftBabyId)) {
                mainVm.navigateTo(getReturnTarget())
                return@loadFeedingRecordById
            }
            draftBabyId = record.babyId
            editingRecord = record
            updateToolbarAction()
            if (!hasUnsavedChanges) applyRecordToUi(record)
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

        timerController?.setStartTime(record.feedingStart, notify = false)
        if (record.feedingEnd > 0L) {
            timerController?.setEndTime(record.feedingEnd, notify = false)
            // 编辑备注不能把暂停间隔重新算作喂养；用户主动修改时间时 Controller 才重新算区间。
            binding.timerPanel.timerView.showDurationWithoutTimer(record.feedingDuration)
        } else {
            timerController?.clearEndTime(notify = false)
        }

        val leftMinutes = DateUtils.millisecondsToMinutes(record.feedingDurationBreastLeft)
        val rightMinutes = DateUtils.millisecondsToMinutes(record.feedingDurationBreastRight)
        // 历史不足一分钟也显示 0，明确清空后才清除原值，避免“原本空白”与清空操作无法区分。
        binding.etLeftBreastDuration.setText(if (record.feedingDurationBreastLeft > 0) leftMinutes.toString() else "")
        binding.etRightBreastDuration.setText(if (record.feedingDurationBreastRight > 0) rightMinutes.toString() else "")
        leftDurationInput = leftDurationInput.withOriginal(binding.etLeftBreastDuration.text.toString(), record.feedingDurationBreastLeft)
        rightDurationInput = rightDurationInput.withOriginal(binding.etRightBreastDuration.text.toString(), record.feedingDurationBreastRight)

        when (selectedFeedingType) {
            FeedingType.FORMULA, FeedingType.MIXED -> {
                val amount = record.feedingAmount
                if (amount != null) {
                    val unit = amountInput.unit
                    val displayValue = UnitConverter.feedingToDisplay(amount, unit)
                    binding.etFeedingAmount.setText(UnitConverter.formatFeedingAmount(displayValue, unit))
                } else {
                    binding.etFeedingAmount.setText("")
                }
                amountInput = amountInput.withOriginal(binding.etFeedingAmount.text.toString(), amount)
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
                binding.etSolidAmount.setText(
                    record.feedingAmount?.let { String.format(Locale.getDefault(), "%d", it) }.orEmpty()
                )
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
        if (sessionCheckFailed) {
            requestSessionRecovery(preserveDraft = true)
            return
        }
        if (sessionChecking || sessionCommitting) return
        if (vm.mutation.isPending || (isEditMode && editingRecord == null)) return
        val babyId = currentDraftBabyId()
        if (babyId == null || babyId < 0) {
            ToastUtils.showShort(getString(R.string.no_baby_selected))
            return
        }
        if (isEditMode && editingRecord?.babyId != babyId) return
        clearValidationErrors()
        if (!binding.timerPanel.startInput.hasValidTime()) {
            showFieldValidationError(binding.timerPanel.startInput,
                if (binding.timerPanel.startInput.text.isNullOrBlank()) R.string.start_time_required else R.string.invalid_time)
            return
        }

        // 尚未输入完整的时间不能被“保存时自动暂停”覆盖成当前时间。
        if (!binding.timerPanel.endInput.text.isNullOrBlank() && !binding.timerPanel.endInput.hasValidTime()) {
            showFieldValidationError(binding.timerPanel.endInput, R.string.invalid_time)
            return
        }

        // 如果正在计时，先暂停
        if (binding.timerPanel.timerView.currentShowState == RecordState.RECORDING) {
            binding.timerPanel.timerView.forcePause(triggerCallback = true)
            // 使用暂停回调已同步给持久会话的同一结束时刻，避免重试内容发生毫秒漂移。
        }

        // 校验必填项
        if (!binding.timerPanel.startInput.hasValidTime()) {
            showFieldValidationError(binding.timerPanel.startInput, R.string.start_time_required)
            return
        }

        if (!binding.timerPanel.endInput.hasValidTime()) {
            showFieldValidationError(binding.timerPanel.endInput, R.string.end_time_required)
            return
        }

        // 校验时间合理性
        if (!validateStartTime() || !validateEndTime()) {
            return
        }

        if (!validateTimeRange()) {
            return
        }

        val startTime = binding.timerPanel.startInput.getTimestamp()
        val endTime = binding.timerPanel.endInput.getTimestamp()

        // 喂养保留暂停/继续产生的有效计时时长；无有效计时或超过时间跨度时回退到开始-结束区间。
        val duration = TimerSessionPolicy.calculateSavedDuration(
            mode = TimerMode.FEEDING,
            startAt = startTime,
            endAt = endTime,
            activeDuration = binding.timerPanel.timerView.getDuration()
        ).valueMs

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
            title = getString(R.string.confirm_save),
            content = getString(R.string.feeding_duration_too_long),
            confirmText = getString(R.string.confirm),
            cancelText = getString(R.string.cancel),
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
        val expectedDraftId = draftId
        val durationMinutes = DateUtils.millisecondsToSmartMinutes(duration)
        val startTimeStr = timestampToMMddHHmm(startTime)
        val endTimeStr = timestampToMMddHHmm(endTime)

        val message = getString(
            R.string.feeding_summary,
            durationMinutes,
            startTimeStr,
            endTimeStr
        )

        DialogHelper.showConfirmDialog(
            context = requireContext(),
            title = getString(R.string.confirm_save),
            content = message,
            confirmText = getString(R.string.confirm),
            cancelText = getString(R.string.cancel),
            onConfirm = { if (expectedDraftId == draftId && !isHidden) doSaveRecord(babyId, startTime, endTime, duration) }
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
        if (vm.mutation.isPending || currentDraftBabyId() != babyId) return

        // 获取左右乳房时长（仅母乳喂养时）
        val leftBreastDuration = if (selectedFeedingType == FeedingType.BREAST) {
            leftDurationInput.durationMillis(binding.etLeftBreastDuration.text.toString())
        } else 0L

        val rightBreastDuration = if (selectedFeedingType == FeedingType.BREAST) {
            rightDurationInput.durationMillis(binding.etRightBreastDuration.text.toString())
        } else 0L
        
        // 获取喂奶量（配方奶/混合喂养时）
        val feedingAmount = when (selectedFeedingType) {
            FeedingType.FORMULA, FeedingType.MIXED -> {
                amountInput.feedingAmount(binding.etFeedingAmount.text.toString())
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

        vm.save(feedingRecord, mutationTarget(babyId),
            if (isEditMode) null else timerController?.ongoingSessionId,
            originalRecord = editingRecord)
    }

    /** 编辑态允许纠正误记录；删除完成后由变更总线统一刷新依赖数据。 */
    private fun showDeleteConfirmDialog() {
        val record = editingRecord ?: return
        if (vm.mutation.isPending) return
        val target = mutationTarget(record.babyId)
        DialogHelper.showConfirmDialog(
            context = requireContext(),
            title = getString(R.string.delete_record),
            content = getString(R.string.delete_record_confirm),
            confirmText = getString(R.string.delete_record),
            cancelText = getString(R.string.cancel),
            onConfirm = {
                if (target.belongsTo(draftId, currentDraftBabyId(), mainVm.navTarget.value)) {
                    vm.delete(record.feedingId, target)
                }
            }
        )
    }

    private fun setMutationInProgress(inProgress: Boolean) {
        mutationInProgress = inProgress
        renderInputFreeze()
    }

    private fun renderInputFreeze() {
        if (view == null) return
        val inProgress = mutationInProgress || sessionChecking || sessionCommitting
        binding.tvSave.isEnabled = !inProgress
        binding.tvSave.alpha = if (inProgress) 0.7f else 1f
        val frozen = inProgress || sessionCheckFailed
        if (frozen) timerController?.dismissPendingUi()
        formFreeze.render(frozen, binding.cardTimer, binding.cardFeedingContent)
        applyTimerLockState()
        updateToolbarAction()
    }

    /** 草稿恢复先核对同一会话的数据库结果；核验完成前不能继续计时或改写冻结输入。 */
    private fun requestSessionRecovery(preserveDraft: Boolean) {
        sessionChecking = false
        sessionCheckFailed = false
        sessionCommitting = false
        renderedRecovery = null
        val babyId = currentDraftBabyId()
        val sessionId = timerController?.ongoingSessionId
        if (isEditMode || babyId == null || (preserveDraft && sessionId == null)) {
            renderInputFreeze()
            return
        }
        preserveRecoveredDraft = preserveDraft
        sessionChecking = true
        renderInputFreeze()
        vm.sessions.recover(mutationTarget(babyId), if (preserveDraft) sessionId else null)
    }

    private fun observeSessionRecovery() {
        launchInLifecycle {
            vm.sessions.committingSessions.collect { sessions ->
                sessionCommitting = !isEditMode && timerController?.ongoingSessionId?.let { it in sessions } == true
                renderInputFreeze()
            }
        }
        launchInLifecycle {
            vm.sessions.recovery.collect { state ->
                if (state == null || state === renderedRecovery ||
                    !state.target.belongsTo(draftId, currentDraftBabyId(), mainVm.navTarget.value) || isHidden) return@collect
                renderedRecovery = state
                sessionChecking = state.loading
                sessionCheckFailed = state.errorRes != null
                if (!state.loading) {
                    when {
                        state.errorRes != null -> {
                            if (!preserveRecoveredDraft) state.session?.let(::restoreOngoingFeeding)
                            ToastUtils.showShort(getString(state.errorRes))
                        }
                        state.committed -> {
                            resetPage()
                            ToastUtils.showShort(getString(R.string.save_success))
                        }
                        !preserveRecoveredDraft -> state.session?.let(::restoreOngoingFeeding)
                    }
                }
                renderInputFreeze()
            }
        }
    }

    private fun mutationTarget(babyId: Int) = RecordMutationController.Target(
        draftId, babyId, editRecordId, mainVm.navTarget.value, getReturnTarget()
    )

    private fun observeMutation() {
        launchInLifecycle {
            vm.mutation.state.collect { state ->
                setMutationInProgress(state is RecordMutationController.State.Running)
                if (state is RecordMutationController.State.Finished && vm.mutation.consume(state)) {
                    val belongs = state.target.belongsTo(draftId, currentDraftBabyId(), mainVm.navTarget.value)
                    if (state.target.draftId == draftId && state.errorRes == null) {
                        hasUnsavedChanges = false
                        resetPage()
                    }
                    if (belongs && !isHidden) {
                        val message = state.errorRes ?: if (state.kind == RecordMutationController.Kind.DELETE)
                            R.string.record_delete_success else R.string.save_success
                        ToastUtils.showShort(getString(message))
                        if (state.errorRes == null) mainVm.navigateTo(state.target.destination)
                        else if (!isEditMode && timerController?.ongoingSessionId != null) {
                            requestSessionRecovery(preserveDraft = true)
                        }
                    }
                }
            }
        }
    }

    /** 校验失败后把错误留在字段上，并把辅助功能焦点带回可修正的位置。 */
    @Suppress("DEPRECATION")
    private fun showFieldValidationError(field: TextView, @StringRes messageRes: Int) {
        val message = getString(messageRes)
        field.error = message
        field.requestFocus()
        field.announceForAccessibility(message)
    }

    private fun clearValidationErrors() {
        binding.timerPanel.startInput.error = null
        binding.timerPanel.endInput.error = null
        binding.etLeftBreastDuration.error = null
        binding.etRightBreastDuration.error = null
    }

    /**
     * 显示退出确认对话框
     */
    private fun showExitConfirmDialog() {
        val expectedDraftId = draftId
        val expectedBabyId = currentDraftBabyId()
        val expectedSessionId = timerController?.ongoingSessionId
        DialogHelper.showConfirmDialog(
            context = requireContext(),
            title = getString(R.string.tip),
            content = getString(R.string.unsaved_record_tip),
            confirmText = getString(R.string.confirm),
            cancelText = getString(R.string.cancel),
            onConfirm = {
                if (isHidden || view == null || draftId != expectedDraftId || currentDraftBabyId() != expectedBabyId) return@showConfirmDialog
                hasUnsavedChanges = false
                if (!isEditMode && expectedSessionId != null) {
                    currentDraftBabyId()?.let { babyId ->
                        OngoingRecordManager.cancelFeeding(babyId, expectedSessionId)
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
        if (!isProgrammaticChange) hasUnsavedChanges = true
    }

    /**
     * 重置页面数据
     * 在保存成功后或页面初始化时调用
     */
    private fun resetPage() {
        isProgrammaticChange = true
        amountInput = RecordNumberInput(UnitConfig.getFeedingUnit())
        leftDurationInput = RecordNumberInput("min")
        rightDurationInput = RecordNumberInput("min")
        updateFeedingUnitDisplay()
        
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
        timerController?.reset()
        
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
            retainedDraft = null
            timerController?.dismissPendingUi()
            // 隐藏时不重置计时器（保留进行中状态）
        } else {
            openCurrentTarget()
        }
    }

    /** 同页收到新宝宝或编辑目标时先清除旧表单，禁止旧草稿写入新目标。 */
    private fun openCurrentTarget() {
        sessionChecking = false
        sessionCheckFailed = false
        sessionCommitting = false
        formFreeze.render(false)
        draftId = UUID.randomUUID().toString()
        draftBabyId = mainVm.getCurrentBabyInfo()?.babyId
        updateFeedingUnitDisplay()
        resolveEditMode()
        resetPage()
        if (isEditMode) {
            loadEditRecord()
        } else {
            requestSessionRecovery(preserveDraft = false)
            loadLastFeedingRecord()
        }
    }

    private fun currentDraftBabyId(): Int? = mainVm.getCurrentBabyInfo()?.babyId
        ?.takeIf { draftBabyId == null || draftBabyId == it }

    /**
     * 恢复进行中的喂养记录
     */
    private fun restoreOngoingFeeding(session: OngoingRecordManager.OngoingSession) {
        session.pendingFeeding?.let(::applyRecordToUi)
        val startTime = session.startAt
        
        isProgrammaticChange = true
        
        // 恢复喂养类型
        selectedFeedingType = FeedingType.fromType(session.feedingType)
        when (selectedFeedingType) {
            FeedingType.BREAST -> binding.rbBreast.isChecked = true
            FeedingType.FORMULA -> binding.rbFormula.isChecked = true
            FeedingType.MIXED -> binding.rbMixed.isChecked = true
            FeedingType.SOLID_FOOD -> binding.rbSolid.isChecked = true
            FeedingType.OTHER -> binding.rbOther.isChecked = true
        }
        updateFeedingTypeVisibility()
        
        // 设置开始时间
        timerController?.syncStartTime(
            timestamp = startTime,
            clearEnd = session.endAt == null,
            resetTimer = false,
            notify = false
        )
        timerController?.restoreAutomaticStart(session.automaticStart, session.clockAnchor, session.sessionId)
        if (session.status == OngoingRecordManager.SessionStatus.PAUSED && session.endAt != null) {
            timerController?.restorePausedSession(session.endAt, session.activeDurationMs)
        } else {
            binding.timerPanel.timerView.startFromOffset(session.elapsedDuration())
        }
        
        hasUnsavedChanges = true
        isProgrammaticChange = false
    }

    /**
     * 加载上次喂奶记录
     */
    private fun loadLastFeedingRecord() {
        currentDraftBabyId()?.let { babyId ->
            vm.loadLastFeedingRecord(babyId)
        }
    }

    override fun initData(view: View, savedInstanceState: Bundle?) {
        super.initData(view, savedInstanceState)

        // 加载上次喂奶记录
        loadLastFeedingRecord()

        // LiveData 已绑定 View 生命周期，只注册一次，避免每次前台恢复都累加观察者。
        vm.lastFeedingRecord.observe(viewLifecycleOwner) { lastRecord ->
            if (lastRecord != null && lastRecord.babyId == currentDraftBabyId()) {
                val lastTimeStr = timestampToMMddHHmm(lastRecord.feedingEnd)
                binding.tvLastFeeding.text = getString(R.string.last_feeding_time, lastTimeStr)
                binding.tvLastFeeding.visibility = View.VISIBLE
            } else {
                binding.tvLastFeeding.visibility = View.GONE
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
