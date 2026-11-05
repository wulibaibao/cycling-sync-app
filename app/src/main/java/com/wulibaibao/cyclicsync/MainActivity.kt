package com.wulibaibao.cyclicsync

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import com.wulibaibao.cyclicsync.data.UnifiedActivity
import com.wulibaibao.cyclicsync.data.ActivitySyncStatus
import com.wulibaibao.cyclicsync.data.TargetSyncState
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
import com.wulibaibao.cyclicsync.ui.SyncResult
import com.wulibaibao.cyclicsync.ui.SyncScreen
import com.wulibaibao.cyclicsync.ui.directionEnabled
import com.wulibaibao.cyclicsync.ui.ActivityLedgerScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 页面路由 */
private sealed interface Screen {
    data object Platforms : Screen
    data class Config(val platform: Platform) : Screen
    data object Sync : Screen
    data object ClassicSync : Screen
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
                android.util.Log.e("CyclingSync", "uncaught", throwable)
                val f = java.io.File(getExternalFilesDir(null) ?: filesDir, "crash.txt")
                f.writeText(throwable.stackTraceToString())
            }
            runCatching {
                // ⚠️ 必须带上具体类名 —— 只显示 `NoClassDefFoundError` 等于没说，
                // 用户截图发过来也没法定位。`NoClassDefFoundError` 的 message
                // 里就是缺失类的全限定名。
                val detail = when (throwable) {
                    is NoClassDefFoundError -> throwable.message?.substringAfterLast(";")
                        ?.trim()?.takeIf { it.isNotEmpty() } ?: ""
                    else -> ""
                }
                val text = if (detail.isNotEmpty()) {
                    "出错了：${throwable.javaClass.simpleName}\n$detail\n日志已写入 crash.txt"
                } else {
                    "出错了：${throwable.javaClass.simpleName}\n${throwable.message?.take(120) ?: ""}\n日志已写入 crash.txt"
                }
                Toast.makeText(this, text, Toast.LENGTH_LONG).show()
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashGuard()
        store = CredentialStore(this)
        logStore = LogStore(this)
        uiPrefs = UiPrefs(this)

        try {
            setContent {
            CyclingSyncTheme {
                var screen by remember { mutableStateOf<Screen>(Screen.Sync) }
                // ⚠️ 凭据读取可能触发 Keystore 操作，**不能**在组合期同步执行 ——
                // 抛异常会直接杀掉进程（用户看到的是 App 被关掉）。
                var account by remember { mutableStateOf(PlatformAccount()) }
                var credsLoaded by remember { mutableStateOf(false) }
                var prefsLoaded by remember { mutableStateOf(false) }
                var running by remember { mutableStateOf(false) }
                var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
                val events = remember { mutableStateListOf<SyncEvent>() }
                var syncResult by remember { mutableStateOf<SyncResult?>(null) }
                var selected by remember { mutableStateOf(emptySet<SyncDirection>()) }
                var syncDate by remember { mutableStateOf(java.time.LocalDate.now()) }

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

                    val loaded = withContext(Dispatchers.IO) { store.load() }
                    val savedDirs = withContext(Dispatchers.IO) { uiPrefs.loadDirections() }
                        .mapNotNull { n -> runCatching { SyncDirection.valueOf(n) }.getOrNull() }
                        .toSet()
                    val savedDateEpoch = withContext(Dispatchers.IO) { uiPrefs.loadLastDateEpochDay() }
                    if (savedDateEpoch != null) {
                        syncDate = java.time.LocalDate.ofEpochDay(savedDateEpoch)
                    }

                    account = loaded
                    val initStates = restoreStates(loaded)
                    states = initStates
                    val valid = SyncDirection.entries.filter { directionEnabled(it, initStates) }.toSet()
                    selected = if (savedDirs.isNotEmpty()) {
                        val pruned = savedDirs.filter { it in valid }.toSet()
                        if (pruned.isNotEmpty()) pruned else valid
                    } else {
                        valid
                    }
                    credsLoaded = true
                    prefsLoaded = true
                }

                // 监听 states 变更：自动剔除未配置的链路，仅当源端和目标端均配置就绪时才允许选中
                LaunchedEffect(states) {
                    if (!prefsLoaded) return@LaunchedEffect
                    val valid = SyncDirection.entries.filter { directionEnabled(it, states) }.toSet()
                    val pruned = selected.filter { it in valid }.toSet()
                    if (pruned != selected) {
                        selected = pruned
                        lifecycleScope.launch {
                            uiPrefs.saveDirections(pruned.map { it.name }.toSet())
                        }
                    }
                }

                // 全局系统返回键劫持：在非主页时一律退回主页（活动看板）
                BackHandler(enabled = screen != Screen.Sync) {
                    screen = when (screen) {
                        is Screen.Config -> Screen.Platforms
                        is Screen.ClassicSync -> Screen.Sync
                        is Screen.Logs -> Screen.Sync
                        is Screen.Platforms -> Screen.Sync
                        Screen.Sync -> Screen.Sync
                    }
                }

                fun persist(a: PlatformAccount) {
                    account = a
                    lifecycleScope.launch {
                        store.save(a)
                        account = withContext(Dispatchers.IO) { store.load() }
                    }
                }

                var activities by remember { mutableStateOf<List<UnifiedActivity>>(emptyList()) }
                var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
                var loadingActivities by remember { mutableStateOf(false) }
                var loadingMore by remember { mutableStateOf(false) }
                var hasMore by remember { mutableStateOf(true) }
                var currentPage by remember { mutableStateOf(1) }
                var batchProgressText by remember { mutableStateOf("") }
                var batchCurrent by remember { mutableStateOf(0) }
                var batchTotal by remember { mutableStateOf(0) }

                fun isActPending(act: UnifiedActivity): Boolean {
                    val applicableTargets = selected
                        .filter { directionEnabled(it, states) && it.source == act.source }
                        .map { it.target }
                        .toSet()
                    return act.isPendingFor(applicableTargets)
                }

                fun refreshActivities(source: Platform? = null) {
                    loadingActivities = true
                    currentPage = 1
                    hasMore = true
                    lifecycleScope.launch {
                        val engine = SyncEngine(account, this@MainActivity)
                        val list = mutableListOf<UnifiedActivity>()
                        val activeSources = selected.filter { directionEnabled(it, states) }.map { it.source }.toSet()
                        val shouldFetchIgp = (source == null || source == Platform.IGP) && (activeSources.isEmpty() || Platform.IGP in activeSources)
                        val shouldFetchOnelap = (source == null || source == Platform.ONELAP) && (activeSources.isEmpty() || Platform.ONELAP in activeSources)

                        var batchFull = false
                        if (shouldFetchIgp && account.igpConfigured) {
                            engine.fetchActivities(Platform.IGP, page = 1, pageSize = 50)
                                .onSuccess {
                                    list += it
                                    if (it.size >= 50) batchFull = true
                                }
                        }
                        if (shouldFetchOnelap && account.onelapConfigured) {
                            engine.fetchActivities(Platform.ONELAP, page = 1, pageSize = 50)
                                .onSuccess {
                                    list += it
                                    if (it.size >= 50) batchFull = true
                                }
                        }
                        list.sortByDescending { it.startTimeMs }
                        activities = list
                        hasMore = batchFull
                        selectedIds = list
                            .filter { isActPending(it) }
                            .map { it.id }
                            .toSet()
                        loadingActivities = false
                    }
                }

                fun loadMoreActivities(source: Platform? = null) {
                    if (loadingActivities || loadingMore || !hasMore) return
                    loadingMore = true
                    lifecycleScope.launch {
                        val engine = SyncEngine(account, this@MainActivity)
                        val activeSources = selected.filter { directionEnabled(it, states) }.map { it.source }.toSet()
                        val shouldFetchIgp = (source == null || source == Platform.IGP) && (activeSources.isEmpty() || Platform.IGP in activeSources)
                        val shouldFetchOnelap = (source == null || source == Platform.ONELAP) && (activeSources.isEmpty() || Platform.ONELAP in activeSources)

                        val nextPage = currentPage + 1
                        val newItems = mutableListOf<UnifiedActivity>()
                        var batchFull = false

                        if (shouldFetchIgp && account.igpConfigured) {
                            engine.fetchActivities(Platform.IGP, page = nextPage, pageSize = 50)
                                .onSuccess {
                                    newItems += it
                                    if (it.size >= 50) batchFull = true
                                }
                        }
                        if (shouldFetchOnelap && account.onelapConfigured) {
                            engine.fetchActivities(Platform.ONELAP, page = nextPage, pageSize = 50)
                                .onSuccess {
                                    newItems += it
                                    if (it.size >= 50) batchFull = true
                                }
                        }

                        if (newItems.isNotEmpty()) {
                            val combined = (activities + newItems).distinctBy { it.id }.sortedByDescending { it.startTimeMs }
                            activities = combined
                            currentPage = nextPage
                            val newPendingIds = newItems.filter { isActPending(it) }.map { it.id }
                            selectedIds = selectedIds + newPendingIds
                        }
                        hasMore = batchFull
                        loadingMore = false
                    }
                }

                fun loadAllHistory(source: Platform? = null) {
                    loadingActivities = true
                    lifecycleScope.launch {
                        val engine = SyncEngine(account, this@MainActivity)
                        val activeSources = selected.filter { directionEnabled(it, states) }.map { it.source }.toSet()
                        val sourcesToStream = when {
                            source != null -> listOf(source)
                            activeSources.isNotEmpty() -> activeSources.toList()
                            account.igpConfigured -> listOf(Platform.IGP)
                            else -> listOf(Platform.ONELAP)
                        }
                        for (s in sourcesToStream) {
                            engine.streamAllActivities(s, maxPages = 100, pageSize = 50, delayPerBatchMs = 100L).collect { batch ->
                                activities = (activities + batch).distinctBy { it.id }.sortedByDescending { it.startTimeMs }
                                selectedIds = activities
                                    .filter { isActPending(it) }
                                    .map { it.id }
                                    .toSet()
                            }
                        }
                        hasMore = false
                        loadingActivities = false
                    }
                }

                fun startBatchSync(targetPlatform: Platform = Platform.XINGZHE) {
                    val pending = activities.filter { it.id in selectedIds && it.targetStates[targetPlatform]?.status != ActivitySyncStatus.SYNCED }
                    val toSync = if (pending.isNotEmpty()) pending else activities.filter { it.id in selectedIds }
                    if (toSync.isEmpty()) return
                    events.clear()
                    running = true
                    batchTotal = toSync.size
                    batchCurrent = 0
                    job = lifecycleScope.launch {
                        val engine = SyncEngine(account, this@MainActivity)
                        engine.syncUnifiedBatch(toSync, targetPlatform).collect { ev ->
                            events.add(ev)
                            if (ev is SyncEvent.Step) {
                                batchProgressText = ev.text
                            }
                            if (ev is SyncEvent.Ride) {
                                batchCurrent++
                                val isSuccess = ev.status == "已上传" || ev.status.contains("已存在") || ev.status.contains("已同步")
                                activities = activities.map { act ->
                                    val isMatch = if (ev.activityId.isNotBlank()) {
                                        act.id == ev.activityId
                                    } else {
                                        act.title == ev.name && Math.abs(act.distanceKm - ev.distanceKm) < 0.2
                                    }
                                    if (isMatch) {
                                        act.copy(
                                            targetStates = act.targetStates + (targetPlatform to TargetSyncState(
                                                status = if (isSuccess) ActivitySyncStatus.SYNCED else ActivitySyncStatus.FAILED,
                                                errorDetail = ev.errorDetail,
                                                syncedAtMs = System.currentTimeMillis()
                                            ))
                                        )
                                    } else act
                                }
                            }
                        }
                        running = false
                        selectedIds = activities
                            .filter { isActPending(it) }
                            .map { it.id }
                            .toSet()
                    }
                }

                fun startSingleSync(act: UnifiedActivity, targetPlatform: Platform = Platform.XINGZHE) {
                    events.clear()
                    running = true
                    batchTotal = 1
                    batchCurrent = 0
                    batchProgressText = "正在同步到 ${targetPlatform.title}: ${act.title}..."
                    job = lifecycleScope.launch {
                        val engine = SyncEngine(account, this@MainActivity)
                        engine.syncUnifiedBatch(listOf(act), targetPlatform).collect { ev ->
                            events.add(ev)
                            if (ev is SyncEvent.Step) {
                                batchProgressText = ev.text
                            }
                            if (ev is SyncEvent.Ride) {
                                batchCurrent++
                                val isSuccess = ev.status == "已上传" || ev.status.contains("已存在") || ev.status.contains("已同步")
                                activities = activities.map { a ->
                                    val isMatch = if (ev.activityId.isNotBlank()) a.id == ev.activityId else a.id == act.id
                                    if (isMatch) {
                                        a.copy(
                                            targetStates = a.targetStates + (targetPlatform to TargetSyncState(
                                                status = if (isSuccess) ActivitySyncStatus.SYNCED else ActivitySyncStatus.FAILED,
                                                errorDetail = ev.errorDetail,
                                                syncedAtMs = System.currentTimeMillis()
                                            ))
                                        )
                                    } else a
                                }
                            }
                        }
                        running = false
                        selectedIds = activities
                            .filter { isActPending(it) }
                            .map { it.id }
                            .toSet()
                    }
                }

                LaunchedEffect(credsLoaded) {
                    if (credsLoaded) {
                        refreshActivities()
                    }
                }

                val currentTab = when (screen) {
                    is Screen.Platforms, is Screen.Config -> 1
                    else -> 0
                }

                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = currentTab == 0,
                                onClick = { screen = Screen.Sync },
                                icon = { Icon(Icons.Rounded.Sync, contentDescription = null) },
                                label = { Text("同步") },
                            )
                            NavigationBarItem(
                                selected = currentTab == 1,
                                onClick = { screen = Screen.Platforms },
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
                                onOpen = { p -> screen = Screen.Config(p) },
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

                            is Screen.ClassicSync -> SyncScreen(
                                running = running,
                                selected = selected,
                                states = states,
                                date = syncDate,
                                result = syncResult,
                                onDismissResult = { syncResult = null },
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
                                    syncResult = null   // 清掉上一轮的结果提示
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
                                                    // 捕获最终结果 —— Done 是正常收尾，
                                                    // Fatal 是致命错误，都要在界面上提示
                                                    when (ev) {
                                                        is SyncEvent.Done -> syncResult = SyncResult.Success(
                                                            ev.totalUploaded, ev.totalSkipped, ev.totalFailed,
                                                        )
                                                        is SyncEvent.Fatal -> syncResult = SyncResult.Failure(ev.message)
                                                        else -> Unit
                                                    }
                                                }
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            syncResult = SyncResult.Failure(e.message ?: "同步异常")
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
                                onBack = { screen = Screen.Sync },
                                onOpenConfig = { p -> screen = Screen.Config(p) },
                            )

                            is Screen.Sync -> ActivityLedgerScreen(
                                running = running,
                                selectedDirections = selected.filter { directionEnabled(it, states) }.toSet(),
                                batchProgressText = batchProgressText,
                                batchCurrent = batchCurrent,
                                batchTotal = batchTotal,
                                loading = loadingActivities,
                                loadingMore = loadingMore,
                                hasMore = hasMore,
                                activities = activities,
                                selectedIds = selectedIds,
                                onToggleSelect = { id ->
                                    selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
                                },
                                onSelectAllPending = {
                                    selectedIds = activities
                                        .filter { isActPending(it) }
                                        .map { it.id }
                                        .toSet()
                                },
                                onClearSelect = { selectedIds = emptySet() },
                                onBatchSync = { target -> startBatchSync(target) },
                                onSingleSync = { act, target -> startSingleSync(act, target) },
                                onRefresh = { refreshActivities() },
                                onLoadMore = { loadMoreActivities() },
                                onLoadAllHistory = { loadAllHistory() },
                                onStop = {
                                    job?.cancel()
                                    running = false
                                },
                                onOpenLogs = { screen = Screen.Logs },
                                onSwitchToClassic = { screen = Screen.ClassicSync },
                            )
                        }
                    }
                }
            }
        }
    } catch (t: Throwable) {
            android.util.Log.e("CyclingSync", "Fatal crash in setContent", t)
            runCatching {
                java.io.File(getExternalFilesDir(null) ?: filesDir, "crash.txt").writeText(t.stackTraceToString())
            }
            val tv = android.widget.TextView(this).apply {
                text = "应用启动异常：\n\n${t.stackTraceToString()}"
                setTextIsSelectable(true)
                textSize = 13f
                setTextColor(android.graphics.Color.RED)
                setPadding(32, 48, 32, 48)
            }
            val sv = android.widget.ScrollView(this).apply { addView(tv) }
            setContentView(sv)
        }
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
                        Platform.XINGZHE,
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
                igpPhone = "",
                igpAccount = "",
                igpPassword = "",
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
                xingzheEnuid = "",
                xingzheMobile = "",
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