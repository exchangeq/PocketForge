package com.pocketforge.ai.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    onExport: () -> Unit = {},
    onRewind: () -> String = { "" },
    onSwitchSession: () -> Unit = {},
    onEditMessage: (String, String) -> String = { _, _ -> "" },
    onToggleNoRecall: () -> String = { "" },
    onCopyTranscript: () -> String = { "" },
    onOpenSettings: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    var noRecall by remember(state.sessionTitle) { mutableStateOf(state.sessionTitle.contains("锁定")) }
    var editing by remember { mutableStateOf<MsgUi?>(null) }
    var editText by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val attachLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val text = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    }.getOrNull()?.let { bytes ->
                        val name = runCatching {
                            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                                if (c.moveToFirst()) c.getString(0) else null
                            }
                        }.getOrNull() ?: "附件"
                        if (bytes.size > 200_000) null else "【附件：$name】\n" + String(bytes, Charsets.UTF_8) + "\n"
                    }
                }
                if (text == null) input = input + "\n（附件太大或不是文本，未读取；请控制在 200KB 内的 txt/md）"
                else input = (input + "\n" + text).trim()
            }
        }
    }
    val listState = rememberLazyListState()
    val lastLength = state.messages.lastOrNull()?.text?.length ?: 0

    // 倒序列表：最新消息固定在底部，新内容不会把已有阅读位置顶走
    LaunchedEffect(state.messages.size, lastLength) {
        if (listState.firstVisibleItemIndex <= 2) {
            listState.scrollToItem(0)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "会话 · " + state.sessionTitle,
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            TextButton(onClick = onSwitchSession, contentPadding = PaddingValues(horizontal = 6.dp)) {
                Text("切换", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
        if (!hasApiKey) SetupCard(onOpenSettings)
        if (!hasStorage) StorageBanner(onRequestStorage)
        PlanCard(state.plan)

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            state = listState,
            reverseLayout = true,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (state.messages.isEmpty()) {
                item { EmptyHint(onPick = { input = it }) }
            }
            items(state.messages.asReversed(), key = { it.id }) { message ->
                MessageRow(
                    message,
                    onEditUser = { input = it },
                    onLongPress = {
                        editing = it
                        editText = it.text
                    },
                )
            }
            item {
                if (state.messages.isNotEmpty() && !state.busy) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        TextButton(
                onClick = {
                    val text = onRewind()
                    if (text.isNotBlank()) input = text
                },
                enabled = state.messages.isNotEmpty() && !state.busy,
                contentPadding = PaddingValues(horizontal = 6.dp),
            ) { Text("撤回", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            TextButton(onClick = onExport, enabled = state.messages.isNotEmpty()) {
                            Text("导出", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                        }
                        TextButton(onClick = onClear) {
                            Text("清空本会话", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        state.lastError?.let { ErrorBanner(it, onOpenSettings) }

        Composer(
            value = input,
            onValueChange = { input = it },
            onAttach = { attachLauncher.launch(arrayOf("text/plain", "text/markdown", "application/json", "*/*")) },
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

        editing?.let { target ->
            AlertDialog(
                onDismissRequest = { editing = null },
                title = { Text("修改这条消息") },
                text = {
                    OutlinedTextField(
                        value = editText,
                        onValueChange = { editText = it },
                        modifier = Modifier.fillMaxWidth().height(180.dp),
                        textStyle = MaterialTheme.typography.bodyMedium,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val msg = onEditMessage(target.id, editText)
                        editing = null
                        if (msg != "已修改") input = input
                    }) { Text("保存") }
                },
                dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } },
            )
        }
    }
}

@Composable
private fun SetupCard(onOpenSettings: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "还没连上模型",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "设置页填 Base URL（到 /v1）和 API Key，点「测试连接」；再去系统里授予「所有文件访问」，就能读写手机目录。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                lineHeight = 19.sp,
            )
            Spacer(Modifier.height(2.dp))
            TextButton(onClick = onOpenSettings, contentPadding = PaddingValues(0.dp)) {
                Text("去设置 →", fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onOpenSettings: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                message,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
                maxLines = 3,
            )
            TextButton(onClick = onOpenSettings) {
                Text("设置", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

@Composable
private fun EmptyHint(onPick: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            modifier = Modifier.size(52.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.primary,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("PF", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("想让它做什么？", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            "说一句目标，它会先读技能说明，再建目录、分步写文件。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 19.sp,
        )
        Spacer(Modifier.height(18.dp))
        QUICK_PROMPTS.forEach { prompt ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable { onPick(prompt) },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        prompt,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    Text("填入", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "技能闸门开启：没读技能就写文件会被直接拒绝",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun MessageRow(
    message: MsgUi,
    onEditUser: (String) -> Unit = {},
    onLongPress: (MsgUi) -> Unit = {},
) {
    val isUser = message.role == "user"
    Column(Modifier.fillMaxWidth()) {
        if (isUser) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
                    modifier = Modifier
                        .fillMaxWidth(0.86f)
                        .combinedClickable(
                            onClick = { onEditUser(message.text) },
                            onLongClick = { onLongPress(message) },
                        ),
                ) {
                    Text(
                        message.text,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            return@Column
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Avatar()
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                if (message.steps.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        message.steps.forEachIndexed { index, step ->
                            StepCard(index + 1, step)
                        }
                    }
                    if (message.text.isNotBlank() || message.streaming) {
                        Spacer(Modifier.height(8.dp))
                    }
                }
                if (message.text.isNotBlank()) {
                    SelectionContainer {
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            MarkdownText(
                                source = message.text,
                                modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
                            )
                        }
                    }
                }
                if (message.streaming) {
                    Row(
                        Modifier.padding(top = if (message.text.isBlank()) 2.dp else 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TypingDots()
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "正在生成",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (message.text.isNotBlank() && !message.streaming) {
                    val clipboard = LocalClipboardManager.current
                    Row(Modifier.padding(top = 4.dp)) {
                        Text(
                            "复制",
                            fontSize = 10.5.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable { clipboard.setText(AnnotatedString(message.text)) }
                                .padding(horizontal = 2.dp, vertical = 2.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "${message.text.length} 字",
                            fontSize = 10.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                message.error?.let {
                    Text(
                        it,
                        modifier = Modifier.padding(top = 6.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun Avatar() {
    Surface(
        modifier = Modifier.size(26.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.primary,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text("AI", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun TypingDots() {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(520, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(index * 160),
                ),
                label = "dot$index",
            )
            Box(
                Modifier
                    .size(5.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha), CircleShape)
            )
        }
    }
}

@Composable
private fun StepCard(index: Int, step: StepUi) {
    var expanded by remember(step.id) { mutableStateOf(false) }
    val statusColor = when (step.status) {
        StepStatus.RUNNING -> MaterialTheme.colorScheme.primary
        StepStatus.OK -> Color(0xFF10B981)
        StepStatus.FAILED -> MaterialTheme.colorScheme.error
    }
    val statusText = when (step.status) {
        StepStatus.RUNNING -> "执行中"
        StepStatus.OK -> "完成"
        StepStatus.FAILED -> "失败"
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(statusColor)
            )
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "步骤 %02d".format(index),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        step.title,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = statusColor.copy(alpha = 0.12f),
                    ) {
                        Text(
                            statusText,
                            fontSize = 10.sp,
                            color = statusColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (expanded) "收起" else "展开",
                        fontSize = 10.5.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                AnimatedVisibility(visible = expanded) {
                    Column(Modifier.padding(top = 8.dp)) {
                        Text(
                            "参数",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            step.args,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 16.sp,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                        if (step.output.isNotBlank()) {
                            Text(
                                "输出",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                step.output,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 16.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    busy: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAttach: () -> Unit = {},
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    "附件",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { onAttach() }
                        .padding(end = 8.dp, bottom = 14.dp),
                )
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        maxLines = 5,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        decorationBox = { innerTextField ->
                            Box {
                                if (value.isEmpty()) {
                                    Text(
                                        "描述目标，AI 会先读技能再动手…",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    )
                                }
                                innerTextField()
                            }
                        },
                    )
                }
                Spacer(Modifier.width(8.dp))
                Surface(
                    modifier = Modifier
                        .size(46.dp)
                        .clickable { if (busy) onStop() else if (value.isNotBlank()) onSend() },
                    shape = CircleShape,
                    color = if (busy) MaterialTheme.colorScheme.errorContainer
                    else if (value.isNotBlank()) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (busy) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "停止",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp),
                            )
                        } else {
                            Icon(
                                Icons.Default.Send,
                                contentDescription = "发送",
                                tint = if (value.isNotBlank()) Color.White
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(19.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
