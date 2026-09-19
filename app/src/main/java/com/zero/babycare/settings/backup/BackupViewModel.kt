package com.zero.babycare.settings.backup

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.blankj.utilcode.util.AppUtils
import com.zero.babydata.backup.BackupImportError
import com.zero.babydata.backup.BackupImportException
import com.zero.babydata.backup.BackupImportReport
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
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException

/**
 * 备份导出 ViewModel
 */
class BackupViewModel internal constructor(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
    private val importOperations: BackupImportOperations
) : AndroidViewModel(application) {

    constructor(application: Application, savedStateHandle: SavedStateHandle) :
        this(application, savedStateHandle, ApplicationBackupImportOperations(application))

    private val exporter by lazy { BabyBackupExporter(application) }
    private val gson = Gson()

    private val _exportState = MutableStateFlow<UiState<BackupExportRequest>>(UiState.None)
    val exportState: StateFlow<UiState<BackupExportRequest>> = _exportState.asStateFlow()

    private val _importPromptState = MutableStateFlow<UiState<BackupImportPrompt>>(UiState.None)
    val importPromptState: StateFlow<UiState<BackupImportPrompt>> = _importPromptState.asStateFlow()

    private val _importState = MutableStateFlow<UiState<BackupImportReport>>(UiState.None)
    val importState: StateFlow<UiState<BackupImportReport>> = _importState.asStateFlow()

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
        // 若系统在写出期间回收进程，恢复后从私有正文重写目标；目标使用截断模式避免重复追加。
        savedStateHandle.get<String>(EXPORT_URI_KEY)?.takeIf {
            _documentExportState.value is BackupDocumentExportState.Idle
        }?.let { writePendingExport(Uri.parse(it)) }
        // 进程恢复只保留文件位置：重新解析后仍默认合并，必须重新选择并确认。
        savedStateHandle.get<String>(PENDING_IMPORT_URI_KEY)?.let { prepareImport(Uri.parse(it)) }
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
                val message = getApplication<Application>()
                    .getString(com.zero.common.R.string.backup_export_failed)
                _exportState.value = UiState.Error(e, message)
            }
        }
    }

    /**
     * 解析导入文件
     */
    fun prepareImport(uri: Uri) {
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
                _importPromptState.value = UiState.Error(e, resolveImportErrorMessage(e))
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
                val report = withContext(Dispatchers.IO) {
                    importOperations.execute(envelope, prompt.mode, dedupMinutes.value)
                }
                _importState.value = UiState.Success(report)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _importState.value = UiState.Error(e, resolveImportErrorMessage(e))
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
        _importState.value = UiState.None
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
            _documentExportState.value !is BackupDocumentExportState.Idle

    private suspend fun stageExport(payload: BackupPayload, isReport: Boolean): BackupExportRequest {
        val previous = savedStateHandle.get<BackupExportRequest>(PENDING_EXPORT_KEY)
        val request = withContext(Dispatchers.IO) {
            exportStore.stage(payload, isReport, previous?.token)
        }
        // 文件完整落盘后才允许打开系统选择器；SavedState 中不存任何宝宝记录正文。
        savedStateHandle[PENDING_EXPORT_KEY] = request
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
        return getApplication<Application>().getString(
            com.zero.common.R.string.backup_file_name_format,
            datePart
        )
    }

    /**
     * 保存导入报告到文件
     */
    fun saveImportReport(report: BackupImportReport) {
        viewModelScope.launch {
            _reportSaveState.value = UiState.Loading
            val result = withContext(Dispatchers.IO) {
                try {
                    val app = getApplication<Application>()
                    val dir = File(app.filesDir, "backup_reports")
                    if (!dir.exists()) {
                        dir.mkdirs()
                    }
                    val fileName = buildReportFileName()
                    val target = File(dir, fileName)
                    val content = gson.toJson(report)
                    target.writeText(content)
                    target.absolutePath
                } catch (_: Exception) {
                    null
                }
            }
            if (result == null) {
                _reportSaveState.value = UiState.Error(
                    IllegalStateException("save failed"),
                    getApplication<Application>().getString(com.zero.common.R.string.backup_import_report_save_failed)
                )
            } else {
                _reportSaveState.value = UiState.Success(result)
            }
        }
    }

    /**
     * 加载报告列表
     */
    fun loadReportList() {
        viewModelScope.launch {
            _reportListState.value = UiState.Loading
            val result = withContext(Dispatchers.IO) {
                try {
                    val dir = getReportDir()
                    if (!dir.exists()) {
                        return@withContext emptyList()
                    }
                    dir.listFiles()
                        ?.filter { it.isFile && it.name.endsWith(".json") }
                        ?.sortedByDescending { it.lastModified() }
                        ?.map { file ->
                            BackupReportItem(
                                fileName = file.name,
                                filePath = file.absolutePath,
                                lastModified = file.lastModified()
                            )
                        } ?: emptyList()
                } catch (_: Exception) {
                    null
                }
            }
            if (result == null) {
                _reportListState.value = UiState.Error(
                    IllegalStateException("list failed"),
                    getApplication<Application>().getString(com.zero.common.R.string.backup_import_report_list_failed)
                )
            } else {
                _reportListState.value = UiState.Success(result)
            }
        }
    }

    /**
     * 读取报告详情
     */
    fun loadReport(item: BackupReportItem) {
        viewModelScope.launch {
            _reportLoadState.value = UiState.Loading
            val result = withContext(Dispatchers.IO) {
                try {
                    val file = File(item.filePath)
                    if (!file.exists()) return@withContext null
                    val content = file.readText()
                    val report = gson.fromJson(content, BackupImportReport::class.java)
                    item.copy(report = report)
                } catch (_: JsonSyntaxException) {
                    null
                } catch (_: Exception) {
                    null
                }
            }
            if (result == null) {
                _reportLoadState.value = UiState.Error(
                    IllegalStateException("load failed"),
                    getApplication<Application>().getString(com.zero.common.R.string.backup_import_report_load_failed)
                )
            } else {
                _reportLoadState.value = UiState.Success(result)
            }
        }
    }

    /**
     * 准备导出报告
     */
    fun prepareReportExport(item: BackupReportItem) {
        if (isPreparingOrWritingExport()) return
        viewModelScope.launch {
            _reportExportState.value = UiState.Loading
            try {
                val payload = withContext(Dispatchers.IO) {
                    val file = File(item.filePath)
                    val content = file.readText()
                    BackupPayload(
                        fileName = item.fileName,
                        json = content
                    )
                }
                _reportExportState.value = UiState.Success(stageExport(payload, isReport = true))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _reportExportState.value = UiState.Error(
                    IllegalStateException("export failed"),
                    getApplication<Application>().getString(com.zero.common.R.string.backup_import_report_export_failed)
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
        viewModelScope.launch {
            _reportDeleteState.value = UiState.Loading
            val success = withContext(Dispatchers.IO) {
                try {
                    File(item.filePath).delete()
                } catch (_: Exception) {
                    false
                }
            }
            if (success) {
                _reportDeleteState.value = UiState.Success(item.fileName)
            } else {
                _reportDeleteState.value = UiState.Error(
                    IllegalStateException("delete failed"),
                    getApplication<Application>().getString(com.zero.common.R.string.backup_report_delete_failed)
                )
            }
        }
    }

    /**
     * 清空所有报告
     */
    fun clearReports() {
        viewModelScope.launch {
            _reportClearState.value = UiState.Loading
            val result = withContext(Dispatchers.IO) {
                try {
                    val dir = getReportDir()
                    if (!dir.exists()) return@withContext 0
                    val files = dir.listFiles()?.filter { it.isFile } ?: emptyList()
                    var deleted = 0
                    files.forEach { file ->
                        if (file.delete()) deleted++
                    }
                    deleted
                } catch (_: Exception) {
                    null
                }
            }
            if (result == null) {
                _reportClearState.value = UiState.Error(
                    IllegalStateException("clear failed"),
                    getApplication<Application>().getString(com.zero.common.R.string.backup_report_clear_failed)
                )
            } else {
                _reportClearState.value = UiState.Success(result)
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
    private fun buildReportFileName(): String {
        val formatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        val datePart = formatter.format(Date())
        return getApplication<Application>().getString(
            com.zero.common.R.string.backup_import_report_file_name_format,
            datePart
        )
    }

    private fun getReportDir(): File {
        return File(getApplication<Application>().filesDir, "backup_reports")
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
    private fun resolveImportErrorMessage(error: Exception): String {
        val resId = when ((error as? BackupImportException)?.error) {
            BackupImportError.INVALID_JSON -> com.zero.common.R.string.backup_import_invalid_file
            BackupImportError.UNSUPPORTED_VERSION -> com.zero.common.R.string.backup_import_unsupported_version
            BackupImportError.EMPTY_DATA -> com.zero.common.R.string.backup_import_empty
            else -> com.zero.common.R.string.backup_import_failed
        }
        return getApplication<Application>().getString(resId)
    }

    internal companion object {
        const val PENDING_EXPORT_KEY = "pending_backup_export"
        const val EXPORT_RESULT_KEY = "backup_export_result"
        const val EXPORT_URI_KEY = "backup_export_destination"
        const val PENDING_IMPORT_URI_KEY = "pending_backup_import_uri"
    }
}
