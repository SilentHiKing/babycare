package com.zero.babycare.settings.backup

import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.blankj.utilcode.util.StringUtils
import com.blankj.utilcode.util.ToastUtils
import com.lxj.xpopup.XPopup
import com.lxj.xpopupext.listener.CommonPickerListener
import com.lxj.xpopupext.popup.CommonPickerPopup
import com.zero.babycare.MainViewModel
import com.zero.babycare.databinding.FragmentBackupBinding
import com.zero.babycare.navigation.BackPressHandler
import com.zero.babycare.navigation.NavTarget
import com.zero.common.ext.launchInLifecycle
import com.zero.common.mmkv.MMKVKeys
import com.zero.common.mmkv.MMKVStore
import com.zero.components.base.BaseFragment
import com.zero.components.base.util.DialogHelper
import com.zero.components.base.vm.UiState
import com.zero.babydata.backup.BackupImportReport
import com.zero.babydata.backup.BackupImportStrategy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 数据备份页面
 */
class BackupFragment : BaseFragment<FragmentBackupBinding>(), BackPressHandler {

    companion object {
        fun create(): BackupFragment = BackupFragment()
    }

    private val vm by viewModels<BackupViewModel>()
    private val mainVm by activityViewModels<MainViewModel>()

    private val exportRow by lazy { binding.rowBackupExport }
    private val importRow by lazy { binding.rowBackupImport }
    private val dedupRow by lazy { binding.rowBackupDedup }
    private val reportClearRow by lazy { binding.rowBackupReportClear }
    private val reportAdapter by lazy {
        BackupReportAdapter(
            onItemClick = { item -> handleReportClick(item) },
            onItemLongClick = { item -> handleReportLongClick(item) }
        )
    }

    private var pendingPayload: BackupPayload? = null

    private val createDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            handleCreateDocumentResult(uri)
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
        binding.toolbar.title = StringUtils.getString(com.zero.common.R.string.settings_backup)
        binding.toolbar.showBackButton { handleBack() }
        binding.toolbar.hideAction()
    }

    /**
     * 初始化功能项
     */
    private fun setupRows() {
        exportRow.tvTitle.text = StringUtils.getString(com.zero.common.R.string.backup_export_title)
        exportRow.tvSummary.text = StringUtils.getString(com.zero.common.R.string.backup_export_summary)
        exportRow.tvSummary.visibility = View.VISIBLE
        exportRow.tvValue.text = ""
        exportRow.root.setOnClickListener { vm.prepareExport() }

        importRow.tvTitle.text = StringUtils.getString(com.zero.common.R.string.backup_import_title)
        importRow.tvSummary.text = StringUtils.getString(com.zero.common.R.string.backup_import_summary)
        importRow.tvSummary.visibility = View.VISIBLE
        importRow.tvValue.text = ""
        importRow.root.setOnClickListener {
            openDocumentLauncher.launch(arrayOf("application/json", "text/plain"))
        }

        dedupRow.tvTitle.text = StringUtils.getString(com.zero.common.R.string.backup_dedup_title)
        dedupRow.tvSummary.text = StringUtils.getString(com.zero.common.R.string.backup_dedup_summary)
        dedupRow.tvSummary.visibility = View.VISIBLE
        dedupRow.tvValue.text = formatDedupMinutes(vm.dedupMinutes.value)
        dedupRow.root.setOnClickListener {
            showDedupPicker(vm.dedupMinutes.value)
        }
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
        reportClearRow.tvTitle.text = StringUtils.getString(com.zero.common.R.string.backup_report_clear_title)
        reportClearRow.tvSummary.text = StringUtils.getString(com.zero.common.R.string.backup_report_clear_summary)
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
                    is UiState.Loading -> showLoading(StringUtils.getString(com.zero.common.R.string.backup_exporting))
                    is UiState.Success -> {
                        hideLoading()
                        startCreateDocument(state.data)
                        vm.resetExportState()
                    }
                    is UiState.Error -> {
                        hideLoading()
                        ToastUtils.showShort(com.zero.common.R.string.backup_export_failed)
                    }
                }
            }
        }

        launchInLifecycle {
            vm.importPreviewState.collect { state ->
                when (state) {
                    is UiState.None -> Unit
                    is UiState.Loading -> showLoading(StringUtils.getString(com.zero.common.R.string.backup_import_parsing))
                    is UiState.Success -> {
                        hideLoading()
                        vm.resetImportPreviewState()
                        showImportModePicker(state.data)
                    }
                    is UiState.Error -> {
                        hideLoading()
                        ToastUtils.showShort(state.message)
                        vm.resetImportPreviewState()
                    }
                }
            }
        }

        launchInLifecycle {
            vm.importState.collect { state ->
                when (state) {
                    is UiState.None -> Unit
                    is UiState.Loading -> showLoading(StringUtils.getString(com.zero.common.R.string.backup_importing))
                    is UiState.Success -> {
                        hideLoading()
                        vm.resetImportState()
                        MMKVStore.remove(MMKVKeys.BABY_INFO)
                        showImportReport(state.data)
                    }
                    is UiState.Error -> {
                        hideLoading()
                        ToastUtils.showShort(state.message)
                        vm.resetImportState()
                    }
                }
            }
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
                            StringUtils.getString(
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
                    is UiState.Loading -> Unit
                    is UiState.Success -> {
                        vm.resetReportExportState()
                        startCreateReportDocument(state.data)
                    }
                    is UiState.Error -> {
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
                            StringUtils.getString(
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
                            StringUtils.getString(
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
    private fun startCreateDocument(payload: BackupPayload?) {
        if (payload == null) {
            ToastUtils.showShort(com.zero.common.R.string.backup_export_failed)
            return
        }
        pendingPayload = payload
        createDocumentLauncher.launch(payload.fileName)
    }

    private fun startCreateReportDocument(payload: BackupReportExportPayload?) {
        if (payload == null) {
            ToastUtils.showShort(com.zero.common.R.string.backup_import_report_export_failed)
            return
        }
        pendingPayload = BackupPayload(payload.fileName, payload.json)
        createDocumentLauncher.launch(payload.fileName)
    }

    /**
     * 处理文件创建结果
     */
    private fun handleCreateDocumentResult(uri: Uri?) {
        val payload = pendingPayload
        pendingPayload = null
        if (uri == null || payload == null) {
            ToastUtils.showShort(com.zero.common.R.string.backup_export_cancelled)
            return
        }
        val successTextResId = if (payload.fileName.contains("import_report")) {
            com.zero.common.R.string.backup_import_report_exported
        } else {
            com.zero.common.R.string.backup_export_success
        }
        val hintResId = if (payload.fileName.contains("import_report")) {
            com.zero.common.R.string.backup_import_report_export_hint_format
        } else {
            com.zero.common.R.string.backup_export_hint_format
        }
        writeBackupToUri(uri, payload.json, payload.fileName, successTextResId, hintResId)
    }

    /**
     * 处理导入文件选择
     */
    private fun handleOpenDocumentResult(uri: Uri?) {
        if (uri == null) {
            ToastUtils.showShort(com.zero.common.R.string.backup_import_cancelled)
            return
        }
        lifecycleScope.launch {
            val json = withContext(Dispatchers.IO) {
                try {
                    requireContext().contentResolver.openInputStream(uri)?.use { stream ->
                        stream.bufferedReader().use { it.readText() }
                    }
                } catch (_: Exception) {
                    null
                }
            }
            if (json.isNullOrBlank()) {
                ToastUtils.showShort(com.zero.common.R.string.backup_import_invalid_file)
            } else {
                vm.prepareImport(json)
            }
        }
    }

    /**
     * 将备份内容写入目标 Uri
     */
    private fun writeBackupToUri(
        uri: Uri,
        json: String,
        fileName: String,
        successTextResId: Int,
        hintResId: Int
    ) {
        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                try {
                    requireContext().contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(json.toByteArray(Charsets.UTF_8))
                    } ?: return@withContext false
                    true
                } catch (_: Exception) {
                    false
                }
            }
            if (success) {
                ToastUtils.showShort(successTextResId)
                showExportHint(fileName, hintResId)
            } else {
                ToastUtils.showShort(com.zero.common.R.string.backup_export_failed)
            }
        }
    }

    /**
     * 选择导入方式
     */
    private fun showImportModePicker(preview: BackupImportPreview?) {
        if (preview == null) {
            ToastUtils.showShort(com.zero.common.R.string.backup_import_failed)
            return
        }
        val modes = BackupImportMode.values().toList()
        val labels = ArrayList<String?>()
        modes.forEach { mode ->
            labels.add(StringUtils.getString(mode.labelResId))
        }

        val popup = CommonPickerPopup(requireContext())
        popup.setPickerData(labels)
        popup.setCommonPickerListener(object : CommonPickerListener {
            override fun onItemSelected(index: Int, data: String?) {
                val mode = modes.getOrNull(index) ?: BackupImportMode.OVERWRITE
                showImportConfirm(mode, preview)
            }

            override fun onCancel() {
                vm.clearPendingImport()
                ToastUtils.showShort(com.zero.common.R.string.backup_import_cancelled)
            }
        })

        XPopup.Builder(requireContext())
            .asCustom(popup)
            .show()
    }

    /**
     * 导入确认弹窗
     */
    private fun showImportConfirm(mode: BackupImportMode, preview: BackupImportPreview) {
        val modeLabel = StringUtils.getString(mode.labelResId)
        val contentResId = when (mode) {
            BackupImportMode.OVERWRITE -> com.zero.common.R.string.backup_import_confirm_overwrite_format
            BackupImportMode.MERGE -> com.zero.common.R.string.backup_import_confirm_merge_format
        }
        val content = StringUtils.getString(
            contentResId,
            modeLabel,
            preview.babyCount,
            preview.feedingCount,
            preview.sleepCount,
            preview.eventCount,
            preview.childDailyCount
        )
        DialogHelper.showConfirmDialog(
            context = requireContext(),
            title = StringUtils.getString(com.zero.common.R.string.backup_import_confirm_title),
            content = content,
            confirmText = StringUtils.getString(com.zero.common.R.string.confirm),
            cancelText = StringUtils.getString(com.zero.common.R.string.cancel),
            onConfirm = {
                when (mode) {
                    BackupImportMode.OVERWRITE -> vm.executeImportOverwrite()
                    BackupImportMode.MERGE -> vm.executeImportMerge()
                }
            },
            onCancel = { vm.clearPendingImport() }
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
            BackupImportStrategy.OVERWRITE -> StringUtils.getString(com.zero.common.R.string.backup_import_mode_overwrite)
            BackupImportStrategy.MERGE -> StringUtils.getString(com.zero.common.R.string.backup_import_mode_merge)
        }
        val content = StringUtils.getString(
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
            title = StringUtils.getString(com.zero.common.R.string.backup_import_report_title),
            content = content
        )
        vm.saveImportReport(report)
    }

    private fun handleReportClick(item: BackupReportItem) {
        vm.loadReport(item)
    }

    private fun handleReportLongClick(item: BackupReportItem) {
        val content = StringUtils.getString(
            com.zero.common.R.string.backup_report_delete_confirm_format,
            item.fileName
        )
        DialogHelper.showConfirmDialog(
            context = requireContext(),
            title = StringUtils.getString(com.zero.common.R.string.backup_report_delete_title),
            content = content,
            confirmText = StringUtils.getString(com.zero.common.R.string.confirm),
            cancelText = StringUtils.getString(com.zero.common.R.string.cancel),
            onConfirm = { vm.deleteReport(item) }
        )
    }

    private fun showClearReportsConfirm() {
        DialogHelper.showConfirmDialog(
            context = requireContext(),
            title = StringUtils.getString(com.zero.common.R.string.backup_report_clear_title),
            content = StringUtils.getString(com.zero.common.R.string.backup_report_clear_confirm),
            confirmText = StringUtils.getString(com.zero.common.R.string.confirm),
            cancelText = StringUtils.getString(com.zero.common.R.string.cancel),
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
                StringUtils.getString(com.zero.common.R.string.backup_import_mode_overwrite)
            com.zero.babydata.backup.BackupImportStrategy.MERGE ->
                StringUtils.getString(com.zero.common.R.string.backup_import_mode_merge)
        }
        val content = StringUtils.getString(
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
            title = StringUtils.getString(com.zero.common.R.string.backup_import_report_detail_title),
            content = content,
            confirmText = StringUtils.getString(com.zero.common.R.string.backup_import_report_export),
            cancelText = StringUtils.getString(com.zero.common.R.string.cancel),
            onConfirm = { vm.prepareReportExport(item) },
            onCancel = {}
        )
    }

    private fun showExportHint(fileName: String, hintResId: Int) {
        DialogHelper.showAlertDialog(
            context = requireContext(),
            title = StringUtils.getString(com.zero.common.R.string.tip),
            content = StringUtils.getString(hintResId, fileName)
        )
    }

    /**
     * 去重分钟选择
     */
    private fun showDedupPicker(current: Int) {
        val options = vm.getAllowedDedupMinutes()
        val labels = ArrayList<String?>()
        options.forEach { minutes ->
            labels.add(formatDedupMinutes(minutes))
        }

        val popup = CommonPickerPopup(requireContext())
        popup.setPickerData(labels)
        popup.setCommonPickerListener(object : CommonPickerListener {
            override fun onItemSelected(index: Int, data: String?) {
                val selected = options.getOrNull(index) ?: current
                vm.setDedupMinutes(selected)
            }

            override fun onCancel() {}
        })

        XPopup.Builder(requireContext())
            .asCustom(popup)
            .show()
    }

    /**
     * 去重分钟文案
     */
    private fun formatDedupMinutes(minutes: Int): String {
        return if (minutes <= 0) {
            StringUtils.getString(com.zero.common.R.string.backup_dedup_off)
        } else {
            StringUtils.getString(com.zero.common.R.string.backup_dedup_value_format, minutes)
        }
    }

    override fun onSystemBackPressed(): Boolean {
        handleBack()
        return true
    }

    private fun handleBack() {
        val returnTarget = (mainVm.navTarget.value as? NavTarget.Backup)?.returnTarget ?: NavTarget.Settings()
        mainVm.navigateTo(returnTarget)
    }
}
