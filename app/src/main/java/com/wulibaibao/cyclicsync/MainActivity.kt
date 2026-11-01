package com.wulibaibao.cyclicsync

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.wulibaibao.cyclicsync.data.CredentialStore
import com.wulibaibao.cyclicsync.data.LogStore
import com.wulibaibao.cyclicsync.data.UiPrefs
import com.wulibaibao.cyclicsync.data.PlatformAccount
import com.wulibaibao.cyclicsync.data.SyncDirection
import com.wulibaibao.cyclicsync.data.SyncEngine
import com.wulibaibao.cyclicsync.data.SyncEvent
import com.wulibaibao.cyclicsync.platform.IgpClient
import com.wulibaibao.cyclicsync.platform.OneLapClient
import com.wulibaibao.cyclicsync.platform.XingzheClient
import com.wulibaibao.cyclicsync.ui.CyclingSyncTheme
import com.wulibaibao.cyclicsync.ui.LogsScreen
import com.wulibaibao.cyclicsync.ui.Platform
import com.wulibaibao.cyclicsync.ui.PlatformConfigScreen
import com.wulibaibao.cyclicsync.ui.PlatformListScreen
import com.wulibaibao.cyclicsync.ui.PlatformState
import com.wulibaibao.cyclicsync.ui.PlatformStatus
import com.wulibaibao.cyclicsync.ui.PlatformStates
import com.wulibaibao.cyclicsync.ui.SyncScreen
import kotlinx.coroutines.launch

/** 页面路由 */
private sealed interface Screen {
    data object Platforms : Screen
    data class Config(val platform: Platform) : Screen
    data object Sync : Screen
    data object Logs : Screen
}

class MainActivity : ComponentActivity() {
    private lateinit var uiPrefs: UiPrefs

    private lateinit var store: CredentialStore

    /** 同步日志持久化 —— 否则 App 被杀掉后日志就没了 */
    private lateinit var logStore: LogStore

    /** 各平台独立状态 —— 放在 Activity 层，普通方法（登录回调等）也要访问 */
    var states by mutableStateOf(PlatformStates())

    /**
     * 全局异常兜底。
     *
     * 没有这个的话，任何未捕获异常（比如网络库内部抛的）都会直接
     * 杀掉进程 —— 用户看到的就是「App 莫名其妙被关掉」。
     */
    private fun installCrashGuard() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                Toast.makeText(
                    this,
                    "出错了：${throwable.javaClass.simpleName}",
                    Toast.LENGTH_LONG,
                ).show()
            }
            // 交给系统处理（保留崩溃日志），但先记一笔
            runCatching { android.util.Log.e("CyclingSync", "uncaught", throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = CredentialStore(this)
        logStore = LogStore(this)
        uiPrefs = UiPrefs(this)

        setContent {
            CyclingSyncTheme {
                var screen by remember { mutableStateOf<Screen>(Screen.Sync) }
                var tab by remember { mutableStateOf(0) }
                // ⚠️ 凭据读取可能触发 Keystore 操作，**不能**在组合期同步执行 ——
    // 抛异常会直接杀掉进程（用户看到的是 App 被关掉）。
    var account by remember { mutableStateOf(PlatformAccount()) }
    var credsLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // ⚠️ 整个包 try/catch —— 协程里的未捕获异常会传播到主线程
        // 直接杀掉进程（用户看到的是「一打开就闪退」）。
        try {
            val loaded = withContext(Dispatchers.IO) { store.load() }
            account = loaded
            // 从已保存的凭据恢复各平台登录态 —— 否则重装/重启后
            // 凭据还在但界面显示「未配置」，用户以为要重新配。
            states = restoreStates(loaded)
        } catch (e: Exception) {
            android.util.Log.e("CyclingSync", "load creds failed", e)
        }
        credsLoaded = true
    }
                var running by remember { mutableStateOf(false) }
                var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
                val events = remember { mutableStateListOf<SyncEvent>() }
                // 恢复上次的日志 —— 否则 App 被杀掉后日志就没了
                LaunchedEffect(Unit) {
                    try {
                        val saved = withContext(Dispatchers.IO) { logStore.load() }
                        if (saved.isNotEmpty()) {
                            events.clear()
                            events.addAll(saved)
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("CyclingSync", "load log failed", e)
                    }
                }
                // 同步方向 —— 默认**一个都不选**，用户自己勾。
                // （之前默认选中「IGP→行者」和「OneLap→行者」两条，
                //   首次打开就带着方向，容易误同步。）
                // 用户勾过之后会存到本地偏好，下次打开自动恢复。
                var selected by remember { mutableStateOf(emptySet<SyncDirection>()) }
                var prefsLoaded by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    try {
                        val saved = withContext(Dispatchers.IO) { uiPrefs.loadDirections() }
                            .mapNotNull { n -> runCatching { SyncDirection.valueOf(n) }.getOrNull() }
                            .toSet()
                        if (saved.isNotEmpty()) selected = saved
                    } catch (e: Exception) {
                        android.util.Log.e("CyclingSync", "load prefs failed", e)
                    }
                    prefsLoaded = true
                }
                // 同步日期，默认当天
                var syncDate by remember { mutableStateOf(java.time.LocalDate.now()) }

                fun persist(a: PlatformAccount) {
                    account = a
                    lifecycleScope.launch {
                        store.save(a)
                        account = withContext(Dispatchers.IO) { store.load() }
                    }
                }

                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = tab == 0,
                                onClick = {
                                    tab = 0
                                    screen = Screen.Sync
                                },
                                icon = { Icon(Icons.Rounded.Sync, contentDescription = null) },
                                label = { Text("同步") },
                            )
                            NavigationBarItem(
                                selected = tab == 1,
                                onClick = {
                                    tab = 1
                                    screen = Screen.Platforms
                                },
                                icon = { Icon(Icons.Rounded.Tune, contentDescription = null) },
                                label = { Text("平台") },
                            )
                        }
                    },
                ) { pad ->
                    Box(Modifier.padding(pad)) {
                        when (val s = screen) {
                            is Screen.Platforms -> PlatformListScreen(
                                states = states,
                                onOpen = { p ->
                                    tab = 0
                                    screen = Screen.Config(p)
                                },
                            )

                            is Screen.Config -> PlatformConfigScreen(
                                platform = s.platform,
                                account = account,
                                state = states.forPlatform(s.platform),
                                onBack = { screen = Screen.Platforms },
                                onSendSms = { phone ->
                                    if (s.platform == Platform.XINGZHE) {
                                        requestXingzheSms(phone)
                                    } else {
                                        requestIgpSms(phone)
                                    }
                                },
                                onVerifyCode = { phone, code ->
                                    if (s.platform == Platform.XINGZHE) {
                                        verifyXingzheCode(phone, code, account) { updated ->
                                            persist(updated)
                                            states = states.with(
                                                Platform.XINGZHE,
                                                PlatformState(
                                                    status = PlatformStatus.VERIFIED,
                                                    detail = "登录成功",
                                                    displayName = updated.displayName(Platform.XINGZHE),
                                                    displayMeta = if (updated.xingzheUserId.isNotBlank())
                                                        "ID ${updated.xingzheUserId}" else "",
                                                ),
                                            )
                                        }
                                    } else {
                                        verifyIgpCode(phone, code, account) { updated ->
                                            persist(updated)
                                            states = states.with(
                                                Platform.IGP,
                                                PlatformState(
                                                    status = PlatformStatus.VERIFIED,
                                                    detail = "登录成功，token 已保存",
                                                    displayName = updated.igpPhone,
                                                ),
                                            )
                                        }
                                    }
                                },
                                onPasswordLogin = { acc, pwd ->
                                    loginIgpByPassword(acc, pwd, account) { updated ->
                                        persist(updated)
                                        states = states.with(
                                            Platform.IGP,
                                            PlatformState(
                                                status = PlatformStatus.VERIFIED,
                                                detail = "登录成功，token 已保存",
                                                displayName = updated.igpAccount.ifBlank { updated.igpPhone },
                                            ),
                                        )
                                    }
                                },
                                onLogin = { acc, pwd ->
                                    // 按平台分发密码登录
                                    when (s.platform) {
                                        Platform.ONELAP -> loginOnelap(acc, pwd, account) { updated ->
                                            persist(updated)
                                            states = states.with(
                                                Platform.ONELAP,
                                                PlatformState(
                                                    status = PlatformStatus.VERIFIED,
                                                    detail = "登录成功",
                                                    displayName = updated.onelapAccount,
                                                ),
                                            )
                                        }
                                        Platform.XINGZHE -> loginXingzhe(acc, pwd, account) { updated ->
                                            persist(updated)
                                            states = states.with(
                                                Platform.XINGZHE,
                                                PlatformState(
                                                    status = PlatformStatus.VERIFIED,
                                                    detail = "登录成功",
                                                    displayName = updated.xingzheUsername
                                                        .ifBlank { updated.xingzheAccount },
                                                    displayMeta = if (updated.xingzheUserId.isNotBlank())
                                                        "ID ${updated.xingzheUserId}" else "",
                                                ),
                                            )
                                        }
                                        Platform.IGP -> loginIgpByPassword(acc, pwd, account) { updated ->
                                            persist(updated)
                                            states = states.with(
                                                Platform.IGP,
                                                PlatformState(
                                                    status = PlatformStatus.VERIFIED,
                                                    detail = "登录成功",
                                                    displayName = updated.igpAccount,
                                                ),
                                            )
                                        }
                                    }
                                },

                                onLoggedOut = { p -> logoutPlatform(p, account) { updated ->
                                    persist(updated)
                                    states = states.with(p, PlatformState())
                                } },
                            )

                            is Screen.Logs -> LogsScreen(
                    events = events.toList(),
                    running = running,
                    onBack = { screen = Screen.Sync },
                    onClear = {
                        events.clear()
                        logStore.clearSync()
                    },
                )

                is Screen.Sync -> SyncScreen(
                                running = running,
                                selected = selected,
                                states = states,
                                date = syncDate,
                                onDateChange = { syncDate = it },
                                onToggle = { d ->
                                    selected = if (d in selected) selected - d else selected + d
                                    lifecycleScope.launch {
                                        uiPrefs.saveDirections(selected.map { it.name }.toSet())
                                    }
                                },
                                onSync = {
                                    // 新一轮同步：清空内存 + 磁盘上的旧日志。
                                    // 必须同步清（clearSync）—— 异步 clear() 可能
                                    // 在同步刚写入日志之后才执行，把新日志误删。
                                    events.clear()
                                    logStore.clearSync()
                                    running = true
                                    val target = syncDate
                                    job = lifecycleScope.launch {
                                        // 收集本次同步的事件 —— 结束后一次性落盘。
                                        // （每条事件都 append 一次：每次开 IO 协程
                                        //   + 全量重写文件，O(n²)，界面会被卡死。）
                                        val batch = mutableListOf<SyncEvent>()
                                        try {
                                            SyncEngine(account, this@MainActivity)
                                                .sync(selected.toList(), target)
                                                .collect { ev ->
                                                    events.add(ev)
                                                    batch += ev
                                                }
                                        } finally {
                                            // 中途取消/异常也要把已产生的日志留下来
                                            withContext(Dispatchers.IO) { logStore.appendAll(batch) }
                                            running = false
                                        }
                                    }
                                },
                                onStop = {
                                    job?.cancel()
                                    running = false
                                },
                                onOpenLogs = { screen = Screen.Logs },
                            )
                        }
                    }
                }
            }
        }
        installCrashGuard()
    }

    // ---------------------------------------------------------- 逐平台验证

    private fun verifyPlatform(
        platform: Platform,
        account: PlatformAccount,
        onResult: (PlatformState) -> Unit,
    ) {
        onResult(
            PlatformState(status = PlatformStatus.CONFIGURED, detail = "验证中…", verifying = true),
        )
        lifecycleScope.launch {
            val result = when (platform) {
                Platform.ONELAP -> verifyOnelap(account)
                Platform.IGP -> verifyIgp(account)
                Platform.XINGZHE -> verifyXingzhe(account)
            }
            onResult(result)
        }
    }

    private suspend fun verifyOnelap(account: PlatformAccount): PlatformState {
        if (!account.onelapConfigured) {
            return PlatformState(
                status = PlatformStatus.FAILED,
                detail = "请先填写顽鹿账号和密码",
            )
        }
        val client = OneLapClient()
        val login = client.login(account.onelapAccount, account.onelapPassword)
        if (login.isFailure) {
            return PlatformState(
                status = PlatformStatus.FAILED,
                detail = "登录失败：${login.exceptionOrNull()?.message}",
            )
        }
        val list = client.listRides(page = 1, limit = 1)
        if (list.isFailure) {
            return PlatformState(
                status = PlatformStatus.FAILED,
                detail = "登录成功，但拉取记录失败：${list.exceptionOrNull()?.message}",
            )
        }
        val n = list.getOrDefault(emptyList()).size
        return PlatformState(
            status = PlatformStatus.VERIFIED,
            detail = "验证通过，可正常读取骑行记录（本页 $n 条）",
        )
    }

    private suspend fun verifyIgp(account: PlatformAccount): PlatformState {
        if (!account.igpConfigured) {
            return PlatformState(
                status = PlatformStatus.FAILED,
                detail = "请先获取短信验证码并完成登录",
            )
        }
        val client = IgpClient()
        client.token = account.igpToken
        client.refreshToken = account.igpRefreshToken
        client.expiresAt = account.igpExpiresAt
        val ens = client.ensureToken()
        if (ens.isFailure) {
            return PlatformState(
                status = PlatformStatus.FAILED,
                detail = "token 无效：${ens.exceptionOrNull()?.message}",
            )
        }
        val list = client.listActivities(pageSize = 1)
        if (list.isFailure) {
            return PlatformState(
                status = PlatformStatus.FAILED,
                detail = "token 有效，但拉取活动失败：${list.exceptionOrNull()?.message}",
            )
        }
        return PlatformState(
            status = PlatformStatus.VERIFIED,
            detail = "验证通过，可正常读取活动记录",
        )
    }

    /**
     * 从已保存的凭据恢复各平台状态。
     *
     * 凭据存在 ≠ 一定有效 —— 这里只恢复「已配置」的展示态，
     * 真正有效性在同步时由 SyncEngine 登录校验。
     */
    private fun restoreStates(a: PlatformAccount): PlatformStates {
        var s = PlatformStates()
        if (a.onelapConfigured) {
            s = s.with(
                Platform.ONELAP,
                PlatformState(
                    status = PlatformStatus.CONFIGURED,
                    detail = "已保存登录信息",
                    displayName = a.onelapAccount,
                ),
            )
        }
        if (a.igpConfigured) {
            s = s.with(
                Platform.IGP,
                PlatformState(
                    status = PlatformStatus.CONFIGURED,
                    detail = "已保存登录信息",
                    displayName = a.igpPhone.ifBlank { a.igpAccount },
                ),
            )
        }
        if (a.xingzheConfigured) {
            s = s.with(
                Platform.XINGZHE,
                PlatformState(
                    status = PlatformStatus.CONFIGURED,
                    detail = "已保存登录信息",
                    displayName = a.xingzheUsername.ifBlank { a.xingzheAccount },
                    displayMeta = if (a.xingzheUserId.isNotBlank()) "ID ${a.xingzheUserId}" else "",
                ),
            )
        }
        return s
    }

    /** 顽鹿账号密码登录 */
    private fun loginOnelap(
        account: String,
        password: String,
        current: PlatformAccount,
        onDone: (PlatformAccount) -> Unit,
    ) {
        if (account.isBlank() || password.isBlank()) {
            Toast.makeText(this, "账号和密码都要填", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val client = OneLapClient()
            client.login(account, password)
                .onSuccess {
                    onDone(current.copy(onelapAccount = account, onelapPassword = password))
                    Toast.makeText(this@MainActivity, "顽鹿登录成功", Toast.LENGTH_SHORT).show()
                }
                .onFailure {
                    Toast.makeText(this@MainActivity, "登录失败: ${it.message}", Toast.LENGTH_LONG).show()
                    states = states.with(
                        Platform.ONELAP,
                        PlatformState(status = PlatformStatus.FAILED, detail = it.message ?: "登录失败"),
                    )
                }
        }
    }

    /** IGPS 账号密码登录 */
    private fun loginIgpByPassword(
        account: String,
        password: String,
        current: PlatformAccount,
        onDone: (PlatformAccount) -> Unit,
    ) {
        if (account.isBlank() || password.isBlank()) {
            Toast.makeText(this, "账号和密码都要填", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val client = IgpClient()
            client.loginByPassword(account, password)
                .onSuccess {
                    onDone(
                        current.copy(
                            igpAccount = account,
                            igpPassword = password,
                            igpToken = client.token,
                            igpRefreshToken = client.refreshToken,
                            igpExpiresAt = client.expiresAt,
                        ),
                    )
                    Toast.makeText(this@MainActivity, "IGPSPORT 登录成功", Toast.LENGTH_SHORT).show()
                }
                .onFailure {
                    Toast.makeText(this@MainActivity, "登录失败: ${it.message}", Toast.LENGTH_LONG).show()
                }
        }
    }

    private suspend fun verifyXingzhe(account: PlatformAccount): PlatformState {
        if (!account.xingzheConfigured) {
            return PlatformState(
                status = PlatformStatus.FAILED,
                detail = "请先登录行者账号",
            )
        }
        val client = XingzheClient()
        client.sessionId = account.xingzheSessionId
        client.username = account.xingzheUsername
        client.userId = account.xingzheUserId
        client.enuid = account.xingzheEnuid
        val list = client.listPrivateWorkouts(offset = 0, limit = 1)
        if (list.isFailure) {
            return PlatformState(
                status = PlatformStatus.FAILED,
                detail = list.exceptionOrNull()?.message ?: "验证失败",
            )
        }
        return PlatformState(
            status = PlatformStatus.VERIFIED,
            detail = "验证通过，可正常读取活动记录",
            displayName = account.displayName(Platform.XINGZHE),
            displayMeta = if (account.xingzheUserId.isNotBlank())
                "ID ${account.xingzheUserId}" else "",
        )
    }

    /** 行者密码登录 */
    private fun loginXingzhe(
        account: String,
        password: String,
        current: PlatformAccount,
        onDone: (PlatformAccount) -> Unit,
    ) {
        if (account.isBlank() || password.isBlank()) {
            Toast.makeText(this, "账号和密码都要填", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val client = XingzheClient()
            client.login(account, password)
                .onSuccess {
                    onDone(
                        current.copy(
                            xingzheAccount = account,
                            xingzhePassword = password,
                            xingzheSessionId = client.sessionId,
                            xingzheUsername = client.username,
                            xingzheUserId = client.userId,
                            xingzheEnuid = client.enuid,
                        ),
                    )
                    Toast.makeText(this@MainActivity, "行者登录成功", Toast.LENGTH_SHORT).show()
                }
                .onFailure {
                    Toast.makeText(this@MainActivity, "登录失败: ${it.message}", Toast.LENGTH_LONG).show()
                    states = states.with(
                        Platform.XINGZHE,
                        PlatformState(status = PlatformStatus.FAILED, detail = it.message ?: "登录失败"),
                    )
                }
        }
    }

    /** 行者短信登录 */
    private fun requestXingzheSms(mobile: String) {
        if (mobile.isBlank()) {
            Toast.makeText(this, "先填手机号", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            XingzheClient().sendSmsCode(mobile)
                .onSuccess {
                    Toast.makeText(this@MainActivity, "验证码已发送", Toast.LENGTH_SHORT).show()
                }
                .onFailure {
                    Toast.makeText(this@MainActivity, "发送失败: ${it.message}", Toast.LENGTH_LONG).show()
                }
        }
    }

    private fun verifyXingzheCode(mobile: String, code: String, account: PlatformAccount, onDone: (PlatformAccount) -> Unit) {
        if (mobile.isBlank() || code.isBlank()) {
            Toast.makeText(this, "手机号和验证码都要填", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val client = XingzheClient()
            client.loginBySms(mobile, code)
                .onSuccess {
                    onDone(
                        account.copy(
                            xingzheMobile = mobile,
                            xingzheSessionId = client.sessionId,
                            xingzheUsername = client.username,
                            xingzheUserId = client.userId,
                            xingzheEnuid = client.enuid,
                        ),
                    )
                    Toast.makeText(this@MainActivity, "行者登录成功", Toast.LENGTH_SHORT).show()
                }
                .onFailure {
                    Toast.makeText(this@MainActivity, "登录失败: ${it.message}", Toast.LENGTH_LONG).show()
                    states = states.with(
                        Platform.IGP,
                        PlatformState(status = PlatformStatus.FAILED, detail = it.message ?: "登录失败"),
                    )
                }
        }
    }

    /** 退出登录 —— 清除凭据 */
    private fun logoutPlatform(
        platform: Platform,
        account: PlatformAccount,
        onDone: (PlatformAccount) -> Unit,
    ) {
        val cleared = when (platform) {
            Platform.ONELAP -> account.copy(
                onelapAccount = "",
                onelapPassword = "",
            )
            Platform.IGP -> account.copy(
                igpToken = "",
                igpRefreshToken = "",
                igpExpiresAt = 0L,
            )
            Platform.XINGZHE -> account.copy(
                xingzheAccount = "",
                xingzhePassword = "",
                xingzheSessionId = "",
                xingzheUsername = "",
                xingzheUserId = "",
            )
        }
        onDone(cleared)
        Toast.makeText(this, "已退出 ${platform.title}", Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------- IGPSPORT 短信

    private fun requestIgpSms(phone: String) {
        if (phone.isBlank()) {
            Toast.makeText(this, "先填手机号", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            IgpClient().sendSmsCode(phone)
                .onSuccess {
                    Toast.makeText(this@MainActivity, "验证码已发送", Toast.LENGTH_SHORT).show()
                }
                .onFailure {
                    Toast.makeText(this@MainActivity, "发送失败: ${it.message}", Toast.LENGTH_LONG).show()
                }
        }
    }

    private fun verifyIgpCode(phone: String, code: String, account: PlatformAccount, onDone: (PlatformAccount) -> Unit) {
        if (phone.isBlank() || code.isBlank()) {
            Toast.makeText(this, "手机号和验证码都要填", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val client = IgpClient()
            client.loginBySms(phone, code)
                .onSuccess {
                    onDone(
                        account.copy(
                            igpPhone = phone,
                            igpToken = client.token,
                            igpRefreshToken = client.refreshToken,
                            igpExpiresAt = client.expiresAt,
                        ),
                    )
                    Toast.makeText(this@MainActivity, "IGPSPORT 登录成功", Toast.LENGTH_SHORT).show()
                }
                .onFailure {
                    Toast.makeText(this@MainActivity, "登录失败: ${it.message}", Toast.LENGTH_LONG).show()
                }
        }
    }
}