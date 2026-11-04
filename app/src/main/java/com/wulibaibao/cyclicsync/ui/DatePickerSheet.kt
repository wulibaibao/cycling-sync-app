package com.wulibaibao.cyclicsync.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kizitonwose.calendar.compose.CalendarState
import com.kizitonwose.calendar.compose.HorizontalCalendar
import com.kizitonwose.calendar.compose.rememberCalendarState
import com.kizitonwose.calendar.core.CalendarDay
import com.kizitonwose.calendar.core.DayPosition
import com.kizitonwose.calendar.core.OutDateStyle
import com.kizitonwose.calendar.core.previousMonth
import com.kizitonwose.calendar.core.nextMonth
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/**
 * 日期选择底部弹层 —— 替换 Material3 自带的 `DatePickerDialog`。
 *
 * 为什么换掉 M3 的：
 * - M3 的 DatePickerDialog 在中文下是「年 / 月」两段式选择器，点两下才能看到日历，
 *   而且整体是 Material You 的圆润造型，在这个「同步」页面里显得突兀
 * - 弹窗不能滑，跨月要靠年份选择器
 *
 * 现在这个：
 * - 底部弹层（ModalBottomSheet），符合 Android 12+ 的交互习惯
 * - 直接就是月历，纵向滑动切月，标题栏左右箭头快速翻
 * - 周一开头（骑行场景中文习惯）
 * - 选中态是圆角药丸 + 填充，今天是描边圈
 * - 未来日期不可选
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerSheet(
    selected: LocalDate,
    onDateSelected: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val today = remember { LocalDate.now() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    // 可选范围：往前 5 年，到今天为止
    val startMonth = remember { YearMonth.from(today).minusMonths(60) }
    val endMonth = remember { YearMonth.from(today) }

    val state = rememberCalendarState(
        startMonth = startMonth,
        endMonth = endMonth,
        firstVisibleMonth = YearMonth.from(selected),
        firstDayOfWeek = DayOfWeek.MONDAY,
        // ⚠️ 必须用 EndOfGrid —— 默认的 EndOfRow 会让 5 行的月份只占 5 行、
        // 6 行的月份占 6 行，横向翻页时每页高度不同，弹层高度会突变（看着像闪）。
        // EndOfGrid 让**所有月份都是 6 行**，每页高度完全一致，翻页不再跳。
        outDateStyle = OutDateStyle.EndOfGrid,
    )

    // 当前显示的月份（跟着左右滑动实时走）
    //
    // 横向分页日历里 `firstVisibleMonth` = `store[listState.firstVisibleItemIndex]`，
    // 也就是「第一个可见的页」。但拖动过程中它会先跳到新页，导致标题提前变。
    //
    // 用 `layoutInfo.visibleMonthsInfo` 取「离屏幕中心最近」的那一页 ——
    // 横向分页时一页占满屏幕，所以中心页就是当前正在看的那页，
    // 滑动过程中标题也平滑跟随。
    //
    // 点箭头时先自己改 visibleMonth（立即反馈），随后被 layoutInfo 校正。
    var visibleMonth by remember { mutableStateOf(YearMonth.from(selected)) }
    LaunchedEffect(state) {
        snapshotFlow {
            val viewportCenter = (state.layoutInfo.viewportStartOffset +
                state.layoutInfo.viewportEndOffset) / 2
            state.layoutInfo.visibleMonthsInfo.minByOrNull {
                kotlin.math.abs(it.offset + it.size / 2 - viewportCenter)
            }?.month?.yearMonth
        }
            .filterNotNull()
            .collect { visibleMonth = it }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxWidth()) {
            // ── 标题栏：年月 + 快捷跳转 ──────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${visibleMonth.year}年 ${visibleMonth.monthValue}月",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                // 快捷键 —— 同步场景最常用「今天 / 昨天」
                if (selected != today) {
                    TextButton(onClick = {
                        onDateSelected(today)
                        onDismiss()
                    }) {
                        Text("今天", style = MaterialTheme.typography.labelLarge)
                    }
                }
                IconButton(
                    onClick = {
                        val target = visibleMonth.previousMonth
                        if (target >= startMonth) {
                            visibleMonth = target
                            scope.launch { state.animateScrollToMonth(target) }
                        }
                    },
                    enabled = visibleMonth > startMonth,
                ) {
                    Icon(Icons.Rounded.ChevronLeft, contentDescription = "上一月")
                }
                IconButton(
                    onClick = {
                        val target = visibleMonth.nextMonth
                        if (target <= endMonth) {
                            visibleMonth = target
                            scope.launch { state.animateScrollToMonth(target) }
                        }
                    },
                    enabled = visibleMonth < endMonth,
                ) {
                    Icon(Icons.Rounded.ChevronRight, contentDescription = "下一月")
                }
            }

            // ── 星期头（周一开头）──────────────────────────────────
            WeekHeader()

            // ── 月历（横向分页：每个月独立一页）─────────────────────
            //
            // ⚠️ 用 HorizontalCalendar 而不是 VerticalCalendar：
            // 纵向日历会把两个月的网格直接拼在一起（中间没有月份标题），
            // 看起来像「10 月和 11 月连成一片」，而且标题栏的月份和
            // 实际看到的日期对不上。横向分页后每个月独立成页，不会连。
            HorizontalCalendar(
                state = state,
                modifier = Modifier.fillMaxWidth(),
                calendarScrollPaged = true,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 12.dp,
                    vertical = 4.dp,
                ),
                dayContent = { day ->
                    DayCell(
                        day = day,
                        selected = day.date == selected,
                        isToday = day.date == today,
                        enabled = !day.date.isAfter(today),
                        onClick = {
                            onDateSelected(day.date)
                            onDismiss()
                        },
                    )
                },
            )

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun WeekHeader() {
    // 周一 → 周日
    val labels = listOf(
        DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
        DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY,
    ).map { day ->
        when (day) {
            DayOfWeek.MONDAY -> "一"
            DayOfWeek.TUESDAY -> "二"
            DayOfWeek.WEDNESDAY -> "三"
            DayOfWeek.THURSDAY -> "四"
            DayOfWeek.FRIDAY -> "五"
            DayOfWeek.SATURDAY -> "六"
            else -> "日"
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        for (label in labels) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
        }
    }
}



/**
 * 日期格子。
 *
 * 视觉规则：
 * - 选中：实心圆角药丸 + 反色文字
 * - 今天（未选中）：描边圆
 * - 未来：半透明 + 不可点
 */
@Composable
private fun DayCell(
    day: CalendarDay,
    selected: Boolean,
    isToday: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    // ⚠️ 必须区分两种「不是本月的格子」，否则用户分不清归属：
    //   InDate  = 上个月末尾那几天（本月 1 号是周一时，月初那格是上月的）
    //   OutDate = 下个月开头那几天
    // 两者都不可点，但**视觉上和本月一样**，看着就像本月日期。
    val inMonth = day.position == DayPosition.MonthDate
    val clickable = inMonth && enabled

    Box(
        modifier = Modifier
            .padding(2.dp)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(14.dp))
            .then(
                when {
                    selected -> Modifier.background(MaterialTheme.colorScheme.primary)
                    isToday && clickable -> Modifier.border(
                        1.5.dp,
                        MaterialTheme.colorScheme.primary,
                        RoundedCornerShape(14.dp),
                    )
                    else -> Modifier
                }
            )
            .clickable(enabled = clickable, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = day.date.dayOfMonth.toString(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = when {
                selected || isToday -> FontWeight.Bold
                !inMonth -> FontWeight.Light   // 邻月：字更细
                else -> FontWeight.Normal
            },
            color = when {
                selected -> MaterialTheme.colorScheme.onPrimary
                else -> MaterialTheme.colorScheme.onSurface.copy(
                    alpha = dayCellAlpha(inMonth, enabled)
                )
            },
        )
    }
}

/**
 * 日期选择器的可选项范围。
 *
 * - 往前 [monthsBack] 个月
 * - 到今天为止（未来不可选）
 */
internal fun datePickerRange(today: LocalDate, monthsBack: Long = 60): Pair<YearMonth, YearMonth> =
    YearMonth.from(today).minusMonths(monthsBack) to YearMonth.from(today)

/**
 * 日期格子的文字透明度。
 *
 * 三档，从深到浅，一眼能分出：
 * - 本月且可选 → 1.0（正常）
 * - 本月但在���来 → 0.38（次一级）
 * - 邻月（InDate/OutDate）→ 0.25（明显淡出）
 *
 * 邻月最淡，因为它们最容易和本月日期混淆 ——
 * 「26 27 28 29 30 31 1」这行里，前 6 个是上月、最后 1 个是下月。
 */
internal fun dayCellAlpha(inMonth: Boolean, enabled: Boolean): Float = when {
    !inMonth -> 0.25f
    !enabled -> 0.38f
    else -> 1.0f
}

/** 该日期是否可选（不能选未来）。 */
internal fun isDateSelectable(date: LocalDate, today: LocalDate): Boolean = !date.isAfter(today)

/** 星期头顺序 —— 周一开头。 */
internal fun weekHeaderLabels(): List<String> = listOf("一", "二", "三", "四", "五", "六", "日")
