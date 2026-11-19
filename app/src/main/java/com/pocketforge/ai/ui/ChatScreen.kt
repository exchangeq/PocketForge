package com.pocketforge.ai.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketforge.ai.data.MsgUi
import com.pocketforge.ai.data.StepStatus
import com.pocketforge.ai.data.StepUi
import com.pocketforge.ai.data.UiState

private val QUICK_PROMPTS = listOf(
    "做个像素风横版小游戏，放到 projects 里",
    "开一本 30 章的科幻长篇，先出大纲和世界观",
    "看看我手机根目录里都有什么",
)

@Composable
fun ChatScreen(
    state: UiState,
    hasStorage: Boolean,
    hasApiKey: Boolean,
    onRequestStorage: () -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val lastLength = state.messages.lastOrNull()?.text?.length ?: 0

    LaunchedEffect(state.messages.size, lastLength) {
        val last = state.messages.lastIndex
        if (last >= 0) listState.scrollToItem(last)
    }

    Column(Modifier.fillMaxSize()) {
        if (!hasApiKey) SetupCard(onOpenSettings)
        if (!hasStorage) StorageBanner(onRequestStorage)
        PlanCard(state.plan)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (state.skillsLoaded) "技能已载入 · 可开工" else "技能未载入 · 首次写入会自动拦截",
                style = MaterialTheme.typography.labelSmall,
                color = if (state.skillsLoaded) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onClear) { Text("清空会话", fontSize = 12.sp) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.messages.isEmpty()) {
                item { EmptyHint(onPick = { input = it }) }
            }
            items(state.messages, key = { it.id }) { message ->
                MessageRow(message)
            }
        }

        if (state.busy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        state.lastError?.let { ErrorBanner(it, onOpenSettings) }

        InputBar(
            value = input,
            onValueChange = { input = it },
            busy = state.busy,
            onSend = {
                val text = input
                if (text.isNotBlank()) {
                    input = ""
                    onSend(text)
                }
            },
            onStop = onStop,
        )
    }
}

@Composable
private fun SetupCard(onOpenSettings: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "先连上模型，才能开工",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                "① 设置页填 Base URL（到 /v1）② 填 API Key ③ 点「测试连接」\n④ 授予「所有文件访问」后就能读写手机目录",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
            TextButton(onClick = onOpenSettings) { Text("去设置") }
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onOpenSettings: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            Modifier.padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "⚠ $message",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
                maxLines = 3,
            )
            TextButton(onClick = onOpenSettings) { Text("设置", fontSize = 12.sp) }
        }
    }
}

@Composable
private fun EmptyHint(onPick: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("PocketForge", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "说一句目标就行，AI 会先读技能说明，再建目录、分步写文件",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 18.sp,
        )
        Spacer(Modifier.height(12.dp))
        QUICK_PROMPTS.forEach { prompt ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .clickable { onPick(prompt) },
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    prompt,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "技能闸门开启时：没读技能就写文件，会被工具层直接拒绝",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun MessageRow(message: MsgUi) {
    val isUser = message.role == "user"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (isUser) 36.dp else 0.dp, end = if (isUser) 0.dp else 20.dp),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        if (message.steps.isNotEmpty()) {
            Column(Modifier.fillMaxWidth()) {
                message.steps.forEachIndexed { index, step ->
                    StepCard(index + 1, step)
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
        if (message.text.isNotBlank()) {
            Surface(
                color = if (isUser) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
            ) {
                SelectionContainer {
                    Text(
                        text = message.text,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        fontSize = 14.sp,
                        lineHeight = 21.sp,
                    )
                }
            }
        }
        if (message.streaming) {
            Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(11.dp), strokeWidth = 1.5.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    "思考中…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        message.error?.let {
            Text(
                "⚠ $it",
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun StepCard(index: Int, step: StepUi) {
    var expanded by remember(step.id) { mutableStateOf(false) }
    val statusColor = when (step.status) {
        StepStatus.RUNNING -> MaterialTheme.colorScheme.primary
        StepStatus.OK -> Color(0xFF4FD1C5)
        StepStatus.FAILED -> MaterialTheme.colorScheme.error
    }
    val statusText = when (step.status) {
        StepStatus.RUNNING -> "执行中"
        StepStatus.OK -> "完成"
        StepStatus.FAILED -> "失败"
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(
            Modifier
                .clickable { expanded = !expanded }
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$index", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text(
                    step.title,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Text(statusText, fontSize = 11.sp, color = statusColor)
                Spacer(Modifier.width(6.dp))
                Text(if (expanded) "收起" else "展开", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
            }
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(top = 6.dp)) {
                    Text(
                        "参数",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        step.args,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 15.sp,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                    if (step.output.isNotBlank()) {
                        Text(
                            "输出",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            step.output,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 15.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    busy: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 4.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("描述目标，AI 先读技能再动手…", fontSize = 13.sp) },
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            )
            Spacer(Modifier.width(6.dp))
            if (busy) {
                IconButton(onClick = onStop) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "停止",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            } else {
                IconButton(onClick = onSend, enabled = value.isNotBlank()) {
                    Icon(
                        Icons.Default.Send,
                        contentDescription = "发送",
                        tint = if (value.isNotBlank()) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
