package com.pocketforge.ai.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketforge.ai.data.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(
    config: AppConfig,
    workspacePath: String,
    fingerprint: String,
    encryptedAtRest: Boolean,
    hasStorage: Boolean,
    onSave: (AppConfig) -> Boolean,
    onTest: suspend () -> String,
    onInstallSkills: () -> String,
    onClear: () -> Unit,
    onRequestStorage: () -> Unit,
    permissionReport: () -> String,
    selfCheck: () -> String,
    onTermuxTest: suspend () -> String,
    onTrustHostKey: () -> String,
) {
    val scope = rememberCoroutineScope()

    var permReport by remember { mutableStateOf("") }
    var checkResult by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        permReport = withContext(Dispatchers.IO) { permissionReport() }
    }

    var baseUrl by remember(config) { mutableStateOf(config.baseUrl) }
    var apiKey by remember(config) { mutableStateOf(config.apiKey) }
    var model by remember(config) { mutableStateOf(config.model) }
    var temperature by remember(config) { mutableStateOf(config.temperature.toString()) }
    var maxTokens by remember(config) { mutableStateOf(config.maxTokens.toString()) }
    var effort by remember(config) { mutableStateOf(config.reasoningEffort) }
    var workspaceRoot by remember(config) { mutableStateOf(config.workspaceRoot) }
    var cipherKey by remember(config) { mutableStateOf(config.streamCipherKey) }
    var systemExtra by remember(config) { mutableStateOf(config.systemExtra) }
    var stream by remember(config) { mutableStateOf(config.stream) }
    var skillsGate by remember(config) { mutableStateOf(config.skillsGate) }
    var skillRepoUrl by remember(config) { mutableStateOf(config.skillRepoUrl) }
    var skillRepoToken by remember(config) { mutableStateOf(config.skillRepoToken) }

    var tHost by remember(config) { mutableStateOf(config.termuxHost) }
    var tPort by remember(config) { mutableStateOf(config.termuxPort.toString()) }
    var tUser by remember(config) { mutableStateOf(config.termuxUser) }
    var tAuth by remember(config) { mutableStateOf(config.termuxAuth) }
    var tPassword by remember(config) { mutableStateOf(config.termuxPassword) }
    var tKey by remember(config) { mutableStateOf(config.termuxKey) }
    var tPass by remember(config) { mutableStateOf(config.termuxPassphrase) }
    var allowShell by remember(config) { mutableStateOf(config.allowShell) }

    var testResult by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var termuxResult by remember { mutableStateOf("") }
    var termuxTesting by remember { mutableStateOf(false) }
    var savedTip by remember { mutableStateOf("") }
    var hostKey by remember { mutableStateOf(config.termuxHostKey) }

    fun buildConfig(): AppConfig = config.copy(
        baseUrl = baseUrl.trim().ifEmpty { "https://api.openai.com/v1" },
        apiKey = apiKey.trim(),
        model = model.trim().ifEmpty { "gpt-4o-mini" },
        temperature = temperature.trim().toDoubleOrNull() ?: 0.4,
        maxTokens = maxTokens.trim().toIntOrNull() ?: 8192,
        reasoningEffort = effort,
        workspaceRoot = workspaceRoot.trim(),
        streamCipherKey = cipherKey.trim(),
        systemExtra = systemExtra,
        stream = stream,
        skillsGate = skillsGate,
        skillRepoUrl = skillRepoUrl.trim(),
        skillRepoToken = skillRepoToken.trim(),
        termuxHost = tHost.trim().ifEmpty { "127.0.0.1" },
        termuxPort = tPort.trim().toIntOrNull() ?: 8022,
        termuxUser = tUser.trim(),
        termuxAuth = tAuth,
        termuxPassword = tPassword,
        termuxKey = tKey.trim(),
        termuxPassphrase = tPass,
        allowShell = allowShell,
    )

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionTitle("模型接入", "兼容 OpenAI 的 /v1/chat/completions，填到 /v1 即可")
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("Base URL") },
            singleLine = true,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("API Key（加密落盘）") },
            singleLine = true,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("模型") },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1.4f),
            )
            OutlinedTextField(
                value = temperature,
                onValueChange = { temperature = it },
                label = { Text("温度") },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(0.6f),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = maxTokens,
                onValueChange = { maxTokens = it },
                label = { Text("max_tokens") },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = workspaceRoot,
                onValueChange = { workspaceRoot = it },
                label = { Text("工作区（留空=默认）") },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1.6f),
            )
        }
        Column(Modifier.padding(top = 4.dp)) {
            Text(
                "推理强度",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
            Text(
                "高: 先列方案再动手、写完自检两次；关: 直接给结论。透传 reasoning_effort，上游不支持会自动忽略。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 15.sp,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("off" to "关", "low" to "低", "medium" to "中", "high" to "高", "max" to "极高")
                    .forEach { (key, label) -> AuthChip(label, effort == key) { effort = key } }
            }
        }
        SwitchRow("SSE 流式输出", "逐字显示模型回答，长回复体感更好", stream) { stream = it }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        SectionTitle("加密与闸门", "配置整份 AES-256-GCM 加密落盘，密钥在 Android Keystore 内不可导出")
        OutlinedTextField(
            value = cipherKey,
            onValueChange = { cipherKey = it },
            label = { Text("流密钥（留空=上游明文；填了会解 PF1 加密帧）") },
            singleLine = true,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = systemExtra,
            onValueChange = { systemExtra = it },
            label = { Text("附加系统提示（可选）") },
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 4,
        )
        SwitchRow(
            "技能闸门",
            "开启后：没读技能说明就写文件会被工具层直接拒绝",
            skillsGate
        ) { skillsGate = it }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        SectionTitle("技能源", "留空用内置；填目录地址或 skills-index.json 即可远程注入")
        OutlinedTextField(
            value = skillRepoUrl,
            onValueChange = { skillRepoUrl = it },
            label = { Text("技能源（目录 URL 或 .json 清单）") },
            singleLine = true,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = skillRepoToken,
            onValueChange = { skillRepoToken = it },
            label = { Text("访问令牌（私有仓库才需要）") },
            singleLine = true,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        SectionTitle(
            "Termux 桥接",
            "走 SSH-2：curve25519-sha256 换密钥，ed25519 主机指纹，chacha20-poly1305 / aes256-gcm 加密，hmac-sha2-256 校验"
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = tHost,
                onValueChange = { tHost = it },
                label = { Text("主机") },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1.5f),
            )
            OutlinedTextField(
                value = tPort,
                onValueChange = { tPort = it },
                label = { Text("端口") },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(0.7f),
            )
            OutlinedTextField(
                value = tUser,
                onValueChange = { tUser = it },
                label = { Text("用户 whoami") },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1.3f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AuthChip("密码", tAuth == "password") { tAuth = "password" }
            AuthChip("私钥", tAuth == "key") { tAuth = "key" }
        }
        if (tAuth == "password") {
            OutlinedTextField(
                value = tPassword,
                onValueChange = { tPassword = it },
                label = { Text("Termux 密码") },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            OutlinedTextField(
                value = tKey,
                onValueChange = { tKey = it },
                label = { Text("私钥（~/.ssh/id_ed25519 全文，含 BEGIN/END）") },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 6,
            )
            OutlinedTextField(
                value = tPass,
                onValueChange = { tPass = it },
                label = { Text("私钥口令（可留空）") },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        SwitchRow(
            "允许 AI 在 Termux 执行命令",
            "默认关闭。开启后只能跑只读/低风险命令，rm -rf /、mkfs、dd、su 等会被拦截",
            allowShell
        ) { allowShell = it }
        Text(
            "已记录主机指纹：${hostKey.ifBlank { "无（首次连接会学习）" }}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(
                enabled = !termuxTesting,
                contentPadding = PaddingValues(horizontal = 8.dp),
                onClick = {
                    termuxTesting = true
                    termuxResult = ""
                    scope.launch {
                        // 先落盘再测，避免测试用的是旧配置
                        onSave(buildConfig())
                        val result = withContext(Dispatchers.IO) { onTermuxTest() }
                        termuxResult = result
                        hostKey = config.termuxHostKey
                        termuxTesting = false
                    }
                },
            ) { Text(if (termuxTesting) "连接中…" else "测试 Termux") }
            TextButton(
                contentPadding = PaddingValues(horizontal = 8.dp),
                onClick = {
                    termuxResult = onTrustHostKey()
                    hostKey = ""
                },
            ) { Text("信任新指纹") }
        }
        if (termuxResult.isNotEmpty()) {
            Text(
                termuxResult,
                style = MaterialTheme.typography.labelSmall,
                color = if (termuxResult.startsWith("连接成功")) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error,
                lineHeight = 16.sp,
            )
        }
        Text(
            "Termux 侧准备：pkg install openssh → sshd → whoami 取用户名 → 需要免密就 cat ~/.ssh/id_ed25519.pub >> ~/.ssh/authorized_keys",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 16.sp,
        )

        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable {
                    val saved = onSave(buildConfig())
                    savedTip = if (saved) "已加密保存" else "保存失败：设备密钥不可用"
                    hostKey = config.termuxHostKey
                },
            ) {
                Text(
                    "保存配置",
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp),
                )
            }
            TextButton(
                enabled = !testing,
                contentPadding = PaddingValues(horizontal = 6.dp),
                onClick = {
                    testing = true
                    testResult = ""
                    scope.launch {
                        onSave(buildConfig())
                        val result = withContext(Dispatchers.IO) { onTest() }
                        testResult = result
                        testing = false
                    }
                },
            ) { Text(if (testing) "测试中…" else "测试连接") }
        }
        if (savedTip.isNotEmpty()) {
            Text(savedTip, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        if (testResult.isNotEmpty()) {
            Text(
                testResult,
                style = MaterialTheme.typography.labelSmall,
                color = if (testResult.contains("成功")) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error,
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        SectionTitle("权限体检", "逐项核对，缺哪项就补哪项")
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    if (permReport.isBlank()) "检测中…" else permReport,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 17.sp,
                )
                if (checkResult.isNotEmpty()) {
                    Text(
                        checkResult,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(
                contentPadding = PaddingValues(horizontal = 8.dp),
                onClick = {
                    onRequestStorage()
                    scope.launch {
                        delay(900)
                        permReport = withContext(Dispatchers.IO) { permissionReport() }
                    }
                },
            ) { Text(if (hasStorage) "重新检查授权" else "授予所有文件访问") }
            TextButton(
                enabled = !checking,
                contentPadding = PaddingValues(horizontal = 8.dp),
                onClick = {
                    checking = true
                    scope.launch {
                        permReport = withContext(Dispatchers.IO) { permissionReport() }
                        checkResult = withContext(Dispatchers.IO) { selfCheck() }
                        checking = false
                    }
                },
            ) { Text(if (checking) "写入测试中…" else "写入自检") }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        SectionTitle("运行环境")
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(12.dp)) {
                InfoLine("工作区", workspacePath)
                InfoLine("文件访问", if (hasStorage) "已授权" else "仅应用私有目录")
                InfoLine("配置加密", if (encryptedAtRest) "AES-256-GCM（Keystore）" else "尚未写入")
                InfoLine("密钥指纹", fingerprint)
                InfoLine("推理强度", when (effort) { "off" -> "关"; "low" -> "低"; "high" -> "高"; "max" -> "极高"; else -> "中" })
                InfoLine("技能源", skillRepoUrl.ifBlank { "内置" })
                InfoLine("Termux", "${tUser.ifBlank { "未配置" }}@${tHost.ifBlank { "127.0.0.1" }}:${tPort.ifBlank { "8022" }}")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(
                contentPadding = PaddingValues(horizontal = 6.dp),
                onClick = { savedTip = onInstallSkills() },
            ) { Text("同步技能到工作区") }
            TextButton(contentPadding = PaddingValues(horizontal = 6.dp), onClick = onClear) {
                Text("清除加密会话", color = MaterialTheme.colorScheme.error)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SwitchRow(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Switch(checked = checked, onCheckedChange = onChange)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            Text(
                hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 15.sp,
            )
        }
    }
}

@Composable
private fun AuthChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outline),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            label,
            fontSize = 12.sp,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}
