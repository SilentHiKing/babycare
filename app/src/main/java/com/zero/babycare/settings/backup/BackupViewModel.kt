package com.zero.babycare.settings.backup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.blankj.utilcode.util.AppUtils
import com.zero.babydata.backup.BackupImportError
import com.zero.babydata.backup.BackupImportException
import com.zero.babydata.backup.BackupImportReport
import com.zero.babydata.backup.BabyBackupEnvelope
import com.zero.babydata.backup.BabyBackupExporter
import com.zero.babydata.backup.BabyBackupImporter
import com.zero.components.base.vm.UiState
import com.zero.common.mmkv.MMKVKeys
import com.zero.common.mmkv.MMKVStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException

/**
 * 备份导出 ViewModel
 */
class BackupViewModel(application: Application) : AndroidViewModel(application) {

    private val exporter = BabyBackupExporter(application)
    private val importer = BabyBackupImporter(application)
    private val gson = Gson()

    private val _exportState = MutableStateFlow<UiState<BackupPayload>>(UiState.None)
    val exportState: StateFlow<UiState<BackupPayload>> = _exportState.asStateFlow()

    private val _importPreviewState = MutableStateFlow<UiState<BackupImportPreview>>(UiState.None)
    val importPreviewState: StateFlow<UiState<BackupImportPreview>> = _importPreviewState.asStateFlow()

    private val _importState = MutableStateFlow<UiState<BackupImportReport>>(UiState.None)
    val importState: StateFlow<UiState<BackupImportReport>> = _importState.asStateFlow()

    private val _reportSaveState = MutableStateFlow<UiState<String>>(UiState.None)
    val reportSaveState: StateFlow<UiState<String>> = _reportSaveState.asStateFlow()

    private val _reportListState = MutableStateFlow<UiState<List<BackupReportItem>>>(UiState.None)
    val reportListState: StateFlow<UiState<List<BackupReportItem>>> = _reportListState.asStateFlow()

    private val _reportLoadState = MutableStateFlow<UiState<BackupReportItem>>(UiState.None)
    val reportLoadState: StateFlow<UiState<BackupReportItem>> = _reportLoadState.asStateFlow()

    private val _reportExportState = MutableStateFlow<UiState<BackupReportExportPayload>>(UiState.None)
    val reportExportState: StateFlow<UiState<BackupReportExportPayload>> = _reportExportState.asStateFlow()

    private val _reportDeleteState = MutableStateFlow<UiState<String>>(UiState.None)
    val reportDeleteState: StateFlow<UiState<String>> = _reportDeleteState.asStateFlow()

    private val _reportClearState = MutableStateFlow<UiState<Int>>(UiState.None)
    val reportClearState: StateFlow<UiState<Int>> = _reportClearState.asStateFlow()

    private val _dedupMinutes = MutableStateFlow(loadDedupMinutes())
    val dedupMinutes: StateFlow<Int> = _dedupMinutes.asStateFlow()

    private var pendingImport: BabyBackupEnvelope? = null

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
                _exportState.value = UiState.Success(payload)
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
    fun prepareImport(json: String) {
        viewModelScope.launch {
            pendingImport = null
            _importPreviewState.value = UiState.Loading
            try {
                val preview = withContext(Dispatchers.IO) {
                    val envelope = importer.parse(json)
                    pendingImport = envelope
                    buildImportPreview(envelope)
                }
                _importPreviewState.value = UiState.Success(preview)
            } catch (e: Exception) {
                _importPreviewState.value = UiState.Error(e, resolveImportErrorMessage(e))
            }
        }
    }

    /**
     * 执行覆盖导入
     */
    fun executeImportOverwrite() {
        val envelope = pendingImport ?: run {
            _importState.value = UiState.Error(
                IllegalStateException("empty"),
                getApplication<Application>().getString(com.zero.common.R.string.backup_import_invalid_file)
            )
            return
        }
        viewModelScope.launch {
            _importState.value = UiState.Loading
            try {
                val report = withContext(Dispatchers.IO) {
                    importer.importOverwrite(envelope)
                }
                pendingImport = null
                _importState.value = UiState.Success(report)
            } catch (e: Exception) {
                pendingImport = null
                _importState.value = UiState.Error(e, resolveImportErrorMessage(e))
            }
        }
    }

    /**
     * 执行合并导入
     */
    fun executeImportMerge() {
        val envelope = pendingImport ?: run {
            _importState.value = UiState.Error(
                IllegalStateException("empty"),
                getApplication<Application>().getString(com.zero.common.R.string.backup_import_invalid_file)
            )
            return
        }
        viewModelScope.launch {
            _importState.value = UiState.Loading
            try {
                val report = withContext(Dispatchers.IO) {
                    importer.importMerge(envelope, dedupMinutes.value)
                }
                pendingImport = null
                _importState.value = UiState.Success(report)
            } catch (e: Exception) {
                pendingImport = null
                _importState.value = UiState.Error(e, resolveImportErrorMessage(e))
            }
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
    fun resetImportPreviewState() {
        _importPreviewState.value = UiState.None
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
     * 清理待导入数据
     */
    fun clearPendingImport() {
        pendingImport = null
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
    private fun buildEnvelope(): BabyBackupEnvelope {
        val appVersion = AppUtils.getAppVersionName()
        return exporter.buildEnvelope(appVersion)
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
        viewModelScope.launch {
            _reportExportState.value = UiState.Loading
            val payload = withContext(Dispatchers.IO) {
                try {
                    val file = File(item.filePath)
                    if (!file.exists()) return@withContext null
                    val content = file.readText()
                    BackupReportExportPayload(
                        fileName = item.fileName,
                        json = content
                    )
                } catch (_: Exception) {
                    null
                }
            }
            if (payload == null) {
                _reportExportState.value = UiState.Error(
                    IllegalStateException("export failed"),
                    getApplication<Application>().getString(com.zero.common.R.string.backup_import_report_export_failed)
                )
            } else {
                _reportExportState.value = UiState.Success(payload)
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
}
