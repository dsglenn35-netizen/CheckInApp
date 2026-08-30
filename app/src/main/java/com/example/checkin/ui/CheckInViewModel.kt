package com.example.checkin.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.checkin.core.CheckInEngine
import com.example.checkin.data.AppDatabase
import com.example.checkin.data.CheckInRecord
import com.example.checkin.data.CheckInRepository
import com.example.checkin.data.CheckInRule
import com.example.checkin.data.LeaveDay
import com.example.checkin.data.TimeEntry
import com.example.checkin.location.LocationTracker
import com.example.checkin.service.AutoCheckInService
import com.example.checkin.util.AutoCheckInPrefs
import com.example.checkin.util.ExportFormat
import com.example.checkin.util.ExportManager
import com.example.checkin.util.ExportScope
import com.example.checkin.util.PhotoPrefs
import com.example.checkin.util.toLocalDate
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CheckInViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = CheckInRepository(AppDatabase.get(application).checkInDao())
    private val locationTracker = LocationTracker(application)
    private val engine = CheckInEngine(application, repository, locationTracker)

    /** 全部打卡记录（按时间倒序） */
    val records: StateFlow<List<CheckInRecord>> = repository.records
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 全部打卡规则 */
    val rules: StateFlow<List<CheckInRule>> = repository.rules
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _currentLocation = MutableStateFlow<Location?>(null)
    val currentLocation: StateFlow<Location?> = _currentLocation.asStateFlow()

    /** 最近一次打卡的结果 */
    private val _lastResult = MutableStateFlow<CheckInRecord?>(null)
    val lastResult: StateFlow<CheckInRecord?> = _lastResult.asStateFlow()

    private val _isChecking = MutableStateFlow(false)
    val isChecking: StateFlow<Boolean> = _isChecking.asStateFlow()

    /** 自动打卡是否开启（本地持久化） */
    private val _autoEnabled = MutableStateFlow(AutoCheckInPrefs.isEnabled(application))
    val autoEnabled: StateFlow<Boolean> = _autoEnabled.asStateFlow()

    /** 打卡时是否拍照（本地持久化） */
    private val _photoEnabled = MutableStateFlow(PhotoPrefs.isEnabled(application))
    val photoEnabled: StateFlow<Boolean> = _photoEnabled.asStateFlow()

    /** 是否正在导出 */
    private val _exporting = MutableStateFlow(false)
    val exporting: StateFlow<Boolean> = _exporting.asStateFlow()

    /** 导出结果提示（成功/失败/无数据） */
    private val _exportMessage = MutableStateFlow<String?>(null)
    val exportMessage: StateFlow<String?> = _exportMessage.asStateFlow()

    /** 设置页操作提示（备份/恢复/清空） */
    private val _settingsMessage = MutableStateFlow<String?>(null)
    val settingsMessage: StateFlow<String?> = _settingsMessage.asStateFlow()

    /** 请假模式：提前标记的请假日期 */
    val leaveDays: StateFlow<List<LeaveDay>> = repository.leaveDays
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 时间段标注（按时间段请假/加班） */
    val timeEntries: StateFlow<List<TimeEntry>> = repository.timeEntries
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        refreshLocation()
        // 若上次开启了自动打卡（例如进程被系统回收后重启），恢复前台服务
        if (AutoCheckInPrefs.isEnabled(getApplication()) && hasLocationPermission()) {
            AutoCheckInService.start(getApplication())
        }
    }

    fun refreshLocation() {
        viewModelScope.launch {
            _currentLocation.value = locationTracker.requestCurrentLocation()
        }
    }

    /**
     * 开启/关闭自动打卡。
     * 开启：启动前台服务持续监控，满足时间段+地点即自动记录；
     * 关闭：停止服务。
     */
    fun setAutoCheckIn(enabled: Boolean) {
        if (enabled && !hasLocationPermission()) {
            _autoEnabled.value = false
            return
        }
        AutoCheckInPrefs.setEnabled(getApplication(), enabled)
        _autoEnabled.value = enabled
        if (enabled) {
            AutoCheckInService.start(getApplication())
        } else {
            AutoCheckInService.stop(getApplication())
        }
    }

    /** 打卡时拍照开关（仅手动打卡生效） */
    fun setPhotoEnabled(enabled: Boolean) {
        PhotoPrefs.setEnabled(getApplication(), enabled)
        _photoEnabled.value = enabled
    }

    /** 手动打卡：记录系统时间与位置，与所有启用规则比对后入库 */
    fun checkIn(photoPath: String? = null) {
        if (_isChecking.value) return
        viewModelScope.launch {
            _isChecking.value = true
            try {
                val result = engine.checkIn(photoPath = photoPath)
                _currentLocation.value = result.location
                _lastResult.value = result.record
            } finally {
                _isChecking.value = false
            }
        }
    }

    // ---------- 记录管理 ----------

    fun deleteRecord(record: CheckInRecord) = viewModelScope.launch {
        repository.deleteRecord(record)
        // 连带删除该记录附带的取证照片文件
        record.photoPath?.let { deletePhotoFile(it) }
    }

    fun updateRecordNote(id: Long, note: String?) = viewModelScope.launch {
        repository.updateRecordNote(id, note?.ifBlank { null })
    }

    /** 修正打卡记录（时间/地点等，整行更新） */
    fun updateRecord(record: CheckInRecord) = viewModelScope.launch {
        repository.updateRecord(record)
    }

    /** 按经纬度逆地理编码（修正记录时刷新地址） */
    suspend fun addressFor(latitude: Double, longitude: Double): String? =
        engine.addressFor(latitude, longitude)

    /** 修正记录后重判状态：时间+地点均在规则内则升级为成功 */
    suspend fun reEvaluateStatus(record: CheckInRecord): CheckInRecord =
        engine.reEvaluateStatus(record)

    /** 一键请假：把失败记录备注标为“请假”（已有备注则追加） */
    fun markRecordAsLeave(record: CheckInRecord) = viewModelScope.launch {
        val newNote = when {
            record.note.isNullOrBlank() -> "请假"
            record.note.contains("请假") -> record.note
            else -> "${record.note}（请假）"
        }
        repository.updateRecordNote(record.id, newNote)
    }

    // ---------- 请假模式 ----------

    /** 标记/取消某天为请假（请假当天不产生自动打卡记录） */
    fun toggleLeaveDay(date: LocalDate) {
        viewModelScope.launch {
            val key = date.toString()
            val existing = repository.leaveDay(key)
            if (existing != null) {
                repository.deleteLeaveDay(existing)
            } else {
                repository.insertLeaveDay(LeaveDay(date = key))
            }
        }
    }

    // ---------- 时间段标注（请假/加班） ----------

    /** 添加一条时间段标注（请假/加班），startMinute/endMinute 为当天分钟数 */
    fun addTimeEntry(
        type: String,
        date: LocalDate,
        startMinute: Int,
        endMinute: Int,
        note: String?
    ) = viewModelScope.launch {
        repository.insertTimeEntry(
            TimeEntry(
                date = date.toString(),
                type = type,
                startMinute = startMinute,
                endMinute = endMinute,
                note = note?.ifBlank { null }
            )
        )
    }

    fun deleteTimeEntry(entry: TimeEntry) = viewModelScope.launch {
        repository.deleteTimeEntry(entry)
    }

    fun clearAllRecords() {
        viewModelScope.launch {
            repository.clearRecords()
            // 清理全部取证照片文件，避免残留
            deleteAllPhotos()
            _settingsMessage.value = "已清空全部打卡记录"
        }
    }

    // ---------- 规则管理 ----------

    fun addRule(rule: CheckInRule) = viewModelScope.launch { repository.insertRule(rule) }

    fun updateRule(rule: CheckInRule) = viewModelScope.launch { repository.updateRule(rule) }

    fun deleteRule(rule: CheckInRule) = viewModelScope.launch { repository.deleteRule(rule) }

    // ---------- 导出 ----------

    fun consumeExportMessage() {
        _exportMessage.value = null
    }

    fun consumeSettingsMessage() {
        _settingsMessage.value = null
    }

    /** 导出打卡记录（本月 / 全部，CSV / Excel），生成后弹出系统分享面板 */
    fun exportRecords(scope: ExportScope, format: ExportFormat) {
        if (_exporting.value) return
        viewModelScope.launch {
            _exporting.value = true
            try {
                val all = repository.allRecords()
                val allRules = repository.allRules()
                val allLeave = repository.allLeaveDays()
                val allEntries = repository.allTimeEntries()
                val monthKey = YearMonth.now().toString()

                val selected = when (scope) {
                    ExportScope.THIS_MONTH -> all.filter { it.timestamp.toLocalDate().toString().startsWith(monthKey) }
                    ExportScope.ALL -> all
                }
                val leaveSel = when (scope) {
                    ExportScope.THIS_MONTH -> allLeave.filter { it.date.startsWith(monthKey) }
                    ExportScope.ALL -> allLeave
                }
                val entrySel = when (scope) {
                    ExportScope.THIS_MONTH -> allEntries.filter { it.date.startsWith(monthKey) }
                    ExportScope.ALL -> allEntries
                }

                if (selected.isEmpty() && leaveSel.isEmpty() && entrySel.isEmpty()) {
                    _exportMessage.value = "没有可导出的数据"
                    return@launch
                }
                val file = when (format) {
                    ExportFormat.CSV -> ExportManager.exportCsv(getApplication(), selected, scope.label)
                    ExportFormat.XLSX -> ExportManager.exportXlsx(
                        getApplication(), selected, allRules, leaveSel, entrySel, scope
                    )
                }
                ExportManager.share(getApplication(), file)
                _exportMessage.value = "已导出 ${selected.size} 条记录（${scope.label}）"
            } catch (e: Exception) {
                _exportMessage.value = "导出失败：${e.message}"
            } finally {
                _exporting.value = false
            }
        }
    }

    // ---------- 备份 / 恢复 ----------

    /** 备份全部数据（规则 + 记录 + 请假 + 时间段标注）为 JSON 并分享 */
    fun backupData() {
        viewModelScope.launch {
            _settingsMessage.value = null
            try {
                val rules = repository.allRules()
                val records = repository.allRecords()
                val leaveDays = repository.allLeaveDays()
                val timeEntries = repository.allTimeEntries()
                if (rules.isEmpty() && records.isEmpty() && leaveDays.isEmpty() && timeEntries.isEmpty()) {
                    _settingsMessage.value = "暂无数据可备份"
                    return@launch
                }
                val file = ExportManager.exportJson(
                    getApplication(), rules, records, leaveDays, timeEntries
                )
                ExportManager.share(getApplication(), file)
                _settingsMessage.value =
                    "备份完成：${rules.size} 条规则、${records.size} 条记录、" +
                        "${leaveDays.size} 天请假、${timeEntries.size} 条时段标注"
            } catch (e: Exception) {
                _settingsMessage.value = "备份失败：${e.message}"
            }
        }
    }

    /** 从备份 JSON 恢复（覆盖导入：先清空现有数据） */
    fun restoreData(uri: Uri) {
        viewModelScope.launch {
            _settingsMessage.value = null
            try {
                val text = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver
                        .openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }
                val data = text?.let { ExportManager.parseBackup(it) }
                if (data == null) {
                    _settingsMessage.value = "恢复失败：文件格式不正确"
                    return@launch
                }
                repository.clearRecords()
                repository.clearRules()
                repository.clearLeaveDays()
                repository.clearTimeEntries()
                repository.insertRules(data.rules)
                repository.insertRecords(data.records)
                repository.insertLeaveDays(data.leaveDays)
                repository.insertTimeEntries(data.timeEntries)
                _settingsMessage.value =
                    "恢复完成：${data.rules.size} 条规则、${data.records.size} 条记录、" +
                        "${data.leaveDays.size} 天请假、${data.timeEntries.size} 条时段标注"
            } catch (e: Exception) {
                _settingsMessage.value = "恢复失败：${e.message}"
            }
        }
    }

    /** 删除单张取证照片文件（失败静默忽略） */
    private fun deletePhotoFile(path: String) {
        runCatching { File(path).delete() }
    }

    /** 清理照片目录下的全部文件（清空记录时调用） */
    private fun deleteAllPhotos() {
        runCatching {
            val dir = getApplication<Application>()
                .getExternalFilesDir(null)?.let { File(it, "photos") }
            dir?.listFiles()?.forEach { it.delete() }
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            getApplication(), Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
}
