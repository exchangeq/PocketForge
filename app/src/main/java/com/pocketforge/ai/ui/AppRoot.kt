package com.pocketforge.ai.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketforge.ai.agent.AgentEngine
import com.pocketforge.ai.data.ConfigRepository
import kotlinx.coroutines.delay

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

    val legacyPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasStorage = engine.hasAllFilesAccess()
        projects = engine.projectNames()
    }

    LaunchedEffect(Unit) {
        projects = engine.projectNames()
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
                    skills = engine.skillEntries(),
                    onRefresh = { projects = engine.projectNames() },
                    onCreate = { name, type, engineName ->
                        engine.createProject(name, type, engineName).summary
                    },
                    onSkillClick = { engine.skillText(it) },
                )
                else -> SettingsScreen(
                    config = config,
                    workspacePath = engine.workspacePath(),
                    fingerprint = engine.keyFingerprint(),
                    encryptedAtRest = engine.encryptedAtRest(),
                    hasStorage = hasStorage,
                    onSave = { engine.saveConfig(it) },
                    onTest = { engine.testConnection() },
                    onInstallSkills = { engine.installSkills() },
                    onClear = { engine.clearSession() },
                    onRequestStorage = {
                        requestAllFilesAccess(context) { legacyPermissionLauncher.launch(it) }
                    },
                    permissionReport = { engine.permissionReport() },
                    selfCheck = { engine.storageSelfCheck() },
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
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("PocketForge", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "项目：$projectName" + if (busy) " · 运行中" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (hasKey) MaterialTheme.colorScheme.surfaceVariant
                    else MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.clickable(onClick = onOpenSettings),
                ) {
                    Text(
                        if (hasKey) "密钥已加密 · 设置 ›" else "未配置密钥 · 去填 ›",
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        color = if (hasKey) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun BottomTabs(current: Int, onSelect: (Int) -> Unit) {
    val labels = listOf("对话", "项目", "设置")
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .height(52.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            labels.forEachIndexed { index, label ->
                Column(
                    modifier = Modifier
                        .clickable { onSelect(index) }
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        label,
                        fontSize = 13.sp,
                        fontWeight = if (current == index) FontWeight.Bold else FontWeight.Normal,
                        color = if (current == index) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(2.dp))
                    Surface(
                        modifier = Modifier
                            .width(18.dp)
                            .height(2.dp),
                        color = if (current == index) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surface,
                    ) {}
                }
            }
        }
    }
}
