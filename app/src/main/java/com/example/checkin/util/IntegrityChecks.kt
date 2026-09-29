package com.example.checkin.util

import com.example.checkin.data.CheckInRecord

/**
 * 打卡记录的**完整性留痕**判定（纯逻辑，可单测）。
 *
 * 目前有两类"这张记录的可信度有问题"的信号：
 * 1. **定位来自模拟位置**（[CheckInRecord.mockLocation]）—— 地点声明被证伪；
 * 2. **系统时间与 GPS 授时偏差过大**（[CheckInValidator.isClockSkewed]）—— 时间声明可疑。
 *
 * ## 设计口径：只留痕，不阻断
 *
 * 两类信号都**不影响打卡结果**，只写进记录并在界面与报表上标出来。
 * 理由是误报的代价不对称：模拟位置在真机上也可能由系统/调试工具误置，
 * 若因此拒绝打卡，用户会直接错过打卡窗口（缺卡、扣钱）；
 * 而"留痕"让这件事**可被核查**，正是证据该有的样子。
 *
 * 报表里两类信号**合并成一列**「异常留痕」：它们很少同时出现，
 * 分开两列只会让本来信息量就低的表格更难读。
 */
object IntegrityChecks {

    /** 记录是否带有异常留痕 */
    fun hasAnomaly(record: CheckInRecord): Boolean =
        record.mockLocation || CheckInValidator.isClockSkewed(record.clockSkewMs)

    /**
     * 记录卡片与报表「异常留痕」列的统一文案；正常时返回空串。
     *
     * 只此一处生成文案，避免界面与导出表各写一套措辞而慢慢漂移。
     */
    fun anomalyLabel(record: CheckInRecord): String {
        val parts = mutableListOf<String>()
        if (record.mockLocation) parts += "定位来自模拟位置"
        // 先取到局部变量：isClockSkewed 非空并不构成智能转换，null 判断必须显式写出来
        val skew = record.clockSkewMs
        if (skew != null && CheckInValidator.isClockSkewed(skew)) {
            parts += "时钟偏差 " + formatClockSkew(skew)
        }
        return if (parts.isEmpty()) "" else "异常：" + parts.joinToString("；")
    }
}
