package com.zero.babycare.settings.backup

import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.blankj.utilcode.util.ToastUtils
import com.lxj.xpopup.core.BasePopupView
import com.lxj.xpopup.interfaces.SimpleCallback
import com.zero.babycare.MainViewModel
import com.zero.babycare.R
import com.zero.babycare.databinding.FragmentBackupBinding
import com.zero.babycare.navigation.BackPressHandler
import com.zero.babycare.navigation.NavTarget
import com.zero.common.ext.launchInLifecycle
import com.zero.components.base.BaseFragment
import com.zero.components.base.util.DialogHelper
import com.zero.components.base.vm.UiState
import com.zero.babydata.backup.BackupImportReport
import com.zero.babydata.backup.BackupImportStrategy

/**
 * 数据备份页面
 */
class BackupFragment : BaseFragment<FragmentBackupBinding>(), BackPressHandler {

    companion object {
        fun create(): BackupFragment = BackupFragment()
    }

    private val vm by viewModels<BackupViewModel>()
    private val mainVm by activityViewModels<MainViewModel>()
    private var importDialog: BasePopupView? = null
    private var displayedImportPromptId: String? = null
    private var importDialogEpoch = 0L

    private val exportRow get() = binding.rowBackupExport
    private val importRow get() = binding.rowBackupImport
    private val dedupRow get() = binding.rowBackupDedup
    private val reportClearRow get() = binding.rowBackupReportClear
    private val reportAdapter by lazy {
        BackupReportAdapter(
            onItemClick = { item -> handleReportClick(item) },
            onItemLongClick = { item -> handleReportLongClick(item) }
        )
    }

    private val createDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            vm.writePendingExport(uri)
        }

    private val openDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            handleOpenDocumentResult(uri)
        }

    override fun initView(view: View, savedInstanceState: Bundle?) {
        super.initView(view, savedInstanceState)
        setupToolbar()
        setupRows()
        setupReportList()
        observeState()
    }

    /**
     * 初始化工具栏
     */
    private fun setupToolbar() {
        binding.toolbar.title = getString(com.zero.common.R.string.settings_backup)
        binding.toolbar.showBackButton { handleBack() }
        binding.toolbar.hideAction()
    }

    /**
     * 初始化功能项
     */
    private fun setupRows() {
        bindGroupedRowBackgrounds()

        exportRow.tvTitle.text = getString(com.zero.common.R.string.backup_export_title)
        exportRow.tvSummary.text = getString(com.zero.common.R.string.backup_export_summary)
        exportRow.tvSummary.visibility = View.VISIBLE
        exportRow.tvValue.text = ""
        exportRow.root.setOnClickListener { vm.prepareExport() }

        importRow.tvTitle.text = getString(com.zero.common.R.string.backup_import_title)
        importRow.tvSummary.text = getString(com.zero.common.R.string.backup_import_summary)
        importRow.tvSummary.visibility = View.VISIBLE
        importRow.tvValue.text = ""
        importRow.root.setOnClickListener {
            openDocumentLauncher.launch(arrayOf("application/json", "text/plain"))
        }

        dedupRow.tvTitle.text = getString(com.zero.common.R.string.backup_dedup_title)
        dedupRow.tvSummary.text = getString(com.zero.common.R.string.backup_dedup_summary)
        dedupRow.tvSummary.visibility = View.VISIBLE
        dedupRow.tvValue.text = formatDedupMinutes(vm.dedupMinutes.value)
        dedupRow.root.setOnClickListener {
            showDedupPicker(vm.dedupMinutes.value)
        }
    }

    /**
     * 备份页沿用设置页分组行规则，让首尾按压态贴合外层圆角。
     */
    private fun bindGroupedRowBackgrounds() {
        exportRow.root.setBackgroundResource(R.drawable.bg_setting_item_top_ripple)
        importRow.root.setBackgroundResource(R.drawable.bg_setting_item_middle_ripple)
        dedupRow.root.setBackgroundResource(R.drawable.bg_setting_item_bottom_ripple)
        reportClearRow.root.setBackgroundResource(R.drawable.bg_setting_item_top_ripple)
    }

    /**
     * 初始化导入报告列表
     */
    private fun setupReportList() {
        binding.rvBackupReports.layoutManager = LinearLayoutManager(requireContext())
        binding.rvBackupReports.adapter = reportAdapter
        setupReportClearRow()
        vm.loadReportList()
    }

    private fun setupReportClearRow() {
        reportClearRow.tvTitle.text = getString(com.zero.common.R.string.backup_report_clear_title)
        reportClearRow.tvSummary.text = getString(com.zero.common.R.string.backup_report_clear_summary)
        reportClearRow.tvSummary.visibility = View.VISIBLE
        reportClearRow.tvValue.text = ""
        reportClearRow.root.setOnClickListener { showClearReportsConfirm() }
    }

    /**
     * 观察导出状态
     */
    private fun observeState() {
        launchInLifecycle {
            vm.exportState.collect { state ->
                when (state) {
                    is UiState.None -> Unit
                    is UiState.Loading -> showLoading(getString(com.zero.common.R.string.backup_exporting))
                    is UiState.Success -> {
                        hideLoading()
                        vm.resetExportState()
                        startCreateDocument(state.data)
                    }
                    is UiState.Error -> {
                        hideLoading()
                        ToastUtils.showShort(com.zero.common.R.string.backup_export_failed)
                        vm.resetExportState()
                    }
                }
            }
        }

        launchInLifecycle {
            vm.documentExportState.collect { state ->
                when (state) {
                    BackupDocumentExportState.Idle -> Unit
                    BackupDocumentExportState.Writing ->
                        showLoading(getString(com.zero.common.R.string.backup_exporting))
                    is BackupDocumentExportState.Finished -> {
                        hideLoading()
                        showDocumentExportResult(state.result)
                        vm.consumeDocumentExportResult()
                    }
                }
            }
        }

        viewLifecycleOwner.launchInLifecycle(minActiveState = Lifecycle.State.RESUMED) {
            vm.importPromptState.collect { renderImportPrompt() }
        }

        viewLifecycleOwner.launchInLifecycle(minActiveState = Lifecycle.State.RESUMED) {
            vm.importState.collect { renderImportResult() }
        }

        launchInLifecycle {
            vm.dedupMinutes.collect { minutes ->
                dedupRow.tvValue.text = formatDedupMinutes(minutes)
            }
        }

        launchInLifecycle {
            vm.reportSaveState.collect { state ->
                when (state) {
                    is UiState.None -> Unit
                    is UiState.Loading -> Unit
                    is UiState.Success -> {
                        ToastUtils.showShort(
                            getString(
                                com.zero.common.R.string.backup_import_report_saved,
                                state.data
                            )
                        )
                        vm.resetReportSaveState()
                        vm.loadReportList()
                    }
                    is UiState.Error -> {
                        ToastUtils.showShort(state.message)
                        vm.resetReportSaveState()
                    }
                }
            }
        }

        launchInLifecycle {
            vm.reportListState.collect { state ->
                when (state) {
                    is UiState.None -> Unit
                    is UiState.Loading -> Unit
                    is UiState.Success -> {
                        updateReportList(state.data ?: emptyList())
                        vm.resetReportListState()
                    }
                    is UiState.Error -> {
                        ToastUtils.showShort(state.message)
                        vm.resetReportListState()
                    }
                }
            }
        }

        launchInLifecycle {
            vm.reportLoadState.collect { state ->
                when (state) {
                    is UiState.None -> Unit
                    is UiState.Loading -> Unit
                    is UiState.Success -> {
                        vm.resetReportLoadState()
                        showReportDetail(state.data)
                    }
                    is UiState.Error -> {
                        ToastUtils.showShort(state.message)
                        vm.resetReportLoadState()
                    }
                }
            }
        }

        launchInLifecycle {
            vm.reportExportState.collect { state ->
                when (state) {
                    is UiState.None -> Unit
                    is UiState.Loading -> showLoading(getString(com.zero.common.R.string.backup_exporting))
                    is UiState.Success -> {
                        hideLoading()
                        vm.resetReportExportState()
                        startCreateDocument(state.data)
                    }
                    is UiState.Error -> {
                        hideLoading()
                        ToastUtils.showShort(state.message)
                        vm.resetReportExportState()
                    }
                }
            }
        }

        launchInLifecycle {
            vm.reportDeleteState.collect { state ->
                when (state) {
                    is UiState.None -> Unit
                    is UiState.Loading -> Unit
                    is UiState.Success -> {
                        ToastUtils.showShort(
                            getString(
                                com.zero.common.R.string.backup_report_deleted_format,
                                state.data
                            )
                        )
                        vm.resetReportDeleteState()
                        vm.loadReportList()
                    }
                    is UiState.Error -> {
                        ToastUtils.showShort(state.message)
                        vm.resetReportDeleteState()
                    }
                }
            }
        }

        launchInLifecycle {
            vm.reportClearState.collect { state ->
                when (state) {
                    is UiState.None -> Unit
                    is UiState.Loading -> Unit
                    is UiState.Success -> {
                        ToastUtils.showShort(
                            getString(
                                com.zero.common.R.string.backup_report_cleared_format,
                                state.data ?: 0
                            )
                        )
                        vm.resetReportClearState()
                        vm.loadReportList()
                    }
                    is UiState.Error -> {
                        ToastUtils.showShort(state.message)
                        vm.resetReportClearState()
                    }
                }
            }
        }
    }

    /**
     * 发起文件创建流程
     */
    private fun startCreateDocument(request: BackupExportRequest?) {
        if (request == null) {
            vm.onExportPickerUnavailable()
            return
        }
        try {
            createDocumentLauncher.launch(request.fileName)
        } catch (_: Exception) {
            vm.onExportPickerUnavailable()
        }
    }

    /** 文件读写结果由 VM 保留，页面恢复后只负责反馈，不持有正在进行的 IO。 */
    private fun showDocumentExportResult(result: BackupDocumentExportResult) {
        when (result.outcome) {
            BackupExportOutcome.CANCELLED -> ToastUtils.showShort(com.zero.common.R.string.backup_export_cancelled)
            BackupExportOutcome.FAILED -> ToastUtils.showShort(
                if (result.isReport) com.zero.common.R.string.backup_import_report_export_failed
                else com.zero.common.R.string.backup_export_failed
            )
            BackupExportOutcome.SUCCEEDED -> {
                ToastUtils.showShort(
                    if (result.isReport) com.zero.common.R.string.backup_import_report_exported
                    else com.zero.common.R.string.backup_export_success
                )
                showExportHint(
                    result.fileName,
                    if (result.isReport) com.zero.common.R.string.backup_import_report_export_hint_format
                    else com.zero.common.R.string.backup_export_hint_format
                )
            }
        }
    }

    /**
     * 处理导入文件选择
     */
    private fun handleOpenDocumentResult(uri: Uri?) {
        if (uri == null) {
            ToastUtils.showShort(com.zero.common.R.string.backup_import_cancelled)
            return
        }
        vm.prepareImport(uri)
    }

    /** 弹窗只是 VM 状态的渲染；隐藏页面和非前台页面不得显示或消费确认。 */
    private fun canShowImportUi(): Boolean = isAdded && view != null && isResumed && !isHidden &&
        mainVm.navTarget.value is NavTarget.Backup

    private fun renderImportPrompt() {
        if (!canShowImportUi()) return
        when (val state = vm.importPromptState.value) {
            UiState.None -> dismissImportDialog()
            UiState.Loading -> {
                dismissImportDialog()
                showLoading(getString(com.zero.common.R.string.backup_import_parsing))
            }
            is UiState.Error -> {
                dismissImportDialog()
                hideLoading()
                ToastUtils.showShort(state.message)
                vm.consumeImportPromptError()
            }
            is UiState.Success -> {
                hideLoading()
                val prompt = state.data ?: return
                if (displayedImportPromptId == prompt.id) return
                dismissImportDialog()
                displayedImportPromptId = prompt.id
                val epoch = importDialogEpoch
                importDialog = when (prompt.step) {
                    BackupImportPrompt.Step.SELECT_MODE -> showImportModePicker(prompt, epoch)
                    BackupImportPrompt.Step.CONFIRM -> showImportConfirm(prompt, epoch)
                }.also { popup ->
                    popup.popupInfo.xPopupCallback = object : SimpleCallback() {
                        override fun onDismiss(popupView: BasePopupView?) {
                            // 生命周期清理先递增世代，旧窗口延迟回调不会取消重建后的同一提示。
                            if (importDialogEpoch != epoch || displayedImportPromptId != prompt.id) return
                            importDialog = null
                            displayedImportPromptId = null
                            if (canShowImportUi()) vm.cancelImport(prompt.id)
                        }
                    }
                }
            }
        }
    }

    private fun renderImportResult() {
        if (!canShowImportUi()) return
        when (val state = vm.importState.value) {
            UiState.None -> Unit
            UiState.Loading -> showLoading(getString(com.zero.common.R.string.backup_importing))
            is UiState.Success -> {
                hideLoading()
                vm.resetImportState()
                showImportReport(state.data)
            }
            is UiState.Error -> {
                hideLoading()
                ToastUtils.showShort(state.message)
                vm.resetImportState()
            }
        }
    }

    private fun acceptsImportCallback(prompt: BackupImportPrompt, epoch: Long): Boolean =
        importDialogEpoch == epoch && displayedImportPromptId == prompt.id && canShowImportUi()

    /** 合并在首位并默认选中，覆盖必须经过明确选择和第二次确认。 */
    private fun showImportModePicker(prompt: BackupImportPrompt, epoch: Long): BasePopupView =
        DialogHelper.showChoiceSheet(
            context = requireContext(),
            title = getString(com.zero.common.R.string.backup_import_confirm_title),
            options = listOf(BackupImportMode.MERGE, BackupImportMode.OVERWRITE).map {
                DialogHelper.PickerOption(
                    value = it,
                    label = getString(it.labelResId)
                )
            },
            selectedValue = prompt.mode,
            onSelected = { mode ->
                if (acceptsImportCallback(prompt, epoch)) vm.selectImportMode(prompt.id, mode)
            },
            onCancel = {
                if (acceptsImportCallback(prompt, epoch)) {
                    vm.cancelImport(prompt.id)
                    ToastUtils.showShort(com.zero.common.R.string.backup_import_cancelled)
                }
            }
        )

    /**
     * 导入确认弹窗
     */
    private fun showImportConfirm(prompt: BackupImportPrompt, epoch: Long): BasePopupView {
        val mode = prompt.mode
        val preview = prompt.preview
        val modeLabel = getString(mode.labelResId)
        val contentResId = when (mode) {
            BackupImportMode.OVERWRITE -> com.zero.common.R.string.backup_import_confirm_overwrite_format
            BackupImportMode.MERGE -> com.zero.common.R.string.backup_import_confirm_merge_format
        }
        val content = getString(
            contentResId,
            modeLabel,
            preview.babyCount,
            preview.feedingCount,
            preview.sleepCount,
            preview.eventCount,
            preview.childDailyCount
        )
        return DialogHelper.showConfirmDialog(
            context = requireContext(),
            title = getString(com.zero.common.R.string.backup_import_confirm_title),
            content = content,
            confirmText = getString(com.zero.common.R.string.confirm),
            cancelText = getString(com.zero.common.R.string.cancel),
            onConfirm = {
                if (acceptsImportCallback(prompt, epoch)) vm.confirmImport(prompt.id)
            },
            onCancel = { if (acceptsImportCallback(prompt, epoch)) vm.cancelImport(prompt.id) }
        )
    }

    /**
     * 导入完成报告
     */
    private fun showImportReport(report: BackupImportReport?) {
        if (report == null) {
            ToastUtils.showShort(com.zero.common.R.string.backup_import_success)
            return
        }
        val modeLabel = when (report.strategy) {
            BackupImportStrategy.OVERWRITE -> getString(com.zero.common.R.string.backup_import_mode_overwrite)
            BackupImportStrategy.MERGE -> getString(com.zero.common.R.string.backup_import_mode_merge)
        }
        val content = getString(
            com.zero.common.R.string.backup_import_report_format,
            modeLabel,
            report.babyInserted,
            report.babyMatched,
            report.babyDuplicateInBackup,
            report.feedingInserted,
            report.feedingSkipped,
            report.sleepInserted,
            report.sleepSkipped,
            report.eventInserted,
            report.eventSkipped,
            report.childDailyInserted,
            report.childDailyUpdated
        )
        DialogHelper.showAlertDialog(
            context = requireContext(),
            title = getString(com.zero.common.R.string.backup_import_report_title),
            content = content
        )
        vm.saveImportReport(report)
    }

    private fun handleReportClick(item: BackupReportItem) {
        vm.loadReport(item)
    }

    private fun handleReportLongClick(item: BackupReportItem) {
        val content = getString(
            com.zero.common.R.string.backup_report_delete_confirm_format,
            item.fileName
        )
        DialogHelper.showConfirmDialog(
            context = requireContext(),
            title = getString(com.zero.common.R.string.backup_report_delete_title),
            content = content,
            confirmText = getString(com.zero.common.R.string.confirm),
            cancelText = getString(com.zero.common.R.string.cancel),
            onConfirm = { vm.deleteReport(item) }
        )
    }

    private fun showClearReportsConfirm() {
        DialogHelper.showConfirmDialog(
            context = requireContext(),
            title = getString(com.zero.common.R.string.backup_report_clear_title),
            content = getString(com.zero.common.R.string.backup_report_clear_confirm),
            confirmText = getString(com.zero.common.R.string.confirm),
            cancelText = getString(com.zero.common.R.string.cancel),
            onConfirm = { vm.clearReports() }
        )
    }

    private fun updateReportList(list: List<BackupReportItem>) {
        reportAdapter.items = list
        val isEmpty = list.isEmpty()
        binding.rvBackupReports.visibility = if (isEmpty) View.GONE else View.VISIBLE
        binding.tvReportEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
    }

    private fun showReportDetail(item: BackupReportItem?) {
        if (item?.report == null) {
            ToastUtils.showShort(com.zero.common.R.string.backup_import_report_load_failed)
            return
        }
        val report = item.report
        val modeLabel = when (report.strategy) {
            com.zero.babydata.backup.BackupImportStrategy.OVERWRITE ->
                getString(com.zero.common.R.string.backup_import_mode_overwrite)
            com.zero.babydata.backup.BackupImportStrategy.MERGE ->
                getString(com.zero.common.R.string.backup_import_mode_merge)
        }
        val content = getString(
            com.zero.common.R.string.backup_import_report_format,
            modeLabel,
            report.babyInserted,
            report.babyMatched,
            report.babyDuplicateInBackup,
            report.feedingInserted,
            report.feedingSkipped,
            report.sleepInserted,
            report.sleepSkipped,
            report.eventInserted,
            report.eventSkipped,
            report.childDailyInserted,
            report.childDailyUpdated
        )
        DialogHelper.showConfirmDialog(
            context = requireContext(),
            title = getString(com.zero.common.R.string.backup_import_report_detail_title),
            content = content,
            confirmText = getString(com.zero.common.R.string.backup_import_report_export),
            cancelText = getString(com.zero.common.R.string.cancel),
            onConfirm = { vm.prepareReportExport(item) },
            onCancel = {}
        )
    }

    private fun showExportHint(fileName: String, hintResId: Int) {
        DialogHelper.showAlertDialog(
            context = requireContext(),
            title = getString(com.zero.common.R.string.tip),
            content = getString(hintResId, fileName)
        )
    }

    /**
     * 去重分钟选择
     */
    private fun showDedupPicker(current: Int) {
        val options = vm.getAllowedDedupMinutes()
        DialogHelper.showChoiceSheet(
            context = requireContext(),
            title = getString(com.zero.common.R.string.backup_dedup_title),
            options = options.map {
                DialogHelper.PickerOption(
                    value = it,
                    label = formatDedupMinutes(it)
                )
            },
            selectedValue = current,
            onSelected = { vm.setDedupMinutes(it) }
        )
    }

    /**
     * 去重分钟文案
     */
    private fun formatDedupMinutes(minutes: Int): String {
        return if (minutes <= 0) {
            getString(com.zero.common.R.string.backup_dedup_off)
        } else {
            getString(com.zero.common.R.string.backup_dedup_value_format, minutes)
        }
    }

    override fun onSystemBackPressed(): Boolean {
        handleBack()
        return true
    }

    override fun onDestroyView() {
        dismissImportDialog()
        binding.rvBackupReports.adapter = null
        super.onDestroyView()
    }

    override fun onPause() {
        // Activity 重建、系统选择器和离开前台都只撤下窗口，不取消用户正在确认的导入。
        dismissImportDialog()
        hideLoading()
        super.onPause()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) {
            dismissImportDialog()
            hideLoading()
        } else {
            renderImportPrompt()
            renderImportResult()
        }
    }

    private fun dismissImportDialog() {
        importDialogEpoch++
        displayedImportPromptId = null
        val popup = importDialog
        importDialog = null
        popup?.dismiss()
    }

    private fun handleBack() {
        val returnTarget = (mainVm.navTarget.value as? NavTarget.Backup)?.returnTarget ?: NavTarget.Settings()
        mainVm.navigateTo(returnTarget)
    }
}
