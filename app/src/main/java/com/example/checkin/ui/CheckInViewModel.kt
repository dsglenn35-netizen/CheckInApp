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
import com.example.checkin.data.CheckInSite
import com.example.checkin.data.LeaveDay
import com.example.checkin.data.TimeEntry
import com.example.checkin.location.LocationTracker
import com.example.checkin.service.AutoCheckInService
import com.example.checkin.util.AutoCheckInPrefs
import com.example.checkin.util.BackupPrefs
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

    private val repository = CheckInRepository(AppDatabase.get(application))
    private val locationTracker = LocationTracker(application)
    private val engine = CheckInEngine(application, repository, locationTracker)

    /** 全部打卡记录（按时间倒序） */
    val records: StateFlow<List<CheckInRecord>> = repository.records
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 全部打卡规则 */
    val rules: StateFlow<List<CheckInRule>> = repository.rules
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 全部附加打卡地点（一个规则可多点位） */
    val sites: StateFlow<List<CheckInSite>> = repository.sites
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _currentLocation = MutableStateFlow<Location?>(null)
    val currentLocation: StateFlow<Location?> = _currentLocation.asStateFlow()

    /** 当前连接的 WiFi SSID（室内/WiFi 兜底判定时展示） */
    private val _currentSsid = MutableStateFlow<String?>(null)
    val currentSsid: StateFlow<String?> = _currentSsid.asStateFlow()

    /** 备份是否包含取证照片（体积会显著增大，默认关闭） */
    private val _backupWithPhotos = MutableStateFlow(BackupPrefs.withPhotos(application))
    val backupWithPhotos: StateFlow<Boolean> = _backupWithPhotos.asStateFlow()

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
            _currentSsid.value = locationTracker.currentWifiSsid()
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

    // ---------- 特殊日标记（按天：请假 / 放假） ----------

    /**
     * 标记 / 取消某天的特殊日标记。
     *
     * [kind] 传 [LeaveDay.KIND_LEAVE]（请假）或 [LeaveDay.KIND_HOLIDAY]（公司放假）表示标记，
     * 传 null 表示取消。两种标记都会让当天**不再自动打卡**（含失败记录）。
     * 标记后主动唤醒自动打卡服务重排状态，静默期也能立即生效。
     */
    fun setDayMark(date: LocalDate, kind: String?) = viewModelScope.launch {
        val key = date.toString()
        val existing = repository.leaveDay(key)
        if (kind == null) {
            existing?.let { repository.deleteLeaveDay(it) }
        } else {
            repository.insertLeaveDay(LeaveDay(date = key, kind = kind))
        }
        AutoCheckInService.refresh(getApplication())
    }

    /**
     * 按日期区间批量标记（**含首尾**），用于公司放假（如国庆 1–7 号）或连续多天请假。
     * 区间内已有的标记会被覆盖为 [kind]。
     */
    fun markDateRange(start: LocalDate, end: LocalDate, kind: String) = viewModelScope.launch {
        if (end.isBefore(start)) return@launch
        repository.insertLeaveDays(
            dateRange(start, end).map { LeaveDay(date = it.toString(), kind = kind) }
        )
        AutoCheckInService.refresh(getApplication())
    }

    /** 清除日期区间内的全部特殊日标记（含首尾） */
    fun clearDateRange(start: LocalDate, end: LocalDate) = viewModelScope.launch {
        if (end.isBefore(start)) return@launch
        dateRange(start, end).forEach { day ->
            repository.leaveDay(day.toString())?.let { repository.deleteLeaveDay(it) }
        }
        AutoCheckInService.refresh(getApplication())
    }

    /** [start] 到 [end] 的连续日期（含两端） */
    private fun dateRange(start: LocalDate, end: LocalDate): List<LocalDate> =
        generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.toList()

    // ---------- 时间段标注（请假/放假/加班） ----------

    /** 添加一条时间段标注（请假/放假/加班），startMinute/endMinute 为当天分钟数 */
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

    // 规则增删改后刷新自动打卡服务：使其立即按新规则重排边界闹钟。
    // 服务在静默期（非打卡时段无轮询）也能及时感知规则变更。

    fun addRule(rule: CheckInRule) = viewModelScope.launch {
        repository.insertRule(rule)
        AutoCheckInService.refresh(getApplication())
    }

    /**
     * 保存规则（新增或编辑），并同步该规则下的附加打卡地点。
     * [newSites] 中 ruleId 为 0 的条目会绑定到保存后的规则主键。
     */
    fun saveRuleWithSites(rule: CheckInRule, newSites: List<CheckInSite>) = viewModelScope.launch {
        // 规则与附加地点必须整体生效：拆开写的话，中途失败会留下
        // "附加地点已删、规则未更新"的脏状态（规则有地点没了，自动打卡会判定地点外）
        repository.inTransaction {
            val ruleId = if (rule.id == 0L) {
                repository.insertRule(rule)
            } else {
                repository.updateRule(rule)
                rule.id
            }
            repository.deleteSitesForRule(ruleId)
            repository.insertSites(newSites.map { it.copy(id = 0, ruleId = ruleId) })
        }
        AutoCheckInService.refresh(getApplication())
    }

    fun updateRule(rule: CheckInRule) = viewModelScope.launch {
        repository.updateRule(rule)
        AutoCheckInService.refresh(getApplication())
    }

    fun deleteRule(rule: CheckInRule) = viewModelScope.launch {
        // 连带删除该规则下的附加地点与照片由 repository/调用方处理
        repository.deleteRule(rule)
        AutoCheckInService.refresh(getApplication())
    }

    /** 备份是否包含照片（持久化开关） */
    fun setBackupWithPhotos(enabled: Boolean) {
        BackupPrefs.setWithPhotos(getApplication(), enabled)
        _backupWithPhotos.value = enabled
    }

    // ---------- 导出 ----------

    fun consumeExportMessage() {
        _exportMessage.value = null
    }

    fun consumeSettingsMessage() {
        _settingsMessage.value = null
    }

    /** 导出打卡记录（本月 / 指定月份 / 全部，CSV / Excel），生成后弹出系统分享面板 */
    fun exportRecords(scope: ExportScope, format: ExportFormat, month: YearMonth? = null) {
        if (_exporting.value) return
        viewModelScope.launch {
            _exporting.value = true
            try {
                val all = repository.allRecords()
                val allRules = repository.allRules()
                val allLeave = repository.allLeaveDays()
                val allEntries = repository.allTimeEntries()
                val allSites = repository.allSites()
                // 指定月份时以该月为准，否则按范围（本月用当前月）
                val monthKey = month?.toString() ?: YearMonth.now().toString()

                val selected = when {
                    month != null ->
                        all.filter { YearMonth.from(it.timestamp.toLocalDate()) == month }
                    scope == ExportScope.THIS_MONTH ->
                        all.filter { it.timestamp.toLocalDate().toString().startsWith(monthKey) }
                    else -> all
                }
                val leaveSel = when {
                    month != null -> allLeave.filter { it.date.startsWith(monthKey) }
                    scope == ExportScope.THIS_MONTH -> allLeave.filter { it.date.startsWith(monthKey) }
                    else -> allLeave
                }
                val entrySel = when {
                    month != null -> allEntries.filter { it.date.startsWith(monthKey) }
                    scope == ExportScope.THIS_MONTH -> allEntries.filter { it.date.startsWith(monthKey) }
                    else -> allEntries
                }
                val label = month?.let { "${it.year}年${it.monthValue}月" } ?: scope.label

                if (selected.isEmpty() && leaveSel.isEmpty() && entrySel.isEmpty()) {
                    _exportMessage.value = "没有可导出的数据"
                    return@launch
                }
                val file = when (format) {
                    ExportFormat.CSV -> ExportManager.exportCsv(getApplication(), selected, label)
                    ExportFormat.XLSX -> ExportManager.exportXlsx(
                        getApplication(), selected, allRules, allSites, leaveSel, entrySel, scope, month
                    )
                }
                ExportManager.share(getApplication(), file)
                _exportMessage.value = "已导出 ${selected.size} 条记录（$label）"
            } catch (e: Exception) {
                _exportMessage.value = "导出失败：${e.message}"
            } finally {
                _exporting.value = false
            }
        }
    }

    // ---------- 备份 / 恢复 ----------

    /** 备份全部数据（规则 + 附加地点 + 记录 + 请假 + 时间段标注，可选照片）为 JSON 并分享 */
    fun backupData() {
        viewModelScope.launch {
            _settingsMessage.value = null
            try {
                val rules = repository.allRules()
                val records = repository.allRecords()
                val leaveDays = repository.allLeaveDays()
                val timeEntries = repository.allTimeEntries()
                val sites = repository.allSites()
                if (rules.isEmpty() && records.isEmpty() && leaveDays.isEmpty() &&
                    timeEntries.isEmpty() && sites.isEmpty()
                ) {
                    _settingsMessage.value = "暂无数据可备份"
                    return@launch
                }
                val withPhotos = _backupWithPhotos.value
                val file = ExportManager.exportJson(
                    getApplication(), rules, records, leaveDays, timeEntries, sites, withPhotos
                )
                ExportManager.share(getApplication(), file)
                val sizeMb = file.length() / 1024.0 / 1024.0
                _settingsMessage.value = buildString {
                    append("备份完成：${rules.size} 条规则、${records.size} 条记录、")
                    append("${leaveDays.count { !it.isHoliday }} 天请假、")
                    append("${leaveDays.count { it.isHoliday }} 天放假、")
                    append("${timeEntries.size} 条时段标注")
                    if (sites.isNotEmpty()) append("、${sites.size} 个附加打卡点")
                    append("（%.1f MB%s）".format(sizeMb, if (withPhotos) "，含照片" else ""))
                }
            } catch (e: Exception) {
                _settingsMessage.value = "备份失败：${e.message}"
            }
        }
    }

    /**
     * 从备份 JSON 恢复（覆盖导入：先清空现有数据）。
     * 备份含内嵌照片时会还原到照片目录并回填路径，换机也能看到取证照片。
     */
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
                // 先还原照片文件（记录需要回填新的照片路径）。
                // 键用记录在备份中的**下标**而不是打卡时间戳：同一秒内的两条记录时间戳会重复，
                // 用时间戳做键会让后一张照片覆盖前一张。
                val restoredPhotos = restorePhotoFiles(data.photos)
                val restoredRecords = data.records.mapIndexed { index, record ->
                    restoredPhotos[index]?.let { record.copy(photoPath = it) } ?: record
                }

                // 清空 + 重建包在同一个事务里：任一步失败整体回滚，
                // 不会出现"旧数据已清空、新数据只写了一半"的不可恢复状态。
                repository.inTransaction {
                    repository.clearRecords()
                    repository.clearRulesAndSites()
                    repository.clearLeaveDays()
                    repository.clearTimeEntries()

                    // 规则按备份顺序重新插入并拿到新主键；
                    // 附加地点优先按 ruleIndex 绑定（规则名可能重复，名字不是唯一键），
                    // 旧备份（v2.5 之前）没有 ruleIndex，回退按规则名匹配。
                    val ruleIds = data.rules.map { repository.insertRule(it) }
                    val idByName = mutableMapOf<String, Long>()
                    data.rules.forEachIndexed { i, rule ->
                        idByName.putIfAbsent(rule.name, ruleIds[i])
                    }
                    repository.insertRecords(restoredRecords)
                    repository.insertLeaveDays(data.leaveDays)
                    repository.insertTimeEntries(data.timeEntries)
                    repository.insertSites(
                        data.sites.map { bs ->
                            val bound = if (bs.ruleIndex in data.rules.indices) {
                                ruleIds[bs.ruleIndex]
                            } else {
                                idByName[bs.ruleName]
                            }
                            bs.site.copy(ruleId = bound ?: 0L)
                        }
                    )
                }
                AutoCheckInService.refresh(getApplication())

                _settingsMessage.value = buildString {
                    append("恢复完成：${data.rules.size} 条规则、${restoredRecords.size} 条记录、")
                    append("${data.leaveDays.count { !it.isHoliday }} 天请假、")
                    append("${data.leaveDays.count { it.isHoliday }} 天放假、")
                    append("${data.timeEntries.size} 条时段标注")
                    if (data.sites.isNotEmpty()) append("、${data.sites.size} 个附加打卡点")
                    if (restoredPhotos.isNotEmpty()) append("、${restoredPhotos.size} 张照片")
                }
            } catch (e: Exception) {
                _settingsMessage.value = "恢复失败：${e.message}"
            }
        }
    }

    /**
     * 把备份中内嵌的 base64 照片还原为本地文件。
     * 返回"记录在备份中的下标 -> 新照片绝对路径"的映射；单张失败不影响其它照片恢复。
     */
    private suspend fun restorePhotoFiles(photos: Map<Int, String>): Map<Int, String> =
        withContext(Dispatchers.IO) {
            if (photos.isEmpty()) return@withContext emptyMap()
            val result = mutableMapOf<Int, String>()
            val dir = getApplication<Application>()
                .getExternalFilesDir(null)?.let { File(it, "photos") }?.apply { mkdirs() }
                ?: return@withContext emptyMap()
            photos.forEach { (index, base64) ->
                runCatching {
                    val bytes = android.util.Base64.decode(base64, android.util.Base64.NO_WRAP)
                    val file = File(dir, "restored_${index}.jpg")
                    file.writeBytes(bytes)
                    result[index] = file.absolutePath
                }
            }
            result
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
