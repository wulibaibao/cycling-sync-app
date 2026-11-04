package com.wulibaibao.cyclicsync.ui

import com.wulibaibao.cyclicsync.data.SyncDirection
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wulibaibao.cyclicsync.data.ActivitySyncStatus
import com.wulibaibao.cyclicsync.data.UnifiedActivity

enum class StatusFilterOption(val label: String) {
    UNSYNCED("⚡ 仅看待同步"),
    WEEK("近 7 天"),
    MONTH("近 30 天"),
    ALL("全部历史(分批)"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityLedgerScreen(
    running: Boolean,
    selectedDirections: Set<SyncDirection> = emptySet(),
    batchProgressText: String,
    batchCurrent: Int,
    batchTotal: Int,
    loading: Boolean,
    activities: List<UnifiedActivity>,
    selectedIds: Set<String>,
    onToggleSelect: (String) -> Unit,
    onSelectAllPending: () -> Unit,
    onClearSelect: () -> Unit,
    onBatchSync: (Platform) -> Unit,
    onSingleSync: (UnifiedActivity, Platform) -> Unit,
    onRefresh: () -> Unit,
    onLoadAllHistory: () -> Unit,
    onStop: () -> Unit,
    onOpenLogs: () -> Unit,
    onSwitchToClassic: () -> Unit,
) {
    val activeSources = selectedDirections.map { it.source }.toSet()
    val activeTargets = selectedDirections.map { it.target }.toSet()

    var sourceFilter by remember { mutableStateOf<Platform?>(null) }
    var statusFilter by remember { mutableStateOf(StatusFilterOption.UNSYNCED) }

    val now = System.currentTimeMillis()
    fun isActPending(act: UnifiedActivity): Boolean {
        val applicableTargets = selectedDirections.filter { it.source == act.source }.map { it.target }.toSet()
        return act.isPendingFor(applicableTargets)
    }

    val filtered = activities.filter { act ->
        if (sourceFilter != null && act.source != sourceFilter) return@filter false
        when (statusFilter) {
            StatusFilterOption.UNSYNCED -> isActPending(act)
            StatusFilterOption.WEEK -> (now - act.startTimeMs) <= 7 * 86400_000L
            StatusFilterOption.MONTH -> (now - act.startTimeMs) <= 30 * 86400_000L
            StatusFilterOption.ALL -> true
        }
    }
    val pendingCount = activities.count { isActPending(it) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(14.dp))
            // 头部标题与快捷动作
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "活动看板",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Text(
                                "PRO",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    val subtitle = when {
                        selectedDirections.isEmpty() -> "未勾选同步方向，点击右上角设置"
                        activeTargets.isNotEmpty() -> "同步目标：${activeTargets.joinToString("、") { it.title }}"
                        else -> "多源骑行运动数据管理与批量同步"
                    }
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                IconButton(onClick = onSwitchToClassic) {
                    Icon(
                        Icons.Rounded.Tune,
                        contentDescription = "经典单日模式",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onOpenLogs) {
                    Icon(
                        Icons.Rounded.Article,
                        contentDescription = "查看日志",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onRefresh) {
                    Icon(
                        Icons.Rounded.Refresh,
                        contentDescription = "刷新活动",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            // 同步进度提示卡片
            if (running) {
                Spacer(Modifier.height(10.dp))
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (batchTotal > 0) "正在批量同步 ($batchCurrent/$batchTotal)" else "正在执行同步...",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedButton(
                                onClick = onStop,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.height(28.dp),
                            ) {
                                Text("停止", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        val progress = if (batchTotal > 0) (batchCurrent.toFloat() / batchTotal).coerceIn(0f, 1f) else 0.5f
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            batchProgressText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // 数据源平台选择 Chips（严格根据勾选的同步源过滤）
            if (activeSources.size > 1 || activeSources.isEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                ) {
                    FilterChip(
                        selected = sourceFilter == null,
                        onClick = { sourceFilter = null },
                        label = { Text("全部 (${activities.size})") },
                    )
                    if (activeSources.isEmpty() || Platform.IGP in activeSources) {
                        FilterChip(
                            selected = sourceFilter == Platform.IGP,
                            onClick = { sourceFilter = Platform.IGP },
                            label = { Text("迹驰 IGPSPORT (${activities.count { it.source == Platform.IGP }})") },
                        )
                    }
                    if (activeSources.isEmpty() || Platform.ONELAP in activeSources) {
                        FilterChip(
                            selected = sourceFilter == Platform.ONELAP,
                            onClick = { sourceFilter = Platform.ONELAP },
                            label = { Text("顽鹿 Onelap (${activities.count { it.source == Platform.ONELAP }})") },
                        )
                    }
                }
            }

            // 状态与时间跨度 Chips
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            ) {
                FilterChip(
                    selected = statusFilter == StatusFilterOption.UNSYNCED,
                    onClick = { statusFilter = StatusFilterOption.UNSYNCED },
                    label = { Text("⚡ 仅看待同步 ($pendingCount)") },
                )
                FilterChip(
                    selected = statusFilter == StatusFilterOption.WEEK,
                    onClick = { statusFilter = StatusFilterOption.WEEK },
                    label = { Text("近 7 天") },
                )
                FilterChip(
                    selected = statusFilter == StatusFilterOption.MONTH,
                    onClick = { statusFilter = StatusFilterOption.MONTH },
                    label = { Text("近 30 天") },
                )
                FilterChip(
                    selected = statusFilter == StatusFilterOption.ALL,
                    onClick = {
                        statusFilter = StatusFilterOption.ALL
                        onLoadAllHistory()
                    },
                    label = { Text("全部历史(分批)") },
                )
            }

            Spacer(Modifier.height(4.dp))

            // 批量控制条
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) {
                val allPendingSelected = pendingCount > 0 && activities.filter {
                    it.targetStates[Platform.XINGZHE]?.status != ActivitySyncStatus.SYNCED
                }.all { it.id in selectedIds }

                Surface(
                    onClick = {
                        if (allPendingSelected) onClearSelect() else onSelectAllPending()
                    },
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Icon(
                            if (allPendingSelected) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (allPendingSelected) "取消全选" else "全选待同步",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "展示 ${filtered.size} 条记录",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(4.dp))

            // 活动卡片列表
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 100.dp),
            ) {
                if (loading && activities.isEmpty()) {
                    item {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(40.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(modifier = Modifier.size(36.dp))
                                Spacer(Modifier.height(14.dp))
                                Text("正在拉取最新骑行数据…", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                } else if (filtered.isEmpty()) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 24.dp),
                        ) {
                            Column(
                                Modifier.padding(28.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                val allSynced = activities.isNotEmpty() && pendingCount == 0
                                Text(
                                    if (allSynced) "🎉 当前活动均已同步" else "当前筛选下暂无活动",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    if (allSynced) "10 月及近期骑行均已在行者找到对应记录，无待同步项"
                                    else "可能是记录都已同步，或未检索到该时间段的数据",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                )
                                Spacer(Modifier.height(16.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (allSynced) {
                                        FilledTonalButton(onClick = { statusFilter = StatusFilterOption.MONTH }) {
                                            Text("查看近 30 天记录")
                                        }
                                        OutlinedButton(onClick = {
                                            statusFilter = StatusFilterOption.ALL
                                            onLoadAllHistory()
                                        }) {
                                            Text("拉取全部历史")
                                        }
                                    } else {
                                        Button(onClick = onRefresh) {
                                            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("从云端刷新")
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    items(filtered, key = { it.id }) { act ->
                        val isSelected = act.id in selectedIds
                        val applicableTargets = selectedDirections
                            .filter { it.source == act.source }
                            .map { it.target }
                            .distinct()
                            .ifEmpty { listOf(Platform.XINGZHE) }
                        ActivityCardView(
                            act = act,
                            targets = applicableTargets,
                            selected = isSelected,
                            onToggle = { onToggleSelect(act.id) },
                            onSingleSync = { target -> onSingleSync(act, target) },
                        )
                    }
                }
            }
        }

        // 底部常驻批量操作栏
        AnimatedVisibility(
            visible = selectedIds.isNotEmpty() && !running,
            enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
            exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            val selectedActivities = activities.filter { it.id in selectedIds }
            val pendingSelectedCount = selectedActivities.count { isActPending(it) }
            val allSelectedSynced = selectedActivities.isNotEmpty() && pendingSelectedCount == 0

            val titleText = when {
                allSelectedSynced -> "已勾选 ${selectedIds.size} 项活动 (均已同步)"
                pendingSelectedCount < selectedIds.size -> "已勾选 ${selectedIds.size} 项活动 ($pendingSelectedCount 项待同步)"
                else -> "已勾选 ${selectedIds.size} 项待同步活动"
            }
            val targetSummary = if (activeTargets.isNotEmpty()) activeTargets.joinToString("、") { it.title } else "行者"
            val buttonText = when {
                allSelectedSynced -> "重新同步验证"
                activeTargets.size > 1 -> "一键全部同步 ($pendingSelectedCount 项)"
                else -> "立即批量同步 ($pendingSelectedCount 项)"
            }

            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 8.dp,
                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            titleText,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            "同步目标：$targetSummary",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(
                        onClick = { onBatchSync(activeTargets.firstOrNull() ?: Platform.XINGZHE) },
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(Icons.Rounded.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(buttonText, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityCardView(
    act: UnifiedActivity,
    targets: List<Platform>,
    selected: Boolean,
    onToggle: () -> Unit,
    onSingleSync: (Platform) -> Unit,
) {
    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(18.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            // 顶栏：多选框 + 平台徽章 + 时间
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (selected) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank,
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = act.source.color.copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, act.source.color.copy(alpha = 0.4f)),
                ) {
                    Text(
                        act.source.title,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = act.source.color,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    act.formattedDate,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))

            // 活动标题
            Text(
                act.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(8.dp))

            // 核心指标三联排
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "${act.distanceKm} km",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "骑行距离",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            act.durationFormatted,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "运动时长",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (act.speedKmH > 0.0) "${act.speedKmH} km/h" else "--",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "平均时速",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // 传感器标签 + 目标平台状态
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (act.hasPower) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0xFFEDE9FE),
                    ) {
                        Text(
                            "功率",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF6D28D9),
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                }
                if (act.hasCadence) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0xFFE0F2FE),
                    ) {
                        Text(
                            "踏频",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF0369A1),
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                }

                Spacer(Modifier.weight(1f))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    targets.forEach { target ->
                        val targetState = act.targetStates[target]
                        val isSynced = targetState?.status == ActivitySyncStatus.SYNCED
                        val isFailed = targetState?.status == ActivitySyncStatus.FAILED

                        when {
                            isSynced -> {
                                Surface(
                                    shape = RoundedCornerShape(20.dp),
                                    color = Color(0xFFECFDF5),
                                    border = BorderStroke(1.dp, Color(0xFFA7F3D0)),
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    ) {
                                        Icon(
                                            Icons.Rounded.CheckCircle,
                                            contentDescription = null,
                                            tint = Color(0xFF10B981),
                                            modifier = Modifier.size(13.dp),
                                        )
                                        Spacer(Modifier.width(3.dp))
                                        Text(
                                            "${target.title} 已同步",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF047857),
                                        )
                                    }
                                }
                            }
                            isFailed -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(
                                        shape = RoundedCornerShape(20.dp),
                                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                                    ) {
                                        Text(
                                            "${target.title} 失败",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                                        )
                                    }
                                    Spacer(Modifier.width(4.dp))
                                    FilledTonalButton(
                                        onClick = { onSingleSync(target) },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        modifier = Modifier.height(26.dp),
                                    ) {
                                        Text("重试", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                            else -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(
                                        shape = RoundedCornerShape(20.dp),
                                        color = Color(0xFFFFFBEB),
                                        border = BorderStroke(1.dp, Color(0xFFFDE68A)),
                                    ) {
                                        Text(
                                            "待同步${target.title}",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFFB45309),
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                                        )
                                    }
                                    Spacer(Modifier.width(4.dp))
                                    FilledTonalButton(
                                        onClick = { onSingleSync(target) },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        modifier = Modifier.height(26.dp),
                                    ) {
                                        Text("同步", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
