package com.pocketforge.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketforge.ai.data.AppConfig
import kotlinx.coroutines.Dispatchers
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
) {
    val scope = rememberCoroutineScope()

    var baseUrl by remember(config) { mutableStateOf(config.baseUrl) }
    var apiKey by remember(config) { mutableStateOf(config.apiKey) }
    var model by remember(config) { mutableStateOf(config.model) }
    var temperature by remember(config) { mutableStateOf(config.temperature.toString()) }
    var maxTokens by remember(config) { mutableStateOf(config.maxTokens.toString()) }
    var workspaceRoot by remember(config) { mutableStateOf(config.workspaceRoot) }
    var cipherKey by remember(config) { mutableStateOf(config.streamCipherKey) }
    var systemExtra by remember(config) { mutableStateOf(config.systemExtra) }
    var stream by remember(config) { mutableStateOf(config.stream) }
    var skillsGate by remember(config) { mutableStateOf(config.skillsGate) }

    var testResult by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var savedTip by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("模型接入", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "兼容 OpenAI 的 /v1/chat/completions。填到 /v1 为止，程序自动补 /chat/completions。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("Base URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("API Key（加密落盘）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("模型") },
                singleLine = true,
                modifier = Modifier.weight(1.4f),
            )
            OutlinedTextField(
                value = temperature,
                onValueChange = { temperature = it },
                label = { Text("温度") },
                singleLine = true,
                modifier = Modifier.weight(0.6f),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = maxTokens,
                onValueChange = { maxTokens = it },
                label = { Text("max_tokens") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = workspaceRoot,
                onValueChange = { workspaceRoot = it },
                label = { Text("工作区（留空=默认）") },
                singleLine = true,
                modifier = Modifier.weight(1.6f),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = stream, onCheckedChange = { stream = it })
            Spacer(Modifier.width(8.dp))
            Text("SSE 流式输出", style = MaterialTheme.typography.bodySmall)
        }

        HorizontalDivider()
        Text("加密与技能闸门", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = cipherKey,
            onValueChange = { cipherKey = it },
            label = { Text("流密钥（留空=上游为明文，可解密 PF1 加密帧）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = systemExtra,
            onValueChange = { systemExtra = it },
            label = { Text("附加系统提示（可选）") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 4,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = skillsGate, onCheckedChange = { skillsGate = it })
            Spacer(Modifier.width(8.dp))
            Column {
                Text("技能闸门", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                Text(
                    "开启后：未读技能说明就写文件会被直接拒绝",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = {
                val saved = onSave(
                    config.copy(
                        baseUrl = baseUrl.trim().ifEmpty { "https://api.openai.com/v1" },
                        apiKey = apiKey.trim(),
                        model = model.trim().ifEmpty { "gpt-4o-mini" },
                        temperature = temperature.trim().toDoubleOrNull() ?: 0.4,
                        maxTokens = maxTokens.trim().toIntOrNull() ?: 2048,
                        workspaceRoot = workspaceRoot.trim(),
                        streamCipherKey = cipherKey.trim(),
                        systemExtra = systemExtra,
                        stream = stream,
                        skillsGate = skillsGate,
                    )
                )
                savedTip = if (saved) "已加密保存" else "保存失败：设备密钥不可用"
            }) {
                Text("保存配置")
            }
            TextButton(onClick = {
                testing = true
                testResult = ""
                scope.launch {
                    val result = withContext(Dispatchers.IO) { onTest() }
                    testResult = result
                    testing = false
                }
            }, enabled = !testing) {
                Text(if (testing) "测试中…" else "测试连接")
            }
        }
        if (savedTip.isNotEmpty()) {
            Text(savedTip, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        if (testResult.isNotEmpty()) {
            Text(testResult, style = MaterialTheme.typography.labelSmall)
        }

        HorizontalDivider()
        Text("运行环境", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(12.dp)) {
                InfoLine("工作区", workspacePath)
                InfoLine("文件访问", if (hasStorage) "已授权" else "仅应用私有目录")
                InfoLine("配置加密", if (encryptedAtRest) "AES-256-GCM（Keystore）" else "尚未写入")
                InfoLine("密钥指纹", fingerprint)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = {
                val path = onInstallSkills()
                savedTip = "技能已同步：$path"
            }) {
                Text("同步技能到工作区")
            }
            TextButton(onClick = onClear) {
                Text("清除加密会话", color = MaterialTheme.colorScheme.error)
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}
