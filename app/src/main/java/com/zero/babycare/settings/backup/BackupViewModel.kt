package com.zero.babycare.settings.backup

import android.app.Application
import com.zero.common.ext.getAppString
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.blankj.utilcode.util.AppUtils
import com.blankj.utilcode.util.LogUtils
import com.zero.babydata.backup.BackupImportError
import com.zero.babydata.backup.BackupImportException
import com.zero.babydata.backup.CommittedBackupImport
import com.zero.babydata.backup.BabyBackupEnvelope
import com.zero.babydata.backup.BabyBackupExporter
import com.zero.components.base.vm.UiState
import com.zero.common.mmkv.MMKVKeys
import com.zero.common.mmkv.MMKVStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 备份导出 ViewModel
 */
class BackupViewModel internal constructor(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
    private val importOperations: BackupImportOperations,
    reportStore: BackupReportStore? = null
) : AndroidViewModel(application) {

    constructor(application: Application, savedStateHandle: SavedStateHandle) :
        this(application, savedStateHandle, ApplicationBackupImportOperations(application))

    private val exporter by lazy { BabyBackupExporter(application) }
    private val reportStore by lazy {
        reportStore ?: FileBackupReportStore(File(application.filesDir, "backup_reports"), ::reportDisplayName)
    }
    val reportPrompt: StateFlow<BackupReportPrompt?> = savedStateHandle.getStateFlow(REPORT_PROMPT_KEY, null)
    private var reportListGeneration = 0L
    private var reportLoadGeneration = 0L

    private val _exportState = MutableStateFlow<UiState<BackupExportRequest>>(UiState.None)
    val exportState: StateFlow<UiState<BackupExportRequest>> = _exportState.asStateFlow()

    private val _importPromptState = MutableStateFlow<UiState<BackupImportPrompt>>(UiState.None)
    val importPromptState: StateFlow<UiState<BackupImportPrompt>> = _importPromptState.asStateFlow()

    private val _importState = MutableStateFlow<UiState<CommittedBackupImport>>(UiState.None)
    val importState: StateFlow<UiState<CommittedBackupImport>> = _importState.asStateFlow()

    private val _reportSaveState = MutableStateFlow<UiState<String>>(UiState.None)
    val reportSaveState: StateFlow<UiState<String>> = _reportSaveState.asStateFlow()

    private val _reportListState = MutableStateFlow<UiState<List<BackupReportItem>>>(UiState.None)
    val reportListState: StateFlow<UiState<List<BackupReportItem>>> = _reportListState.asStateFlow()

    private val _reportLoadState = MutableStateFlow<UiState<BackupReportItem>>(UiState.None)
    val reportLoadState: StateFlow<UiState<BackupReportItem>> = _reportLoadState.asStateFlow()

    private val _reportExportState = MutableStateFlow<UiState<BackupExportRequest>>(UiState.None)
    val reportExportState: StateFlow<UiState<BackupExportRequest>> = _reportExportState.asStateFlow()

    private val _reportDeleteState = MutableStateFlow<UiState<String>>(UiState.None)
    val reportDeleteState: StateFlow<UiState<String>> = _reportDeleteState.asStateFlow()

    private val _reportClearState = MutableStateFlow<UiState<Int>>(UiState.None)
    val reportClearState: StateFlow<UiState<Int>> = _reportClearState.asStateFlow()

    private val _dedupMinutes = MutableStateFlow(loadDedupMinutes())
    val dedupMinutes: StateFlow<Int> = _dedupMinutes.asStateFlow()

    private var pendingImport: BabyBackupEnvelope? = null
    private var pendingImportRelease: Job? = null
    private var restoringImportResult = true
    private var activeImportCommitId: String? = null
    private var consumingImportResult = false

    // noBackupFilesDir 不会进入系统云备份，也不会像 cacheDir 一样被系统提前清理。
    private val exportStore by lazy {
        BackupExportStore(File(application.noBackupFilesDir, "pending_backup_exports"))
    }
    private val _documentExportState = MutableStateFlow<BackupDocumentExportState>(
        savedStateHandle.get<BackupDocumentExportResult>(EXPORT_RESULT_KEY)
            ?.let { BackupDocumentExportState.Finished(it) } ?: BackupDocumentExportState.Idle
    )
    val documentExportState: StateFlow<BackupDocumentExportState> = _documentExportState.asStateFlow()

    init {
        reportPrompt.value?.takeIf { it.action == BackupReportPrompt.Action.DETAIL }?.let(::readReportPrompt)
        // 尚未打开选择器的导出在冷恢复后仍待用户发起；已打开的请求交 ActivityResult 恢复。
        savedStateHandle.get<BackupExportRequest>(PENDING_EXPORT_KEY)?.takeIf {
            savedStateHandle.get<Boolean>(EXPORT_PICKER_LAUNCHED_KEY) != true &&
                savedStateHandle.get<String>(EXPORT_URI_KEY) == null &&
                _documentExportState.value is BackupDocumentExportState.Idle
        }?.let { if (it.isReport) _reportExportState.value = UiState.Success(it) else _exportState.value = UiState.Success(it) }
        // 若系统在写出期间回收进程，恢复后从私有正文重写目标；目标使用截断模式避免重复追加。
        savedStateHandle.get<String>(EXPORT_URI_KEY)?.takeIf {
            _documentExportState.value is BackupDocumentExportState.Idle
        }?.let { writePendingExport(Uri.parse(it)) }
        viewModelScope.launch {
            try {
                val committed = withContext(Dispatchers.IO) { importOperations.recoverReport() }
                restoringImportResult = false
                if (committed != null) {
                    // 已提交收据优先于系统保存的旧 URI：恢复结果，不重放写入也不重复要求确认。
                    clearImportSource()
                    activeImportCommitId = committed.commitId
                    _importState.value = UiState.Success(committed)
                } else {
                    savedStateHandle.get<String>(PENDING_IMPORT_URI_KEY)?.let { prepareImport(Uri.parse(it)) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                restoringImportResult = false
                _importState.value = UiState.Error(error, messageRes = com.zero.common.R.string.backup_import_report_load_failed)
            }
        }
    }

    /**
     * 允许的去重分钟配置
     * 0 表示仅在完全一致时才去重
     */
    fun getAllowedDedupMinutes(): List<Int> {
        return listOf(0, 1, 5, 10)
    }

    /**
     * 生成备份 JSON 并返回可保存的文件名
     */
    fun prepareExport() {
        if (isPreparingOrWritingExport()) return
        viewModelScope.launch {
            _exportState.value = UiState.Loading
            try {
                val payload = withContext(Dispatchers.IO) {
                    val envelope = buildEnvelope()
                    val json = exporter.toJson(envelope)
                    BackupPayload(
                        fileName = buildFileName(),
                        json = json
                    )
                }
                _exportState.value = UiState.Success(stageExport(payload, isReport = false))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _exportState.value = UiState.Error(e, messageRes = com.zero.common.R.string.backup_export_failed)
            }
        }
    }

    /**
     * 解析导入文件
     */
    fun prepareImport(uri: Uri) {
        if (restoringImportResult) {
            // 初次查询提交记录期间到达的选择器结果排队；查清是否已提交后再决定预览。
            savedStateHandle[PENDING_IMPORT_URI_KEY] = uri.toString()
            return
        }
        if (_importState.value is UiState.Success || consumingImportResult) return
        if (_importPromptState.value is UiState.Loading || _importState.value is UiState.Loading) return
        val previous = savedStateHandle.get<String>(PENDING_IMPORT_URI_KEY)
        val previousRelease = pendingImportRelease
        savedStateHandle[PENDING_IMPORT_URI_KEY] = uri.toString()
        pendingImport = null
        _importPromptState.value = UiState.Loading
        viewModelScope.launch {
            try {
                val envelope = withContext(Dispatchers.IO) {
                    // 取消后快速重选同一 URI 时，必须先完成旧释放，才能获取新的持久授权。
                    previousRelease?.join()
                    if (previous != null && previous != uri.toString()) importOperations.release(Uri.parse(previous))
                    importOperations.read(uri)
                }
                pendingImport = envelope
                _importPromptState.value = UiState.Success(
                    BackupImportPrompt(UUID.randomUUID().toString(), buildImportPreview(envelope))
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                clearImportSource()
                _importPromptState.value = UiState.Error(e, messageRes = resolveImportErrorResource(e))
            }
        }
    }

    /** 每次步骤转换生成新标识，旧页面/旧弹窗不能确认另一份导入。 */
    fun selectImportMode(promptId: String, mode: BackupImportMode) {
        val prompt = currentImportPrompt(promptId) ?: return
        if (prompt.step != BackupImportPrompt.Step.SELECT_MODE) return
        _importPromptState.value = UiState.Success(
            prompt.copy(id = UUID.randomUUID().toString(), mode = mode, step = BackupImportPrompt.Step.CONFIRM)
        )
    }

    /** 写入只允许来自当前最终确认；在启动协程之前占用状态，双击不会重复导入。 */
    fun confirmImport(promptId: String) {
        if (_importState.value is UiState.Loading) return
        val prompt = currentImportPrompt(promptId) ?: return
        if (prompt.step != BackupImportPrompt.Step.CONFIRM) return
        val envelope = pendingImport ?: return
        pendingImport = null
        _importPromptState.value = UiState.None
        _importState.value = UiState.Loading
        // 即便系统恢复的是更早快照，也只会重新预览，绝不恢复“自动执行覆盖”状态。
        clearImportSource()
        viewModelScope.launch {
            try {
                val committed = withContext(Dispatchers.IO) {
                    importOperations.execute(envelope, prompt.mode, dedupMinutes.value)
                }
                activeImportCommitId = committed.commitId
                _importState.value = UiState.Success(committed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _importState.value = UiState.Error(e, messageRes = resolveImportErrorResource(e))
            }
        }
    }

    fun cancelImport(promptId: String) {
        currentImportPrompt(promptId) ?: return
        pendingImport = null
        clearImportSource()
        _importPromptState.value = UiState.None
    }

    private fun currentImportPrompt(id: String): BackupImportPrompt? =
        (_importPromptState.value as? UiState.Success)?.data?.takeIf { it.id == id }

    private fun clearImportSource() {
        val source = savedStateHandle.remove<String>(PENDING_IMPORT_URI_KEY) ?: return
        val previousRelease = pendingImportRelease
        // 主线程入口先建立任务链；实际授权 IO 留在后台，后续读取会等待整条旧释放链。
        pendingImportRelease = viewModelScope.launch(Dispatchers.IO) {
            previousRelease?.join()
            importOperations.release(Uri.parse(source))
        }
    }

    /**
     * 清理导出状态，避免重复触发
     */
    fun resetExportState() {
        _exportState.value = UiState.None
    }

    /**
     * 清理导入预览状态
     */
    fun consumeImportPromptError() {
        if (_importPromptState.value is UiState.Error) _importPromptState.value = UiState.None
    }

    /**
     * 清理导入执行状态
     */
    fun resetImportState() {
        val commitId = activeImportCommitId
        if (_importState.value is UiState.Success && commitId != null) consumeImportResult(commitId)
        else _importState.value = UiState.None
    }

    /**
     * 清理报告保存状态
     */
    fun resetReportSaveState() {
        _reportSaveState.value = UiState.None
    }

    /**
     * 更新去重分钟配置
     */
    fun setDedupMinutes(minutes: Int) {
        val normalized = if (minutes in getAllowedDedupMinutes()) minutes else loadDedupMinutes()
        MMKVStore.put(MMKVKeys.SETTINGS_BACKUP_DEDUP_MINUTES, normalized)
        _dedupMinutes.value = normalized
    }

    /**
     * 组装备份包元信息
     */
    private suspend fun buildEnvelope(): BabyBackupEnvelope {
        val appVersion = AppUtils.getAppVersionName()
        return exporter.buildEnvelope(appVersion)
    }

    private fun isPreparingOrWritingExport(): Boolean =
        _exportState.value is UiState.Loading || _reportExportState.value is UiState.Loading ||
            _documentExportState.value !is BackupDocumentExportState.Idle ||
            savedStateHandle.get<BackupExportRequest>(PENDING_EXPORT_KEY) != null

    private suspend fun stageExport(payload: BackupPayload, isReport: Boolean): BackupExportRequest {
        val previous = savedStateHandle.get<BackupExportRequest>(PENDING_EXPORT_KEY)
        val request = withContext(Dispatchers.IO) {
            exportStore.stage(payload, isReport, previous?.token)
        }
        // 文件完整落盘后才允许打开系统选择器；SavedState 中不存任何宝宝记录正文。
        savedStateHandle[PENDING_EXPORT_KEY] = request
        savedStateHandle[EXPORT_PICKER_LAUNCHED_KEY] = false
        if (previous != null) withContext(Dispatchers.IO) { exportStore.discard(previous) }
        return request
    }

    /** 文件选择结果只携带 Uri，所有读写由 Application resolver 和 VM 生命周期承担。 */
    fun writePendingExport(uri: Uri?) {
        if (_documentExportState.value !is BackupDocumentExportState.Idle) return
        val request = savedStateHandle.get<BackupExportRequest>(PENDING_EXPORT_KEY)
        if (uri == null) {
            finishDocumentExport(request, BackupExportOutcome.CANCELLED)
            return
        }
        if (request == null) {
            finishDocumentExport(null, BackupExportOutcome.FAILED)
            return
        }
        savedStateHandle[EXPORT_URI_KEY] = uri.toString()
        _documentExportState.value = BackupDocumentExportState.Writing
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    // 支持持久授权的文档提供方可在进程恢复后继续写入；不支持时仍完成当前写出。
                    try {
                        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    } catch (_: SecurityException) {
                        // 部分提供方仅提供临时授权，不影响当前已授权的导出。
                    } catch (_: IllegalArgumentException) {
                        // file Uri 等入口没有持久授权机制。
                    }
                    exportStore.writeTo(request) {
                        resolver.openOutputStream(uri, "wt") ?: error("无法打开导出文件")
                    }
                }
                finishDocumentExport(request, BackupExportOutcome.SUCCEEDED)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                finishDocumentExport(request, BackupExportOutcome.FAILED)
            }
        }
    }

    fun consumeImportResult(commitId: String) {
        val committed = (_importState.value as? UiState.Success)?.data ?: return
        if (activeImportCommitId != commitId || committed.commitId != commitId || consumingImportResult) return
        consumingImportResult = true
        viewModelScope.launch {
            var archived = false
            _reportSaveState.value = UiState.Loading
            try {
                // 文件成功落盘才允许确认已读；提交 ID 使取消、重试和重复关闭都复用同一归档。
                val item = reportStore.archive(committed)
                archived = true
                _reportSaveState.value = UiState.Success(item.fileName)
                loadReportList()
                val next = withContext(Dispatchers.IO) {
                    importOperations.consumeReport(commitId)
                    importOperations.recoverReport()
                }
                activeImportCommitId = next?.commitId
                _importState.value = next?.let { UiState.Success(it) } ?: UiState.None
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LogUtils.w("导入报告归档或确认待重试：${error.javaClass.simpleName}")
                if (!archived) _reportSaveState.value = UiState.Error(error, messageRes = com.zero.common.R.string.backup_import_report_save_failed)
            } finally {
                consumingImportResult = false
            }
        }
    }

    fun markExportPickerLaunched(request: BackupExportRequest) {
        if (savedStateHandle.get<BackupExportRequest>(PENDING_EXPORT_KEY)?.token != request.token) return
        savedStateHandle[EXPORT_PICKER_LAUNCHED_KEY] = true
        if (request.isReport) resetReportExportState() else resetExportState()
    }

    fun onExportPickerUnavailable() {
        finishDocumentExport(savedStateHandle[PENDING_EXPORT_KEY], BackupExportOutcome.FAILED)
    }

    private fun finishDocumentExport(request: BackupExportRequest?, outcome: BackupExportOutcome) {
        val result = BackupDocumentExportResult(request?.fileName.orEmpty(), request?.isReport == true, outcome)
        savedStateHandle[EXPORT_RESULT_KEY] = result
        _documentExportState.value = BackupDocumentExportState.Finished(result)
    }

    /** 结果由 View 确认展示后再消费；View 销毁、旋转和主题重建都不删除待导出文件。 */
    fun consumeDocumentExportResult() {
        if (_documentExportState.value !is BackupDocumentExportState.Finished) return
        val request = savedStateHandle.remove<BackupExportRequest>(PENDING_EXPORT_KEY)
        savedStateHandle.remove<Boolean>(EXPORT_PICKER_LAUNCHED_KEY)
        savedStateHandle.remove<BackupDocumentExportResult>(EXPORT_RESULT_KEY)
        val destination = savedStateHandle.remove<String>(EXPORT_URI_KEY)
        _documentExportState.value = BackupDocumentExportState.Idle
        viewModelScope.launch(Dispatchers.IO) {
            if (request != null) exportStore.discard(request)
            if (destination != null) runCatching {
                getApplication<Application>().contentResolver.releasePersistableUriPermission(
                    Uri.parse(destination), Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        }
    }

    /**
     * 生成默认文件名
     */
    private fun buildFileName(): String {
        val formatter = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault())
        val datePart = formatter.format(Date())
        return getApplication<Application>().getAppString(
            com.zero.common.R.string.backup_file_name_format,
            datePart
        )
    }

    /**
     * 加载报告列表
     */
    fun loadReportList() {
        val generation = ++reportListGeneration
        viewModelScope.launch {
            _reportListState.value = UiState.Loading
            try {
                val result = reportStore.list()
                if (generation == reportListGeneration) _reportListState.value = UiState.Success(result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == reportListGeneration) _reportListState.value = UiState.Error(error,
                    messageRes = com.zero.common.R.string.backup_import_report_list_failed
                )
            }
        }
    }

    /**
     * 读取报告详情
     */
    fun loadReport(item: BackupReportItem) {
        val prompt = promptFor(BackupReportPrompt.Action.DETAIL, item)
        savedStateHandle[REPORT_PROMPT_KEY] = prompt
        readReportPrompt(prompt)
    }

    private fun readReportPrompt(prompt: BackupReportPrompt) {
        val item = prompt.item() ?: return
        val generation = ++reportLoadGeneration
        viewModelScope.launch {
            _reportLoadState.value = UiState.Loading
            try {
                val result = reportStore.read(item)
                if (generation == reportLoadGeneration && reportPrompt.value?.id == prompt.id) {
                    _reportLoadState.value = UiState.Success(result)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == reportLoadGeneration && reportPrompt.value?.id == prompt.id) _reportLoadState.value = UiState.Error(error,
                    messageRes = com.zero.common.R.string.backup_import_report_load_failed
                )
            }
        }
    }

    fun requestReportDeletion(item: BackupReportItem) {
        savedStateHandle[REPORT_PROMPT_KEY] = promptFor(BackupReportPrompt.Action.DELETE, item)
    }

    fun requestReportClear() {
        savedStateHandle[REPORT_PROMPT_KEY] = BackupReportPrompt(action = BackupReportPrompt.Action.CLEAR)
    }

    fun dismissReportPrompt(id: String) {
        if (reportPrompt.value?.id != id) return
        savedStateHandle[REPORT_PROMPT_KEY] = null
        reportLoadGeneration++
        _reportLoadState.value = UiState.None
    }

    fun confirmReportPrompt(id: String) {
        val prompt = reportPrompt.value?.takeIf { it.id == id } ?: return
        dismissReportPrompt(id)
        when (prompt.action) {
            BackupReportPrompt.Action.DETAIL -> prompt.item()?.let(::prepareReportExport)
            BackupReportPrompt.Action.DELETE -> prompt.item()?.let(::deleteReport)
            BackupReportPrompt.Action.CLEAR -> clearReports()
        }
    }

    private fun promptFor(action: BackupReportPrompt.Action, item: BackupReportItem) = BackupReportPrompt(
        action = action, fileName = item.fileName, filePath = item.filePath, lastModified = item.lastModified
    )

    /**
     * 准备导出报告
     */
    fun prepareReportExport(item: BackupReportItem) {
        if (isPreparingOrWritingExport()) return
        viewModelScope.launch {
            _reportExportState.value = UiState.Loading
            try {
                val payload = reportStore.export(item)
                _reportExportState.value = UiState.Success(stageExport(payload, isReport = true))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _reportExportState.value = UiState.Error(
                    IllegalStateException("export failed"),
                    messageRes = com.zero.common.R.string.backup_import_report_export_failed
                )
            }
        }
    }

    /**
     * 清理报告列表状态
     */
    fun resetReportListState() {
        _reportListState.value = UiState.None
    }

    /**
     * 清理报告详情状态
     */
    fun resetReportLoadState() {
        _reportLoadState.value = UiState.None
    }

    /**
     * 清理报告导出状态
     */
    fun resetReportExportState() {
        _reportExportState.value = UiState.None
    }

    /**
     * 删除指定报告
     */
    fun deleteReport(item: BackupReportItem) {
        reportListGeneration++
        viewModelScope.launch {
            _reportDeleteState.value = UiState.Loading
            try {
                reportStore.delete(item)
                _reportDeleteState.value = UiState.Success(item.fileName)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _reportDeleteState.value = UiState.Error(error,
                    messageRes = com.zero.common.R.string.backup_report_delete_failed
                )
            } finally {
                loadReportList()
            }
        }
    }

    /**
     * 清空所有报告
     */
    fun clearReports() {
        reportListGeneration++
        viewModelScope.launch {
            _reportClearState.value = UiState.Loading
            try {
                _reportClearState.value = UiState.Success(reportStore.clear())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _reportClearState.value = UiState.Error(error,
                    messageRes = com.zero.common.R.string.backup_report_clear_failed
                )
            } finally {
                loadReportList()
            }
        }
    }

    fun resetReportDeleteState() {
        _reportDeleteState.value = UiState.None
    }

    fun resetReportClearState() {
        _reportClearState.value = UiState.None
    }

    /**
     * 生成导入预览信息
     */
    private fun buildImportPreview(envelope: BabyBackupEnvelope): BackupImportPreview {
        val data = envelope.data
        return BackupImportPreview(
            babyCount = data.babies.size,
            feedingCount = data.feedingRecords.size,
            sleepCount = data.sleepRecords.size,
            eventCount = data.eventRecords.size,
            childDailyCount = data.childDailyRecords.size
        )
    }

    /**
     * 生成导入报告文件名
     */
    private fun reportDisplayName(file: File): String {
        val id = runCatching { UUID.fromString(file.nameWithoutExtension).toString() }.getOrNull()
            ?: return file.name
        val formatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        val datePart = formatter.format(Date(file.lastModified()))
        return getApplication<Application>().getAppString(
            com.zero.common.R.string.backup_import_report_file_name_format,
            "${datePart}_${id.take(8)}"
        )
    }

    /**
     * 读取去重分钟配置
     */
    private fun loadDedupMinutes(): Int {
        val value = MMKVStore.getInt(MMKVKeys.SETTINGS_BACKUP_DEDUP_MINUTES, 1)
        return if (value in getAllowedDedupMinutes()) value else 1
    }

    /**
     * 将导入异常映射为提示文案
     */
    private fun resolveImportErrorResource(error: Exception): Int {
        val resId = when ((error as? BackupImportException)?.error) {
            BackupImportError.INVALID_JSON -> com.zero.common.R.string.backup_import_invalid_file
            BackupImportError.UNSUPPORTED_VERSION -> com.zero.common.R.string.backup_import_unsupported_version
            BackupImportError.EMPTY_DATA -> com.zero.common.R.string.backup_import_empty
            else -> com.zero.common.R.string.backup_import_failed
        }
        return resId
    }

    internal companion object {
        const val PENDING_EXPORT_KEY = "pending_backup_export"
        const val EXPORT_RESULT_KEY = "backup_export_result"
        const val EXPORT_URI_KEY = "backup_export_destination"
        const val PENDING_IMPORT_URI_KEY = "pending_backup_import_uri"
        const val REPORT_PROMPT_KEY = "backup_report_prompt"
        const val EXPORT_PICKER_LAUNCHED_KEY = "backup_export_picker_launched"
    }
}
