package com.pocketforge.ai.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.os.Build
import android.os.PowerManager
import android.widget.Toast
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketforge.ai.agent.AgentEngine
import com.pocketforge.ai.service.KeepAliveService
import com.pocketforge.ai.data.ConfigRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val app = context.applicationContext as com.pocketforge.ai.PocketForgeApp
    val repo = app.configRepo
    val engine = app.engine
    val state by engine.ui.collectAsState()
    val config by repo.config.collectAsState()

    var tab by remember { mutableStateOf(0) }
    var hasStorage by remember { mutableStateOf(engine.hasAllFilesAccess()) }
    var projects by remember { mutableStateOf(engine.projectNames()) }
    var skills by remember { mutableStateOf(engine.skillStatus()) }
    var forced by remember { mutableStateOf(engine.forceInjected()) }
    var locks by remember { mutableStateOf(engine.lockedDocs()) }
    var notice by remember { mutableStateOf("") }
    var updateTag by remember { mutableStateOf<String?>(null) }
    var showSessions by remember { mutableStateOf(false) }
    var sessionQuery by remember { mutableStateOf("") }
    var sessionHits by remember { mutableStateOf(listOf<com.pocketforge.ai.data.SessionHit>()) }
    var renameTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var renameText by remember { mutableStateOf("") }
    var sessionList by remember { mutableStateOf<List<Triple<String, String, Int>>>(emptyList()) }
    var updateDismissed by remember { mutableStateOf(false) }
    val prefs = remember { context.getSharedPreferences("pf-prefs", Context.MODE_PRIVATE) }
    var openTabs by remember { mutableStateOf(readTabs(prefs, engine.currentSessionKey(), engine.currentSessionTitle())) }
    var activeKey by remember { mutableStateOf(engine.currentSessionKey()) }
    var gallery by remember { mutableStateOf<List<com.pocketforge.ai.core.ImageClient.ImageMeta>>(emptyList()) }
    var persona by remember { mutableStateOf(engine.persona()) }
    var personaPacks by remember { mutableStateOf<List<com.pocketforge.ai.agent.AgentEngine.PersonaPack>>(emptyList()) }

    val legacyPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasStorage = engine.hasAllFilesAccess()
        projects = engine.projectNames()
    }

    val docPickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            engine.onIo {
                notice = engine.importDocuments(uris)
            }
        }
    }

    var keepAlive by remember { mutableStateOf(KeepAliveService.isEnabled(context)) }
    val notifyPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        keepAlive = granted
        KeepAliveService.setEnabled(context, granted)
        Toast.makeText(
            context,
            if (granted) "后台常驻已开启" else "没给通知权限，前台服务无法常驻",
            Toast.LENGTH_SHORT
        ).show()
    }

    val skillPickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            engine.onIo { notice = engine.installSkillFiles(uris); skills = engine.skillStatus() }
        }
    }

    LaunchedEffect(Unit) {
        if (KeepAliveService.isEnabled(context)) runCatching { KeepAliveService.start(context) }
        withContext(Dispatchers.IO) {
            runCatching { updateTag = engine.updateAvailable() }
        }
        projects = engine.projectNames()
        skills = engine.skillStatus()
        forced = engine.forceInjected()
        locks = engine.lockedDocs()
        gallery = engine.gallery()
        persona = engine.persona()
        if (openTabs.isEmpty()) openTabs = readTabs(prefs, engine.currentSessionKey(), engine.currentSessionTitle())
        while (true) {
            hasStorage = engine.hasAllFilesAccess()
            delay(1200)
        }
    }

    LaunchedEffect(activeKey) { persona = engine.persona() }

    val hasKey = config.apiKey.isNotBlank()

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Header(
            projectName = state.projectName,
            busy = state.busy,
            hasKey = hasKey,
            updateTag = updateTag,
            onOpenSettings = { tab = 3 },
            onUpdateClick = { tab = 3 },
        )

        if (updateTag != null && !updateDismissed) {
            AlertDialog(
                onDismissRequest = { updateDismissed = true },
                title = { Text("发现新版本 " + updateTag) },
                text = {
                    Text("当前 ${engine.currentVersion()}，可升级到 ${updateTag}。\n\n更新包会先校验签名，不符会拒绝安装，不会装进别的应用。")
                },
                confirmButton = {
                    TextButton(onClick = { updateDismissed = true; tab = 3 }) { Text("去更新") }
                },
                dismissButton = {
                    TextButton(onClick = { updateDismissed = true }) { Text("稍后") }
                },
            )
        }

        // 关键：内容区必须用 weight，否则 fillMaxSize 会吃掉底部导航的高度
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (showSessions) {
            AlertDialog(
                onDismissRequest = { showSessions = false },
                title = { Text("对话页", style = MaterialTheme.typography.titleMedium) },
                text = {
                    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = sessionQuery,
                                onValueChange = { sessionQuery = it },
                                label = { Text("搜索所有对话") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = {
                                sessionHits = if (sessionQuery.isBlank()) emptyList() else engine.searchChats(sessionQuery)
                            }) { Text("搜索") }
                        }
                        if (sessionHits.isNotEmpty()) {
                            Text("命中 ${sessionHits.size} 条", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            sessionHits.forEach { hit ->
                                Column(
                                    Modifier.fillMaxWidth()
                                        .clickable {
                                            engine.switchSession(hit.key)
                                            activeKey = hit.key
                                            openTabs = pushTab(openTabs, hit.key, hit.title)
                                            writeTabs(prefs, openTabs)
                                            showSessions = false
                                        }
                                        .padding(vertical = 6.dp),
                                ) {
                                    Text(hit.title, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                    Text(hit.snippet, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                            }
                            Text("──── 对话列表 ────", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp))
                        }
                        sessionList.forEach { (key, title, count) ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(
                                    Modifier.weight(1f).clickable {
                                        engine.switchSession(key)
                                        activeKey = key
                                        openTabs = pushTab(openTabs, key, title)
                                        writeTabs(prefs, openTabs)
                                        showSessions = false
                                    },
                                ) {
                                    Text(title, fontSize = 14.sp, fontWeight = if (key == engine.currentSessionKey()) FontWeight.Bold else FontWeight.Normal)
                                    Text("$count 条 · $key", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TextButton(
                                    onClick = { renameTarget = key to title; renameText = title },
                                    contentPadding = PaddingValues(horizontal = 4.dp),
                                ) { Text("改名", fontSize = 10.sp) }
                                TextButton(
                                    onClick = {
                                        engine.deleteSession(key)
                                        sessionList = engine.sessions()
                                    },
                                    contentPadding = PaddingValues(horizontal = 4.dp),
                                ) { Text("删除", fontSize = 10.sp, color = MaterialTheme.colorScheme.error) }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val key = engine.newSession("新对话 " + java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date()))
                        openTabs = pushTab(openTabs, key, "新对话")
                        activeKey = key
                        writeTabs(prefs, openTabs)
                        sessionList = engine.sessions()
                        showSessions = false
                        tab = 0
                    }) { Text("新建对话") }
                },
                dismissButton = { TextButton(onClick = { showSessions = false }) { Text("关闭") } },
            )
        }

        renameTarget?.let { (key, oldTitle) ->
            AlertDialog(
                onDismissRequest = { renameTarget = null },
                title = { Text("重命名对话页") },
                text = {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        label = { Text("新名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        engine.renameSession(key, renameText)
                        sessionList = engine.sessions()
                        renameTarget = null
                    }) { Text("保存") }
                },
                dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("取消") } },
            )
        }

        when (tab) {
                0 -> ChatScreen(
                    state = state,
                    hasStorage = hasStorage,
                    hasApiKey = hasKey,
                    onRequestStorage = {
                        requestAllFilesAccess(context) { legacyPermissionLauncher.launch(it) }
                    },
                    onSend = { engine.send(it) },
                    onStop = { engine.stop() },
                    onClear = { engine.clearSession() },
                    onSwitchSession = {
                        sessionList = engine.sessions()
                        showSessions = true
                    },
                    onRewind = { engine.rewindLastTurn() },
                    onEditMessage = { id, text -> engine.editMessage(id, text) },
                    onToggleNoRecall = {
                        val msg = engine.setNoRecall(!engine.isNoRecall())
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        msg
                    },
                    onCopyTranscript = { engine.copyTranscript() },
                    onGenerateImage = { prompt, size -> withContext(Dispatchers.IO) { engine.generateImage(prompt, size) } },
                    persona = persona,
                    onCreatePersona = { n, r, st, q, t, raw ->
                        val msg = engine.createPersona(n, r, st, q, t, raw)
                        persona = engine.persona()
                        msg
                    },
                    packs = personaPacks,
                    onLoadPacks = {
                        val list = withContext(Dispatchers.IO) { engine.personaPacks(true) }
                        personaPacks = list
                        list
                    },
                    onFetchPack = { id -> withContext(Dispatchers.IO) { engine.fetchPersonaPack(id) } },
                    tabs = openTabs,
                    activeKey = activeKey,
                    onSelectTab = { key ->
                        if (engine.switchSession(key)) {
                            activeKey = key
                            sessionList = engine.sessions()
                        }
                    },
                    onNewTab = {
                        val key = engine.newSession("新对话 " + java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date()))
                        openTabs = pushTab(openTabs, key, "新对话")
                        activeKey = key
                        writeTabs(prefs, openTabs)
                        sessionList = engine.sessions()
                    },
                    pendingTask = engine.pendingTask(),
                    onResumePending = {
                        val goal = engine.pendingTask()
                        if (goal != null) {
                            engine.discardPendingTask()
                            engine.send("继续未完成的任务：" + goal)
                        }
                    },
                    onDiscardPending = { engine.discardPendingTask() },
                    onExport = {
                        engine.onIo {
                            val msg = engine.exportSession()
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    onOpenSettings = { tab = 3 },
                )

                1 -> ProjectScreen(
                    workspacePath = engine.workspacePath(),
                    projects = projects,
                    skills = skills,
                    forced = forced,
                    repoUrl = config.skillRepoUrl,
                    locks = locks,
                    onRefresh = {
                        projects = engine.projectNames()
                        skills = engine.skillStatus()
                        locks = engine.lockedDocs()
                    },
                    onCreate = { name, type, engineName, scale ->
                        val r = engine.createProject(name, type, engineName, scale)
                        val session = engine.ensureProjectSession(name)
                        r.summary + "\n" + session
                    },
                    onReadFile = { path -> engine.readFileText(path) },
                    onSaveFile = { path, text -> engine.saveFileText(path, text) },
                    onExportZip = { name -> engine.exportProjectZip(name) },
                    onImportDocs = { docPickLauncher.launch(arrayOf("text/*", "application/markdown", "application/octet-stream")) },
                    onOpenChat = { name ->
                        tab = 0
                        engine.enterProjectSessionByName(name)
                    },
                    onSkillClick = { engine.skillText(it) },
                    onSyncBundled = {
                        val result = engine.installSkills()
                        skills = engine.skillStatus()
                        result
                    },
                    onRemoteInstall = { url, force ->
                        val target = url.ifBlank { config.skillRepoUrl }
                        val result = engine.installRemoteSkills(target, config.skillRepoToken, force)
                        skills = engine.skillStatus()
                        forced = engine.forceInjected()
                        result
                    },
                    onForceInject = {
                        val result = engine.forceInjectFromBundle()
                        skills = engine.skillStatus()
                        forced = engine.forceInjected()
                        result
                    },
                    onDisableForce = {
                        val result = engine.disableForceInject()
                        forced = engine.forceInjected()
                        result
                    },
                    onUnlock = { path ->
                        val result = engine.unlockDoc(path)
                        locks = engine.lockedDocs()
                        result
                    },
                    onPickFiles = {
                        skillPickLauncher.launch(
                            arrayOf("text/markdown", "text/plain", "application/octet-stream")
                        )
                    },
                    onProjectClick = { name -> engine.projectFiles(name) },
                    onEnterProjectChat = { _, name ->
                        val msg = engine.enterProjectSessionByName(name)
                        tab = 0
                        msg
                    },
                    notice = notice,
                )

                2 -> ImageScreen(
                    busy = state.busy,
                    provider = config.imageProvider,
                    defaultModel = config.imageModel,
                    gallery = gallery,
                    onGenerate = { prompt, size, model ->
                        val msg = withContext(Dispatchers.IO) { engine.generateImage(prompt, size, model) }
                        gallery = engine.gallery()
                        msg
                    },
                    onRefreshGallery = { gallery = engine.gallery() },
                    onDelete = { path -> engine.deleteImage(path) },
                )

                else -> SettingsScreen(
                    config = config,
                    workspacePath = engine.workspacePath(),
                    fingerprint = engine.keyFingerprint(),
                    encryptedAtRest = engine.encryptedAtRest(),
                    hasStorage = hasStorage,
                    onSave = { engine.saveConfig(it) },
                    onTest = { withContext(Dispatchers.IO) { engine.testConnection() } },
                    onInstallSkills = { engine.installSkills() },
                    onClear = { engine.clearSession() },
                    onRequestStorage = {
                        requestAllFilesAccess(context) { legacyPermissionLauncher.launch(it) }
                    },
                    permissionReport = { engine.permissionReport() },
                    selfCheck = { engine.storageSelfCheck() },
                    onTermuxTest = { withContext(Dispatchers.IO) { engine.termuxTest() } },
                    onTrustHostKey = { engine.trustNewHostKey() },
                    keepAlive = keepAlive,
                    onToggleKeepAlive = { on ->
                        if (on) {
                            if (Build.VERSION.SDK_INT >= 33 &&
                                ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                            ) {
                                notifyPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                keepAlive = true
                                KeepAliveService.setEnabled(context, true)
                                Toast.makeText(context, "后台常驻已开启", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            keepAlive = false
                            KeepAliveService.setEnabled(context, false)
                            Toast.makeText(context, "已关闭后台常驻", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onBatteryWhitelist = {
                        runCatching {
                            val pm = context.getSystemService(PowerManager::class.java)
                            if (pm != null && !pm.isIgnoringBatteryOptimizations(context.packageName)) {
                                context.startActivity(
                                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + context.packageName))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            } else {
                                Toast.makeText(context, "已在电池优化白名单里", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onAppSettings = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    },
                    logSummary = { engine.logSummary() },
                    latestLog = { engine.latestLog() },
                    onClearLogs = { engine.clearLogs() },
                    downloaderPkg = engine.downloaderPackage(),
                    useDownloader = engine.useDownloader(),
                    onToggleUseDownloader = { engine.setUseDownloader(it) },
                    onHandoffDownloader = { engine.handoffUpdate() },
                    onUsage = { engine.usageSummary() },
                    onResetUsage = { engine.resetUsage() },
                    onStorageUsage = { engine.storageUsage() },
                    onClearCache = { engine.clearCache() },
                    currentVersion = engine.currentVersion(),
                    onCheckUpdate = { withContext(Dispatchers.IO) { engine.checkUpdate() } },
                    onDownloadInstall = { onProgress ->
                        withContext(Dispatchers.IO) { engine.downloadAndInstallUpdate(onProgress) }
                    },
                    onOpenUrl = { url ->
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    },
                )
            }
        }

        BottomTabs(current = tab, onSelect = { tab = it })
    }
}

private fun requestAllFilesAccess(context: Context, legacy: (Array<String>) -> Unit) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val intent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
        if (runCatching { context.startActivity(intent) }.isSuccess) return
        if (runCatching {
                context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }.isSuccess
        ) return
    }
    legacy(
        arrayOf(
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
        )
    )
}

@Composable
private fun Header(
    projectName: String,
    busy: Boolean,
    hasKey: Boolean,
    updateTag: String?,
    onOpenSettings: () -> Unit,
    onUpdateClick: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    modifier = Modifier.size(34.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primary,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("PF", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "PocketForge",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "$projectName" + if (busy) " · 生成中" else " · 待命",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (updateTag != null) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .clickable(onClick = onUpdateClick),
                    ) {
                        Text(
                            "新版本 $updateTag",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                        )
                    }
                }
                Surface(
                    shape = CircleShape,
                    color = if (hasKey) MaterialTheme.colorScheme.surfaceVariant
                    else MaterialTheme.colorScheme.primaryContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.clickable(onClick = onOpenSettings),
                ) {
                    Row(
                        Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(6.dp)
                                .background(
                                    if (hasKey) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error,
                                    CircleShape,
                                )
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (hasKey) "已加密" else "未配置密钥",
                            fontSize = 11.sp,
                            color = if (hasKey) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun BottomTabs(current: Int, onSelect: (Int) -> Unit) {
    val items = listOf(
        Triple("对话", Icons.Default.MailOutline, 0),
        Triple("项目", Icons.Default.List, 1),
        Triple("画图", Icons.Default.AddCircle, 2),
        Triple("设置", Icons.Default.Settings, 3),
    )
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { (label, icon: ImageVector, index) ->
                val active = current == index
                Column(
                    modifier = Modifier
                        .clickable { onSelect(index) }
                        .padding(horizontal = 18.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        icon,
                        contentDescription = label,
                        tint = if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(19.dp),
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        label,
                        fontSize = 12.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        color = if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ---------------- 多页对话：最近打开的页面本地持久化 ----------------

private fun readTabs(prefs: android.content.SharedPreferences, active: String, activeTitle: String): List<Pair<String, String>> {
    val raw = prefs.getString("chat_tabs", "").orEmpty()
    val list = mutableListOf<Pair<String, String>>()
    if (raw.isNotBlank()) {
        runCatching {
            val arr = org.json.JSONArray(raw)
            for (i in 0 until arr.length()) {
                val row = arr.optJSONArray(i) ?: continue
                val key = row.optString(0)
                val title = row.optString(1)
                if (key.isNotBlank()) list.add(key to title.ifBlank { key })
            }
        }
    }
    if (list.isEmpty()) list.add(active to activeTitle)
    return pushTab(list, active, activeTitle)
}

private fun pushTab(list: List<Pair<String, String>>, key: String, title: String): List<Pair<String, String>> {
    val next = list.filterNot { it.first == key }.toMutableList()
    next.add(0, key to title)
    return next.take(6)
}

private fun writeTabs(prefs: android.content.SharedPreferences, list: List<Pair<String, String>>) {
    runCatching {
        val arr = org.json.JSONArray()
        list.forEach { (k, t) -> arr.put(org.json.JSONArray().put(k).put(t)) }
        prefs.edit().putString("chat_tabs", arr.toString()).apply()
    }
}
