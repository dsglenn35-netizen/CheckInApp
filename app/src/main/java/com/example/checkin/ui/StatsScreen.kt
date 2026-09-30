package com.example.checkin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.checkin.util.DayWork
import com.example.checkin.util.ShiftSchedule
import com.example.checkin.util.WorkStats
import java.time.LocalDate
import java.time.YearMonth

/** 分钟转成人话（如 "8 小时 30 分"），集中一处避免界面上到处拼字符串 */
private fun hoursText(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h > 0 && m > 0 -> h.toString() + " 小时 " + m.toString() + " 分"
        h > 0 -> h.toString() + " 小时"
        else -> m.toString() + " 分钟"
    }
}

private fun percentText(rate: Float): String = (rate * 100).toInt().toString() + "%"

private val WEEK_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")

/** 柱高：按时长比例换算；时长为 0 也留一条细线，以便看出"这天有班次却没工时" */
private fun barHeight(minutes: Int, max: Int): Dp {
    val fraction = (minutes.toFloat() / max.coerceAtLeast(1)).coerceIn(0f, 1f)
    val h = fraction * 120f
    return (if (minutes > 0) h.coerceAtLeast(4f) else 2f).dp
}

/**
 * 统计页：近 7 天工时柱状图 + 本月工时与出勤汇总。
 *
 * 刻意不引入图表库：这里只有"一周每天一根柱子"这一种图，
 * 用 Box 的高度按比例画就够，省掉一整个依赖（也省掉它的体积与混淆配置）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(viewModel: CheckInViewModel) {
    val records by viewModel.records.collectAsState()
    val rules by viewModel.rules.collectAsState()
    val shiftOverrides by viewModel.shiftOverrides.collectAsState()
    val overrides = remember(shiftOverrides) { ShiftSchedule.table(shiftOverrides) }
    val today = LocalDate.now()

    val week = remember(records, rules, overrides, today) {
        WorkStats.recentDays(records, rules, today, days = 7, overrides = overrides)
    }
    val month = remember(records, rules, overrides, today) {
        WorkStats.monthSummary(records, rules, YearMonth.from(today), overrides)
    }
    val dueDays = remember(week) { week.count { it.hasDue } }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("统计") },
                windowInsets = WindowInsets(0.dp)
            )
        },
        contentWindowInsets = WindowInsets(0.dp)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "近 7 天在岗工时",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    WorkBarChart(week)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "本周合计 " + hoursText(WorkStats.totalMinutes(week)) +
                            "（按班次累加，班次之间的空档不计入）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                YearMonth.from(today).let { it.year.toString() + " 年 " + it.monthValue + " 月" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    StatLine("应打卡班次", month.dueShifts.toString() + " 次")
                    StatLine("已出勤班次", month.attendedShifts.toString() + " 次")
                    StatLine("班次出勤率", percentText(month.attendanceRate))
                    StatLine("累计在岗工时", hoursText(month.workMinutes))
                    StatLine("缺卡", month.missingShifts.toString() + " 个班次")
                    StatLine("旷工", month.absentShifts.toString() + " 个班次")
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "柱状图颜色：有工时为主色，有应打卡班次但无工时则只有一条淡色细线" +
                            "（一眼看出该上班却没时长）。工时按班次分别计算后累加。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "近 7 天里有应打卡班次的天数：" + dueDays.toString() + " 天",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun WorkBarChart(days: List<DayWork>) {
    val max = WorkStats.chartMax(days)
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(132.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            days.forEach { day ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(barHeight(day.workMinutes, max))
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            if (day.workMinutes > 0) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                        )
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            days.forEach { day ->
                Text(
                    WEEK_LABELS[(day.date.dayOfWeek.value + 6) % 7],
                    style = MaterialTheme.typography.bodySmall,
                    color = if (day.complete) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
