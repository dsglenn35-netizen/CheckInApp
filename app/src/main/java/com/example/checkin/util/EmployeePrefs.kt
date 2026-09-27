package com.example.checkin.util

import android.content.Context

/**
 * 考勤表表头的人员信息（姓名 / 工号 / 部门）。
 *
 * App 不联网、也没有账号体系，这些信息只能由用户自己填一次、保存在本机；
 * 导出 Excel 时写进表头，HR 拿到就能直接归档，不必再手工补。
 */
data class EmployeeInfo(
    val name: String = "",
    val employeeId: String = "",
    val department: String = ""
) {
    val isEmpty: Boolean get() = name.isBlank() && employeeId.isBlank() && department.isBlank()

    /** 表头展示行，如「姓名：张三    工号：00123    部门：技术部」；全空时返回 null */
    fun headerLine(): String? {
        if (isEmpty) return null
        val parts = mutableListOf<String>()
        if (name.isNotBlank()) parts += "姓名：" + name
        if (employeeId.isNotBlank()) parts += "工号：" + employeeId
        if (department.isNotBlank()) parts += "部门：" + department
        return parts.joinToString("    ")
    }
}

/** 人员信息的本地持久化 */
object EmployeePrefs {
    private const val NAME = "employee_prefs"
    private const val KEY_NAME = "name"
    private const val KEY_ID = "employee_id"
    private const val KEY_DEPT = "department"

    fun load(context: Context): EmployeeInfo {
        val sp = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        return EmployeeInfo(
            name = sp.getString(KEY_NAME, "").orEmpty(),
            employeeId = sp.getString(KEY_ID, "").orEmpty(),
            department = sp.getString(KEY_DEPT, "").orEmpty()
        )
    }

    fun save(context: Context, info: EmployeeInfo) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_NAME, info.name)
            .putString(KEY_ID, info.employeeId)
            .putString(KEY_DEPT, info.department)
            .apply()
    }
}
