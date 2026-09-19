package com.zero.babycare.home.record

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.widget.doAfterTextChanged
import com.google.gson.Gson
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import com.blankj.utilcode.util.ToastUtils
import com.zero.babycare.MainViewModel
import com.zero.babycare.databinding.FragmentSleepRecordBinding
import com.zero.babycare.home.OngoingRecordManager
import com.zero.babycare.navigation.NavTarget
import com.zero.babycare.navigation.BackPressHandler
import com.zero.babydata.entity.SleepRecord
import com.zero.common.R
import com.zero.common.util.DateUtils
import com.zero.common.ext.launchInLifecycle
import kotlinx.coroutines.flow.combine
import java.util.UUID
import com.zero.components.base.BaseFragment
import com.zero.components.base.util.DialogHelper
import com.zero.components.widget.RecordView.RecordState
import com.zero.components.widget.ToolbarAction

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
    private var mutationInProgress = false

    // 是否有未保存的记录
    private var hasUnsavedChanges = false

    // 标记是否是程序设置时间（避免循环触发）
    private var isProgrammaticChange = false

    private var timerController: RecordTimerController? = null
    private var retainedDraft: Bundle? = null
    private var draftForView: Bundle? = null
    private var draftBabyId: Int? = null
    private var draftId = UUID.randomUUID().toString()

    override fun initView(view: View, savedInstanceState: Bundle?) {
        super.initView(view, savedInstanceState)
        binding.btn.title = getString(R.string.sleep_record)

        setupTimerController()
        setupToolbar()
        setupSaveButton()
        binding.etNote.doAfterTextChanged { if (!isProgrammaticChange) markAsUnsaved() }

        resolveEditMode()
        draftForView = (savedInstanceState?.getBundle(RecordDraftState.KEY) ?: retainedDraft)
            ?.takeIf { mainVm.navTarget.value is NavTarget.SleepRecord &&
                RecordDraftState.matches(it, mainVm.getCurrentBabyInfo()?.babyId, editRecordId) }
        draftBabyId = draftForView?.getInt("baby_id")?.takeIf { it > 0 }
            ?: mainVm.getCurrentBabyInfo()?.babyId
        draftId = draftForView?.getString("draft_id") ?: UUID.randomUUID().toString()
        if (draftForView == null) resetPage()
        if (isEditMode && draftForView?.getString("editing_record") == null) {
            loadEditRecord()
        }
        launchInLifecycle {
            mainVm.currentBaby.combine(mainVm.navTarget) { baby, target -> baby?.babyId to target }
                .collect { (babyId, target) ->
                    if (!isHidden && target is NavTarget.SleepRecord && babyId != null) {
                        val changed = (draftBabyId != null && draftBabyId != babyId) || editRecordId != target.editRecordId
                        if (draftBabyId == null) draftBabyId = babyId
                        if (changed) openCurrentTarget()
                    }
                }
        }
        observeMutation()
        // LiveData 自身绑定 View 生命周期；只注册一次，避免反复进入记录页累加观察者。
        vm.lastSleepRecord.observe(viewLifecycleOwner) { record ->
            if (record != null && record.babyId == currentDraftBabyId()) {
                binding.tvLastSleep.text = getString(
                    R.string.last_sleep_time,
                    DateUtils.timestampToMMddHHmm(record.sleepEnd)
                )
                binding.tvLastSleep.visibility = View.VISIBLE
            } else {
                binding.tvLastSleep.visibility = View.GONE
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (view != null && !isHidden) outState.putBundle(RecordDraftState.KEY, captureDraft())
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        draftForView?.let { draft ->
            isProgrammaticChange = true
            editingRecord = draft.getString("editing_record")?.let { Gson().fromJson(it, SleepRecord::class.java) }
                ?: editingRecord
            editRecordId = draft.getInt("edit_id").takeIf { it > 0 }
            isEditMode = editRecordId != null
            RecordDraftState.restoreHierarchy(binding.root, draft)
            draft.getBundle("timer")?.let { RecordDraftState.restoreTimer(binding.timerPanel, timerController, it) }
            hasUnsavedChanges = draft.getBoolean("dirty")
            isProgrammaticChange = false
            applyTimerLockState()
            updateToolbarAction()
        }
        draftForView = null
        retainedDraft = null
    }

    private fun captureDraft(): Bundle = Bundle().apply {
        putInt("edit_id", editRecordId ?: 0)
        putString("draft_id", draftId)
        putInt("baby_id", draftBabyId ?: mainVm.getCurrentBabyInfo()?.babyId ?: 0)
        putBoolean("dirty", hasUnsavedChanges)
        editingRecord?.let { putString("editing_record", Gson().toJson(it)) }
        putBundle("timer", RecordDraftState.saveTimer(binding.timerPanel, timerController))
        RecordDraftState.saveHierarchy(binding.root, this)
    }

    override fun onDestroyView() {
        retainedDraft = if (!isHidden) captureDraft() else null
        timerController?.dispose()
        timerController = null
        binding.timerPanel.timerView.release()
        super.onDestroyView()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        // View 未创建时不执行
        if (view == null) return
        
        if (hidden) {
            timerController?.dismissPendingUi()
            retainedDraft = null
            // 隐藏时不重置计时器（保留进行中状态）
        } else {
            openCurrentTarget()
        }
    }

    /** 同一个常驻页面也可能收到另一宝宝/编辑目标，必须先切换表单归属。 */
    private fun openCurrentTarget() {
        draftId = UUID.randomUUID().toString()
        draftBabyId = mainVm.getCurrentBabyInfo()?.babyId
        resolveEditMode()
        resetPage()
        if (isEditMode) {
            loadEditRecord()
        } else {
            draftBabyId?.takeIf(OngoingRecordManager::isSleeping)?.let(::restoreOngoingSleep)
            loadLastSleepRecord()
        }
    }

    private fun currentDraftBabyId(): Int? = mainVm.getCurrentBabyInfo()?.babyId
        ?.takeIf { draftBabyId == null || draftBabyId == it }

    /**
     * 恢复进行中的睡眠记录
     */
    private fun restoreOngoingSleep(babyId: Int) {
        val session = OngoingRecordManager.getSleepSession(babyId) ?: return
        val startTime = session.startAt
        
        isProgrammaticChange = true
        
        // 设置入睡时间
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
                mode = TimerMode.SLEEP,
                shouldIgnoreInput = { isProgrammaticChange }
            ),
            callbacks = RecordTimerController.Callbacks(
                onStartTimeChanged = { startTime ->
                    markAsUnsaved()
                    val babyId = currentDraftBabyId()
                    val sessionId = timerController?.ongoingSessionId
                    if (!isEditMode && babyId != null && sessionId != null && OngoingRecordManager.isSleeping(babyId)) {
                        // 同步进行中的开始时间，避免恢复与状态卡不一致
                        OngoingRecordManager.updateSleepStart(babyId, startTime, sessionId)
                    }
                },
                onEndTimeChanged = { markAsUnsaved() },
                onTimerStart = { startTime ->
                    if (!isEditMode) currentDraftBabyId()?.let { babyId ->
                        OngoingRecordManager.startSleep(
                            babyId, startTime,
                            automaticStart = timerController?.isStartTimeAutomatic == true
                        )
                        OngoingRecordManager.getSleepSession(babyId)?.let { session ->
                            timerController?.restoreAutomaticStart(session.automaticStart, session.clockAnchor, session.sessionId)
                        }
                    }
                },
                onTimerResume = {
                    markAsUnsaved()
                    val sessionId = timerController?.ongoingSessionId
                    if (!isEditMode && sessionId != null) currentDraftBabyId()?.let { babyId ->
                        OngoingRecordManager.resumeSleep(babyId, sessionId)
                    }
                },
                onTimerPause = { endTime ->
                    val sessionId = timerController?.ongoingSessionId
                    if (!isEditMode && sessionId != null) currentDraftBabyId()?.let { babyId ->
                        OngoingRecordManager.pauseSleep(
                            babyId = babyId,
                            endTime = endTime,
                            activeDurationMs = binding.timerPanel.timerView.getDuration(),
                            expectedSessionId = sessionId
                        )
                    }
                },
                onClockSynchronized = { sessionId, startTime, clock ->
                    // View 只转发当前表单事件，由 ViewModel 按会话身份更新持久状态。
                    if (!isEditMode) currentDraftBabyId()?.let { babyId ->
                        vm.synchronizeAutomaticStart(babyId, sessionId, startTime, clock)
                    }
                },
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
            saveSleepRecord()
        }
    }

    private fun resolveEditMode() {
        val navTarget = mainVm.navTarget.value as? NavTarget.SleepRecord
        editRecordId = navTarget?.editRecordId
        isEditMode = editRecordId != null
        if (!isEditMode || editingRecord?.sleepId != editRecordId) {
            editingRecord = null
        }
        applyTimerLockState()
        updateToolbarAction()
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
        val expectedView = view
        val expectedBabyId = draftBabyId
        val expectedDraftId = draftId
        vm.loadSleepRecordById(recordId) { record ->
            if (view !== expectedView || isHidden || editRecordId != recordId || draftId != expectedDraftId) return@loadSleepRecordById
            if (record == null || (expectedBabyId != null && record.babyId != expectedBabyId) ||
                (draftBabyId != null && record.babyId != draftBabyId)) {
                mainVm.navigateTo(getReturnTarget())
                return@loadSleepRecordById
            }
            draftBabyId = record.babyId
            editingRecord = record
            updateToolbarAction()
            // 等待数据库期间输入的草稿优先，只补齐保存编辑所需的原始记录身份。
            if (!hasUnsavedChanges) applyRecordToUi(record)
        }
    }

    private fun applyRecordToUi(record: SleepRecord) {
        isProgrammaticChange = true

        timerController?.setStartTime(record.sleepStart, notify = false)
        timerController?.setEndTime(record.sleepEnd, notify = false, updateDuration = true)
        binding.etNote.setText(record.note)

        hasUnsavedChanges = false
        isProgrammaticChange = false
    }

    /**
     * 保存睡眠记录
     */
    private fun saveSleepRecord() {
        if (vm.mutation.isPending || (isEditMode && editingRecord == null)) return
        val babyId = currentDraftBabyId()
        if (babyId == null) {
            ToastUtils.showShort(getString(R.string.no_baby_selected))
            return
        }
        if (isEditMode && editingRecord?.babyId != babyId) return
        clearValidationErrors()

        // 未完成的手动时间必须先纠正，不能让自动暂停填入“现在”覆盖用户输入。
        val startInput = binding.timerPanel.startInput
        if (!startInput.hasValidTime()) {
            showFieldValidationError(startInput,
                if (startInput.text.isNullOrBlank()) R.string.sleep_start_required else R.string.invalid_time)
            return
        }
        val endInput = binding.timerPanel.endInput
        if (!endInput.text.isNullOrBlank() && !endInput.hasValidTime()) {
            showFieldValidationError(endInput, R.string.invalid_time)
            return
        }

        // 如果正在计时，先暂停
        if (binding.timerPanel.timerView.currentShowState == RecordState.RECORDING) {
            binding.timerPanel.timerView.forcePause(triggerCallback = true)
            // 更新醒来时间
            binding.timerPanel.endInput.setTimestamp(System.currentTimeMillis())
        }

        val startTime = binding.timerPanel.startInput.getTimestamp()
        val endTime = binding.timerPanel.endInput.getTimestamp()

        // 校验
        if (startTime <= 0) {
            showFieldValidationError(binding.timerPanel.startInput, R.string.sleep_start_required)
            return
        }
        if (endTime <= 0) {
            showFieldValidationError(binding.timerPanel.endInput, R.string.sleep_end_required)
            return
        }
        if (endTime <= startTime) {
            showFieldValidationError(binding.timerPanel.endInput, R.string.sleep_end_must_after_start)
            return
        }

        // 睡眠不扣除暂停时间，最终时长始终按入睡到醒来的完整时间区间计算。
        val duration = TimerSessionPolicy.calculateSavedDuration(
            mode = TimerMode.SLEEP,
            startAt = startTime,
            endAt = endTime,
            activeDuration = binding.timerPanel.timerView.getDuration()
        ).valueMs

        val sleepRecord = SleepRecord(
            sleepId = editingRecord?.sleepId ?: 0,
            babyId = babyId,
            sleepStart = startTime,
            sleepEnd = endTime,
            sleepDuration = duration,
            note = binding.etNote.text.toString(),
            createdAt = editingRecord?.createdAt ?: System.currentTimeMillis()
        )

        vm.save(sleepRecord, mutationTarget(babyId))
    }

    /** 删除是记录纠错的一部分，成功后同步触发统计与预测刷新。 */
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
                    vm.delete(record.sleepId, target)
                }
            }
        )
    }

    private fun setMutationInProgress(inProgress: Boolean) {
        mutationInProgress = inProgress
        if (view == null) return
        binding.tvSave.isEnabled = !inProgress
        binding.tvSave.alpha = if (inProgress) 0.7f else 1f
        updateToolbarAction()
    }

    private fun mutationTarget(babyId: Int) = RecordMutationController.Target(
        draftId, babyId, editRecordId, mainVm.navTarget.value, getReturnTarget()
    )

    /** 生命周期重建后由新 View 消费结果，旧 Fragment 不再被保存协程持有。 */
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
                            R.string.record_delete_success else R.string.sleep_save_success
                        ToastUtils.showShort(getString(message))
                        if (state.errorRes == null) mainVm.navigateTo(state.target.destination)
                    }
                }
            }
        }
    }

    /** 错误与具体字段绑定，确保用户能立即定位并修正记录。 */
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
    }

    /**
     * 加载上次睡眠记录
     */
    private fun loadLastSleepRecord() {
        val babyId = currentDraftBabyId() ?: return
        vm.loadLastSleepRecord(babyId)
    }

    /**
     * 重置页面数据
     */
    private fun resetPage() {
        isProgrammaticChange = true
        
        // 清空时间输入与计时器
        timerController?.reset()
        
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
        if (!isProgrammaticChange) hasUnsavedChanges = true
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
                title = getString(R.string.tip),
                content = getString(R.string.unsaved_record_tip),
                confirmText = getString(R.string.confirm),
                cancelText = getString(R.string.cancel),
                onConfirm = {
                    hasUnsavedChanges = false
                    if (!isEditMode) {
                        currentDraftBabyId()?.let { babyId ->
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
