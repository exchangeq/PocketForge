package com.pocketforge.ai.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.pocketforge.ai.data.ConfigRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val repo = remember { ConfigRepository(context) }
    val engine = remember { AgentEngine(context, repo) }
    val state by engine.ui.collectAsState()
    val config by repo.config.collectAsState()

    var tab by remember { mutableStateOf(0) }
    var hasStorage by remember { mutableStateOf(engine.hasAllFilesAccess()) }
    var projects by remember { mutableStateOf(engine.projectNames()) }
    var skills by remember { mutableStateOf(engine.skillStatus()) }
    var forced by remember { mutableStateOf(engine.forceInjected()) }
    var locks by remember { mutableStateOf(engine.lockedDocs()) }

    val legacyPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasStorage = engine.hasAllFilesAccess()
        projects = engine.projectNames()
    }

    LaunchedEffect(Unit) {
        projects = engine.projectNames()
        skills = engine.skillStatus()
        forced = engine.forceInjected()
        locks = engine.lockedDocs()
        while (true) {
            hasStorage = engine.hasAllFilesAccess()
            delay(1200)
        }
    }

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
            onOpenSettings = { tab = 2 },
        )

        // 关键：内容区必须用 weight，否则 fillMaxSize 会吃掉底部导航的高度
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
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
                    onOpenSettings = { tab = 2 },
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
                    onCreate = { name, type, engineName ->
                        engine.createProject(name, type, engineName).summary
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
    onOpenSettings: () -> Unit,
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
        Triple("设置", Icons.Default.Settings, 2),
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
