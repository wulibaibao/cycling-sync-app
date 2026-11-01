package com.wulibaibao.cyclicsync.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.wulibaibao.cyclicsync.data.PlatformAccount
import com.wulibaibao.cyclicsync.data.SyncDirection
import com.wulibaibao.cyclicsync.data.SyncEvent
import java.time.LocalDate

/**
 * Compose Preview —— 只放在 debug 源集，不进 release 包。
 *
 * 用途：无真机 / 无模拟器时，用 `android preview render` 离线渲染三个界面，
 * 验证 UI 代码可正常组合执行。
 */

private val previewStates = PlatformStates(
    onelap = PlatformState(
        status = PlatformStatus.VERIFIED,
        detail = "验证通过，可正常读取骑行记录（本页 20 条）",
        displayName = "wulibaibao",
    ),
    igp = PlatformState(
        status = PlatformStatus.CONFIGURED,
        detail = "已保存登录信息",
        displayName = "138****8888",
    ),
    xingzhe = PlatformState(
        status = PlatformStatus.NOT_CONFIGURED,
        detail = "未配置",
    ),
)

private val previewEvents = listOf(
    SyncEvent.Step(SyncDirection.ONELAP_TO_XINGZHE, "登录顽鹿 OneLap"),
    SyncEvent.Ride(
        direction = SyncDirection.ONELAP_TO_XINGZHE,
        date = "2026-11-01",
        name = "晨骑 · 滨江绿道",
        distanceKm = 24.6,
        status = "已上传",
    ),
    SyncEvent.Ride(
        direction = SyncDirection.ONELAP_TO_XINGZHE,
        date = "2026-11-01",
        name = "午骑 · 公司往返",
        distanceKm = 12.3,
        status = "已跳过（重复）",
    ),
    SyncEvent.Ride(
        direction = SyncDirection.IGP_TO_XINGZHE,
        date = "2026-11-01",
        name = "夜骑 · 环城",
        distanceKm = 31.8,
        status = "上传失败",
        errorDetail = "HTTP 403 SignatureDoesNotMatch（平台签名密钥未配置）",
    ),
    SyncEvent.Summary(SyncDirection.ONELAP_TO_XINGZHE, uploaded = 1, skipped = 1, failed = 0),
    SyncEvent.Summary(SyncDirection.IGP_TO_XINGZHE, uploaded = 0, skipped = 0, failed = 1),
    SyncEvent.Done(totalUploaded = 1, totalSkipped = 1, totalFailed = 1),
)

@Preview(name = "同步页", showBackground = true)
@Composable
private fun SyncScreenPreview() {
    CyclingSyncTheme {
        SyncScreen(
            running = false,
            selected = setOf(SyncDirection.ONELAP_TO_XINGZHE, SyncDirection.IGP_TO_XINGZHE),
            states = previewStates,
            date = LocalDate.of(2026, 11, 1),
            onDateChange = {},
            onToggle = {},
            onSync = {},
            onStop = {},
            onOpenLogs = {},
        )
    }
}

@Preview(name = "平台页", showBackground = true)
@Composable
private fun PlatformListScreenPreview() {
    CyclingSyncTheme {
        PlatformListScreen(
            states = previewStates,
            onOpen = {},
        )
    }
}

@Preview(name = "平台配置页", showBackground = true)
@Composable
private fun PlatformConfigScreenPreview() {
    CyclingSyncTheme {
        PlatformConfigScreen(
            platform = Platform.ONELAP,
            account = PlatformAccount(
                onelapAccount = "wulibaibao",
                onelapPassword = "demo-password",
            ),
            state = PlatformState(
                status = PlatformStatus.NOT_CONFIGURED,
                detail = "未配置",
            ),
            onBack = {},
            onSendSms = {},
            onVerifyCode = { _, _ -> },
            onLogin = { _, _ -> },
            onPasswordLogin = { _, _ -> },
            onLoggedOut = {},
        )
    }
}

@Preview(name = "日志页", showBackground = true)
@Composable
private fun LogsScreenPreview() {
    CyclingSyncTheme {
        LogsScreen(
            events = previewEvents,
            running = false,
            onBack = {},
            onClear = {},
        )
    }
}
