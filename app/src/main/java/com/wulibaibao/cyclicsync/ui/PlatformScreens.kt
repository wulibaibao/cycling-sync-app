package com.wulibaibao.cyclicsync.ui

import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalClipboardManager
import kotlinx.coroutines.delay
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.ListAlt
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.TextButton
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Circle
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DirectionsBike
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wulibaibao.cyclicsync.data.PlatformAccount
import com.wulibaibao.cyclicsync.data.SyncDirection
import com.wulibaibao.cyclicsync.data.SyncEvent

// ---------------------------------------------------------------- 平台定义

enum class Platform(
    val title: String,
    val subtitle: String,
    val color: Color,
    val icon: ImageVector,
) {
    ONELAP("顽鹿 OneLap", "数据源 · 原始码表 FIT", Color(0xFF00B39B), Icons.Rounded.DirectionsBike),
    IGP("IGPSPORT", "数据源 · 目标平台", Color(0xFF3B6FF5), Icons.Rounded.PhoneAndroid),
    XINGZHE("行者 XOSS", "目标平台 · 账号密码 / 短信", Color(0xFFFF6B35), Icons.Rounded.VerifiedUser),
}

enum class PlatformStatus { NOT_CONFIGURED, CONFIGURED, VERIFIED, FAILED }

/** 登录方式切换（短信 / 密码） */
enum class LoginMode { SMS, PASSWORD }

data class PlatformState(
    val status: PlatformStatus = PlatformStatus.NOT_CONFIGURED,
    val detail: String = "",
    val verifying: Boolean = false,
    /** 已登录账号的显示名 —— 只展示账号信息，不含任何密钥 */
    val displayName: String = "",
    /** 登录用户的其他可见信息（如 ID） */
    val displayMeta: String = "",
)

/** 三个平台的独立配置 + 独立验证状态 */
data class PlatformStates(
    val onelap: PlatformState = PlatformState(),
    val igp: PlatformState = PlatformState(),
    val xingzhe: PlatformState = PlatformState(),
) {
    fun forPlatform(p: Platform) = when (p) {
        Platform.ONELAP -> onelap
        Platform.IGP -> igp
        Platform.XINGZHE -> xingzhe
    }

    fun with(p: Platform, s: PlatformState) = when (p) {
        Platform.ONELAP -> copy(onelap = s)
        Platform.IGP -> copy(igp = s)
        Platform.XINGZHE -> copy(xingzhe = s)
    }
}

// ---------------------------------------------------------------- 平台列表页

@Composable
fun PlatformListScreen(
    states: PlatformStates,
    onOpen: (Platform) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(20.dp))

        // 顶部状态条 —— 紧凑一行，不占地方
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.DirectionsBike,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "骑行数据同步",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "配置平台账号 → 逐个验证 → 一键同步",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        Text(
            "选择要配置的平台",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "每个平台独立配置与验证，互不影响",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        Platform.entries.forEach { p ->
            PlatformCard(p, states.forPlatform(p), onClick = { onOpen(p) })
            Spacer(Modifier.height(12.dp))
        }

        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.VerifiedUser,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "凭据本机加密存储，不上传服务器",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun PlatformCard(
    platform: Platform,
    state: PlatformState,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(platform.color.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    platform.icon,
                    contentDescription = null,
                    tint = platform.color,
                    modifier = Modifier.size(26.dp),
                )
            }

            Spacer(Modifier.width(16.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    platform.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    // 有账号名就显示账号名，否则显示状态描述
                    state.displayName.ifBlank {
                        state.detail.ifEmpty { platform.subtitle }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.status == PlatformStatus.VERIFIED)
                        MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (state.status == PlatformStatus.VERIFIED)
                        FontWeight.Medium else FontWeight.Normal,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.width(12.dp))

            StatusBadge(state, platform)
        }
    }
}

@Composable
private fun StatusBadge(state: PlatformState, platform: Platform) {
    val bg: Color
    val fg: Color
    when (state.status) {
        PlatformStatus.VERIFIED -> {
            bg = Color(0xFF2FB344).copy(alpha = 0.15f)
            fg = Color(0xFF1E7A2E)
        }
        PlatformStatus.FAILED -> {
            bg = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
            fg = MaterialTheme.colorScheme.error
        }
        PlatformStatus.CONFIGURED -> {
            bg = platform.color.copy(alpha = 0.15f)
            fg = platform.color
        }
        else -> {
            bg = MaterialTheme.colorScheme.surfaceVariant
            fg = MaterialTheme.colorScheme.onSurfaceVariant
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, color = bg, modifier = Modifier.size(28.dp)) {
            Box(contentAlignment = Alignment.Center) {
                if (state.verifying) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(15.dp),
                        strokeWidth = 2.dp,
                        color = fg,
                    )
                } else {
                    Icon(
                        imageVector = when (state.status) {
                            // 已验证：绿色对勾
                            PlatformStatus.VERIFIED -> Icons.Rounded.CheckCircle
                            // 失败：红色感叹号
                            PlatformStatus.FAILED -> Icons.Rounded.ErrorOutline
                            // 已配置：实心圆点
                            PlatformStatus.CONFIGURED -> Icons.Rounded.Circle
                            // 未配置：空心圈
                            else -> Icons.Rounded.RadioButtonUnchecked
                        },
                        contentDescription = null,
                        tint = fg,
                        modifier = Modifier.size(
                            if (state.status == PlatformStatus.CONFIGURED) 12.dp else 17.dp,
                        ),
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            when (state.status) {
                PlatformStatus.VERIFIED -> "已验证"
                PlatformStatus.FAILED -> "失败"
                PlatformStatus.CONFIGURED -> "已配置"
                else -> "未配置"
            },
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = fg,
        )
    }
}

// ---------------------------------------------------------------- 登录方式切换

/** 短信 / 密码 切换段控件 —— 代替上下堆叠，节省空间 */
@Composable
private fun LoginModeTabs(
    selected: LoginMode,
    onSelect: (LoginMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        LoginMode.entries.forEach { mode ->
            val active = mode == selected
            Surface(
                onClick = { onSelect(mode) },
                shape = RoundedCornerShape(13.dp),
                color = if (active) MaterialTheme.colorScheme.surface
                else androidx.compose.ui.graphics.Color.Transparent,
                shadowElevation = if (active) 2.dp else 0.dp,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = when (mode) {
                        LoginMode.SMS -> "短信验证码"
                        LoginMode.PASSWORD -> "账号密码"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    color = if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 11.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 已登录卡片

/**
 * 已登录账号信息卡片。
 *
 * ⚠️ 只展示账号信息（用户名 / 登录账户 / ID），**绝不显示 sessionid、密码、token**。
 */
@Composable
private fun AccountInfoCard(
    platform: Platform,
    displayName: String,
    displayMeta: String,
    accountLabel: String,
    accountValue: String,
    nickname: String,
    onLogout: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = platform.color.copy(alpha = 0.10f),
        ),
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, platform.color.copy(alpha = 0.35f)),
    ) {
        Column(Modifier.padding(22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(platform.color),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(26.dp),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "已登录",
                        style = MaterialTheme.typography.labelLarge,
                        color = platform.color,
                        fontWeight = FontWeight.Bold,
                    )
                    if (displayName.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            displayName,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            // 账号明细
            InfoRow(label = accountLabel, value = accountValue)
            if (nickname.isNotBlank() && nickname != accountValue) {
                Spacer(Modifier.height(10.dp))
                InfoRow(label = "昵称", value = nickname)
            }
            if (displayMeta.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                InfoRow(label = "用户 ID", value = displayMeta.removePrefix("ID "))
            }

            Spacer(Modifier.height(20.dp))

            OutlinedButton(
                onClick = onLogout,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 13.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Icon(Icons.Rounded.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("退出登录")
            }
        }
    }
}

/** 账号明细行 */
@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.35f))
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(80.dp),
        )
        Text(
            value.ifBlank { "—" },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------------------------------------------------------------- 输入框清除按钮

/** 输入框右上角的一键清除 */
@Composable
private fun ClearButton(value: String, onClear: () -> Unit): (@Composable () -> Unit)? {
    if (value.isEmpty()) return null
    return {
        IconButton(onClick = onClear) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = "清除",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

// ---------------------------------------------------------------- 单平台配置页// ---------------------------------------------------------------- 单平台配置页

@Composable
fun PlatformConfigScreen(
    platform: Platform,
    account: PlatformAccount,
    state: PlatformState,
    onBack: () -> Unit,
    onSendSms: (String) -> Unit,
    /** (手机号, 验证码) —— 手机号必须一起回传：它只存在于界面本地状态，
     *  没有写进已保存的 account，Activity 侧拿不到就无法校验和登录。 */
    onVerifyCode: (String, String) -> Unit,
    onLogin: (String, String) -> Unit,
    onPasswordLogin: (String, String) -> Unit,
    onLoggedOut: (Platform) -> Unit,
) {
    var onelapAccount by remember { mutableStateOf(account.onelapAccount) }
    var onelapPassword by remember { mutableStateOf(account.onelapPassword) }
    var igpPhone by remember { mutableStateOf(account.igpPhone) }
    var igpCode by remember { mutableStateOf("") }
    var igpAccount by remember { mutableStateOf(account.igpAccount) }
    var igpPassword by remember { mutableStateOf(account.igpPassword) }
    var xzAccount by remember { mutableStateOf(account.xingzheAccount) }
    var xzPassword by remember { mutableStateOf(account.xingzhePassword) }
    var xzMobile by remember { mutableStateOf(account.xingzheMobile) }
    var xzCode by remember { mutableStateOf("") }
    // 行者/IGPS 用 Tab 切换登录方式，避免上下堆叠占空间
    var loginMode by remember { mutableStateOf(LoginMode.SMS) }

    // 已登录 → 只展示账号信息，完全不显示登录表单
    // CONFIGURED 也算已登录 —— 重装/重启后凭据恢复时状态是 CONFIGURED，
    // 若只认 VERIFIED 会退回登录表单，等于让用户重新配一遍。
    val loggedIn = state.status == PlatformStatus.VERIFIED ||
        state.status == PlatformStatus.CONFIGURED

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "返回",
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }

        Spacer(Modifier.height(8.dp))

        if (loggedIn) {
            // ===== 已登录：只展示账号信息 =====
            AccountInfoCard(
                platform = platform,
                displayName = state.displayName,
                displayMeta = state.displayMeta,
                accountLabel = when (platform) {
                    Platform.ONELAP -> "登录账户"
                    Platform.IGP -> "登录手机号"
                    Platform.XINGZHE -> "登录账户"
                },
                accountValue = when (platform) {
                    Platform.ONELAP -> account.onelapAccount
                    Platform.IGP -> if (account.igpAccount.isNotBlank()) account.igpAccount else account.igpPhone
                    Platform.XINGZHE -> if (account.xingzheUsername.isNotBlank()) account.xingzheUsername
                    else account.xingzheAccount.ifBlank { account.xingzheMobile }
                },
                nickname = when (platform) {
                    Platform.ONELAP -> ""
                    Platform.IGP -> state.displayName.takeIf { it != account.igpPhone } ?: ""
                    Platform.XINGZHE -> state.displayName.takeIf { it != account.xingzheAccount } ?: ""
                },
                onLogout = { onLoggedOut(platform) },
            )
            Spacer(Modifier.height(28.dp))
            return@Column
        }

        // ===== 未登录：登录表单 =====
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when (platform) {
                    Platform.ONELAP -> {
                        OutlinedTextField(
                            value = onelapAccount,
                            onValueChange = { onelapAccount = it },
                            label = { Text("账号（手机号 / 邮箱）") },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            trailingIcon = ClearButton(onelapAccount) { onelapAccount = "" },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = onelapPassword,
                            onValueChange = { onelapPassword = it },
                            label = { Text("密码") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            shape = RoundedCornerShape(16.dp),
                            trailingIcon = ClearButton(onelapPassword) { onelapPassword = "" },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = { onLogin(onelapAccount.trim(), onelapPassword) },
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(vertical = 14.dp),
                        ) { Text("登录") }
                    }

                    Platform.IGP -> {
                        LoginModeTabs(loginMode) { loginMode = it }
                        when (loginMode) {
                            LoginMode.SMS -> {
                                OutlinedTextField(
                                    value = igpPhone,
                                    onValueChange = { igpPhone = it },
                                    label = { Text("手机号") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(16.dp),
                                    trailingIcon = ClearButton(igpPhone) { igpPhone = "" },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedTextField(
                                    value = igpCode,
                                    onValueChange = { igpCode = it },
                                    label = { Text("短信验证码") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(16.dp),
                                    trailingIcon = ClearButton(igpCode) { igpCode = "" },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    SmsCountdownButton(
                                        phone = igpPhone,
                                        onSend = onSendSms,
                                        modifier = Modifier.weight(1f),
                                    )
                                    FilledTonalButton(
                                        onClick = { onVerifyCode(igpPhone, igpCode) },
                                        shape = RoundedCornerShape(16.dp),
                                        modifier = Modifier.weight(1f),
                                    ) { Text("登录") }
                                }
                            }
                            LoginMode.PASSWORD -> {
                                OutlinedTextField(
                                    value = igpAccount,
                                    onValueChange = { igpAccount = it },
                                    label = { Text("账号（手机号 / 邮箱）") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(16.dp),
                                    trailingIcon = ClearButton(igpAccount) { igpAccount = "" },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedTextField(
                                    value = igpPassword,
                                    onValueChange = { igpPassword = it },
                                    label = { Text("密码") },
                                    singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                    shape = RoundedCornerShape(16.dp),
                                    trailingIcon = ClearButton(igpPassword) { igpPassword = "" },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Button(
                                    onClick = { onPasswordLogin(igpAccount.trim(), igpPassword) },
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                    contentPadding = PaddingValues(vertical = 14.dp),
                                ) { Text("登录") }
                            }
                        }
                    }

                    Platform.XINGZHE -> {
                        LoginModeTabs(loginMode) { loginMode = it }
                        when (loginMode) {
                            LoginMode.SMS -> {
                                OutlinedTextField(
                                    value = xzMobile,
                                    onValueChange = { xzMobile = it },
                                    label = { Text("手机号") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(16.dp),
                                    trailingIcon = ClearButton(xzMobile) { xzMobile = "" },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedTextField(
                                    value = xzCode,
                                    onValueChange = { xzCode = it },
                                    label = { Text("短信验证码") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(16.dp),
                                    trailingIcon = ClearButton(xzCode) { xzCode = "" },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    SmsCountdownButton(
                                        phone = xzMobile,
                                        onSend = onSendSms,
                                        modifier = Modifier.weight(1f),
                                    )
                                    FilledTonalButton(
                                        onClick = { onVerifyCode(xzMobile, xzCode) },
                                        shape = RoundedCornerShape(16.dp),
                                        modifier = Modifier.weight(1f),
                                    ) { Text("登录") }
                                }
                            }
                            LoginMode.PASSWORD -> {
                                OutlinedTextField(
                                    value = xzAccount,
                                    onValueChange = { xzAccount = it },
                                    label = { Text("账号（手机号 / 邮箱）") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(16.dp),
                                    trailingIcon = ClearButton(xzAccount) { xzAccount = "" },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedTextField(
                                    value = xzPassword,
                                    onValueChange = { xzPassword = it },
                                    label = { Text("密码") },
                                    singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                    shape = RoundedCornerShape(16.dp),
                                    trailingIcon = ClearButton(xzPassword) { xzPassword = "" },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Button(
                                    onClick = { onLogin(xzAccount.trim(), xzPassword) },
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                    contentPadding = PaddingValues(vertical = 14.dp),
                                ) { Text("登录") }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // 登录结果（只在失败时显示 —— 成功会切到账号信息页）
        AnimatedVisibility(
            visible = state.status == PlatformStatus.FAILED && state.detail.isNotEmpty(),
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        state.detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

// ---------------------------------------------------------------- 同步页

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(
    running: Boolean,
    selected: Set<SyncDirection>,
    states: PlatformStates,
    date: LocalDate,
    onDateChange: (LocalDate) -> Unit,
    onToggle: (SyncDirection) -> Unit,
    onSync: () -> Unit,
    onStop: () -> Unit,
    onOpenLogs: () -> Unit,
) {
    var showDatePicker by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(20.dp))
        // 标题行 —— 右侧放日志入口图标（固定在顶部，不会被同步日志挤下去）
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "同步",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "选择同步方向",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onOpenLogs) {
                Icon(
                    Icons.Rounded.Article,
                    contentDescription = "查看完整日志",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        Spacer(Modifier.height(14.dp))

        // ===== 日期选择 =====
        // 同步日期 —— 整块可点，弹日历选任意一天
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .clickable { showDatePicker = true },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(42.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Rounded.CalendarToday,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "同步日期",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        dateText(date),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    "选择",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Text(
            "同步方向",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(10.dp))

        SyncDirection.entries.forEach { dir ->
            val enabled = directionEnabled(dir, states)
            val isOn = dir in selected
            val alpha by animateFloatAsState(if (isOn) 1f else 0.7f, label = "alpha")

            Surface(
                onClick = { if (enabled) onToggle(dir) },
                shape = RoundedCornerShape(22.dp),
                color = if (isOn)
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                else MaterialTheme.colorScheme.surface,
                border = if (isOn)
                    BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (isOn) Icons.Rounded.CheckCircle
                        else Icons.Rounded.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = if (isOn) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    // 平台名在前（源 → 目标），平台用品牌色
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            dir.source.title,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isOn) FontWeight.Bold else FontWeight.Medium,
                            color = dir.source.color.copy(alpha = alpha),
                        )
                        Text(
                            "  →  ",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                        )
                        Text(
                            dir.target.title,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isOn) FontWeight.Bold else FontWeight.Medium,
                            color = dir.target.color.copy(alpha = alpha),
                        )
                    }
                    if (!enabled) {
                        Text(
                            "需先配置",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(2.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onSync,
                enabled = !running && selected.isNotEmpty(),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 14.dp),
            ) {
                if (running) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(Icons.Rounded.Sync, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("开始同步", fontWeight = FontWeight.Bold)
                }
            }
            if (running) {
                OutlinedButton(
                    onClick = onStop,
                    shape = RoundedCornerShape(18.dp),
                    contentPadding = PaddingValues(horizontal = 22.dp, vertical = 14.dp),
                ) { Text("停止") }
            }
        }

        Spacer(Modifier.height(16.dp))
    }

    // 日期选择对话框 —— 可以直接跳到任意一天
    if (showDatePicker) {
        val today = LocalDate.now()
        val initialMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = initialMillis,
            selectableDates = object : androidx.compose.material3.SelectableDates {
                // 不能选未来
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val d = Instant.ofEpochMilli(utcTimeMillis)
                        .atZone(ZoneOffset.UTC).toLocalDate()
                    return !d.isAfter(today)
                }

                override fun isSelectableYear(year: Int): Boolean = year <= today.year
            },
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { ms ->
                        val picked = Instant.ofEpochMilli(ms)
                            .atZone(ZoneOffset.UTC).toLocalDate()
                        onDateChange(picked)
                    }
                    showDatePicker = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("取消") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}



/**
 * 获取验证码按钮 —— 内置 60 秒倒计时。
 *
 * 倒计时期间禁用，避免用户连点导致服务端限流。
 */
@Composable
private fun SmsCountdownButton(
    phone: String,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    countdownSeconds: Int = 60,
) {
    var remaining by remember { mutableStateOf(0) }

    // 倒计时：每秒减 1，归零后恢复
    LaunchedEffect(remaining) {
        if (remaining > 0) {
            delay(1000)
            remaining -= 1
        }
    }

    FilledTonalButton(
        onClick = {
            if (remaining > 0) return@FilledTonalButton
            onSend(phone)
            remaining = countdownSeconds
        },
        enabled = enabled && remaining == 0 && phone.isNotBlank(),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier,
    ) {
        Text(
            when {
                remaining > 0 -> "${remaining} 秒后重发"
                else -> "获取验证码"
            },
        )
    }
}

/** 独立日志页 —— 完整事件列表 + 清除 */
@Composable
fun LogsScreen(
    events: List<SyncEvent>,
    onBack: () -> Unit,
    onClear: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "同步日志",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "共 ${events.size} 条",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (events.isNotEmpty()) {
                val ctx = LocalClipboardManager.current
                // 复制全部 —— 一键把整份日志带走
                IconButton(
                    onClick = {
                        ctx.setText(AnnotatedString(events.joinToString("\n") { it.toPlainText() }))
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.Rounded.ContentCopy,
                        contentDescription = "复制全部日志",
                        modifier = Modifier.size(19.dp),
                    )
                }
                TextButton(onClick = onClear) {
                    Icon(
                        Icons.Rounded.DeleteSweep,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("清除")
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        if (events.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Rounded.Article,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.size(52.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "还没有日志",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "跑一次同步就会记录在这里",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item {
                    GroupedEventList(events)
                }
            }
        }
    }
}



/**
 * 日志分组 —— 按同步方向把连续的记录包进同一个背景块。
 *
 * 同一条链路的日志用同一个背景色 + 左侧色条，一眼看出哪几条属于同一组。
 */
@Composable
private fun GroupedEventList(
    events: List<SyncEvent>,
    modifier: Modifier = Modifier,
) {
    // 切成 [(方向, 该组的记录)] —— 无方向的（Info/Done/Fatal）单独成组
    val groups = remember(events) {
        val out = mutableListOf<Pair<SyncDirection?, List<SyncEvent>>>()
        events.forEach { ev ->
            val dir = when (ev) {
                is SyncEvent.Step -> ev.direction
                is SyncEvent.Ride -> ev.direction
                is SyncEvent.Summary -> ev.direction
                else -> null
            }
            if (dir != null && out.isNotEmpty() && out.last().first == dir) {
                out[out.lastIndex] = dir to (out.last().second + ev)
            } else {
                out += dir to listOf(ev)
            }
        }
        out
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        groups.forEach { (dir, groupEvents) ->
            if (dir == null) {
                // 无方向的事件（总览/致命错误）—— 直接平铺
                groupEvents.forEach { EventRow(it) }
            } else {
                DirectionGroup(dir = dir, events = groupEvents)
            }
        }
    }
}

/**
 * 一个方向的日志组 —— 同色背景 + 左侧色条 + 组头。
 */
@Composable
private fun DirectionGroup(
    dir: SyncDirection,
    events: List<SyncEvent>,
) {
    val accent = dir.groupColor
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = accent.copy(alpha = 0.08f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
        ) {
            // 左侧色条 —— 组的标识（IntrinsicSize.Min 让色条撑满整组高度）
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(accent.copy(alpha = 0.55f)),
            )
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // 组头：方向名 + 本组条数
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DirectionChip(dir)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${events.size} 条",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                events.forEach { EventRow(it, inGroup = true) }
            }
        }
    }
}

/**
 * 同步方向标签 —— 平台名用品牌色，一眼区分是哪条链路。
 */
@Composable
private fun DirectionChip(dir: SyncDirection) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = dir.source.color.copy(alpha = 0.16f),
        modifier = Modifier,
    ) {
        Row(
            Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                dir.source.title,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = dir.source.color,
            )
            Text(
                " → ",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                dir.target.title,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = dir.target.color,
            )
        }
    }
}

/** 事件转纯文本 —— 用于复制 */
private fun SyncEvent.toPlainText(): String = when (this) {
    is SyncEvent.Info -> message
    is SyncEvent.Step -> "[${direction.label}] $text"
    is SyncEvent.Ride -> "[${direction.label}] $date $name ${"%.1f".format(distanceKm)}km — $status" +
        (if (errorDetail.isNotBlank()) "\n    $errorDetail" else "")
    is SyncEvent.Summary -> "[${direction.label}] 上传 $uploaded · 跳过 $skipped · 失败 $failed"
    is SyncEvent.Done -> "同步完成：上传 $totalUploaded · 跳过 $totalSkipped · 失败 $totalFailed"
    is SyncEvent.Fatal -> "错误：$message"
}

/** 日期显示：今天 / 昨天 / M月d日（周X） */
private fun dateText(d: LocalDate): String {
    val today = LocalDate.now()
    val week = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[d.dayOfWeek.value - 1]
    return when (d) {
        today -> "今天 · ${d.monthValue}月${d.dayOfMonth}日 $week"
        today.minusDays(1) -> "昨天 · ${d.monthValue}月${d.dayOfMonth}日 $week"
        else -> "${d.monthValue}月${d.dayOfMonth}日 $week"
    }
}

/** 平台就绪卡 —— 平台优先，同步页顶部 */
@Composable
private fun PlatformReadyCard(
    platform: Platform,
    status: PlatformStatus,
    displayName: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val statusColor = when (status) {
        PlatformStatus.VERIFIED -> Color(0xFF2FB344)
        PlatformStatus.FAILED -> MaterialTheme.colorScheme.error
        PlatformStatus.CONFIGURED -> platform.color
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier,
    ) {
        Column(
            Modifier.padding(14.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(platform.color.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        platform.icon,
                        contentDescription = null,
                        tint = platform.color,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(statusColor),
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                platform.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                when (status) {
                    PlatformStatus.VERIFIED -> displayName.ifEmpty { "已连接" }
                    PlatformStatus.FAILED -> "登录失败"
                    PlatformStatus.CONFIGURED -> "已配置"
                    else -> "未登录"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 链路所需的平台是否都已配置 */
private fun directionEnabled(dir: SyncDirection, states: PlatformStates): Boolean =
    states.forPlatform(dir.source).status != PlatformStatus.NOT_CONFIGURED &&
        states.forPlatform(dir.target).status != PlatformStatus.NOT_CONFIGURED

@Composable
private fun EventRow(ev: SyncEvent, inGroup: Boolean = false) {
    when (ev) {
        is SyncEvent.Info -> Text(ev.message, style = MaterialTheme.typography.bodyMedium)

        is SyncEvent.Step -> {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.25f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!inGroup) DirectionChip(ev.direction)
                        Spacer(Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        ev.text,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        is SyncEvent.Ride -> {
            val isError = ev.status.contains("失败")
            val isUploaded = ev.status.contains("已上传")
            val accent = when {
                isError -> MaterialTheme.colorScheme.error
                isUploaded -> Color(0xFF2FB344)
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            var expanded by remember(ev.errorDetail) { mutableStateOf(false) }
            val ctx = LocalClipboardManager.current

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (isError)
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.28f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                border = if (isError)
                    BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                else null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    // 方向标签 —— 一眼看出是哪条链路
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (!inGroup) DirectionChip(ev.direction)
                        Spacer(Modifier.weight(1f))
                        Text(
                            ev.date,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            ev.status,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = accent,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        ev.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "%.1f km".format(ev.distanceKm),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.weight(1f))

                        // 复制按钮 —— 复制完整错误信息
                        if (ev.errorDetail.isNotBlank()) {
                            IconButton(
                                onClick = {
                                    ctx.setText(AnnotatedString(ev.errorDetail))
                                },
                                modifier = Modifier.size(26.dp),
                            ) {
                                Icon(
                                    Icons.Rounded.ContentCopy,
                                    contentDescription = "复制错误详情",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(15.dp),
                                )
                            }
                        }
                    }

                    // 错误详情：默认显示一行，可展开
                    if (ev.errorDetail.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.1f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                Text(
                                    ev.errorDetail,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (ev.errorDetail.length > 60) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        if (expanded) "收起" else "展开完整错误",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.clickable { expanded = !expanded },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        is SyncEvent.Summary -> {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        ev.direction.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "上传 ${ev.uploaded} · 跳过 ${ev.skipped} · 失败 ${ev.failed}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        is SyncEvent.Done -> {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "同步完成：上传 ${ev.totalUploaded} · 跳过 ${ev.totalSkipped} · 失败 ${ev.totalFailed}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        is SyncEvent.Fatal -> {
            val ctx = LocalClipboardManager.current
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        ev.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { ctx.setText(AnnotatedString(ev.message)) },
                        modifier = Modifier.size(26.dp),
                    ) {
                        Icon(
                            Icons.Rounded.ContentCopy,
                            contentDescription = "复制",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
            }
        }
    }
}