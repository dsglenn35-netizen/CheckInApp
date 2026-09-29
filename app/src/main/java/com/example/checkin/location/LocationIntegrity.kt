package com.example.checkin.location

import android.location.Location
import android.os.Build

/**
 * 该定位是否来自系统"**模拟位置**"提供者（开发者选项里的"选择模拟位置信息应用"）。
 *
 * 这是唯一能真正**证伪地点声明**的作弊信号：模拟位置可以让人在家里打出公司的坐标。
 * 因此它被写进打卡记录并出现在导出表里 —— 一张能被随意伪造地点的考勤表没有证据价值。
 *
 * API 分支集中在这里：Android 12（API 31）起用 [Location.isMock]，旧版本用
 * 已废弃但仍在工作的 [Location.isFromMockProvider]。
 */
fun Location.isMocked(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        isMock
    } else {
        @Suppress("DEPRECATION")
        isFromMockProvider
    }
