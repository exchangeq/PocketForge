package com.pocketforge.ai.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketforge.ai.agent.AgentEngine
import com.pocketforge.ai.data.PersonaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 人设页：当前对话的锁定人设 + 仓库包浏览 / 下载 / 预览 / 写入 + 本地 .aist 导入 */
@Composable
fun PersonaScreen(
    current: PersonaStore.Persona?,
    packsInitial: List<AgentEngine.PersonaPack>,
    onCreate: (String, String, String, String, String, String) -> String,
    onLoad: suspend (Boolean) -> List<AgentEngine.PersonaPack>,
    onFetch: suspend (String) -> AgentEngine.PersonaPackContent,
    onImport: suspend (Uri) -> AgentEngine.PersonaPackContent,
    onRefreshCurrent: () -> Unit,
    onStartChat: (String, String) -> Unit = { _, _ -> },
    onOpenChat: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

    var packs by remember { mutableStateOf(packsInitial) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("全部") }
    var loading by remember { mutableStateOf(false) }
    var downloadingId by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<AgentEngine.PersonaPackContent?>(null) }
    var previewWarn by remember { mutableStateOf(false) }
    var writeName by remember { mutableStateOf("") }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                downloadingId = "__local__"
                runCatching { withContext(Dispatchers.IO) { onImport(uri) } }
                    .onSuccess { c ->
                        preview = c
                        previewWarn = false
                        writeName = c.name.substringBefore(" ·").take(24)
                        status = "本地包已解压：${c.parts} 个文件 ${c.bytes} 字"
                    }
                    .onFailure { status = "本地导入失败：" + (it.message ?: "读不出来") }
                downloadingId = null
            }
        }
    }

    val visible = packs.filter { p ->
        val hitQuery = query.isBlank() || p.name.contains(query, true) || p.desc.contains(query, true)
        val hitFilter = when (filter) {
            "常规" -> !p.name.contains("病娇") && !p.desc.contains("病娇") && !p.name.contains("囚禁") && !p.desc.contains("囚禁")
            "病娇" -> p.name.contains("病娇") || p.desc.contains("病娇")
            "囚禁" -> p.name.contains("囚禁") || p.desc.contains("囚禁")
            else -> true
        }
        hitQuery && hitFilter
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("当前对话的人设", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (current == null) {
                        Text("还没写入人设", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("在下面挑一个包，或导入本地 .aist；写入后本对话内不可更改。", fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text("${current.name} · 已锁定", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("本对话内不可修改、不可删除；要换人设只能新建对话页。", fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = onOpenChat, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp)) { Text("进入人设对话 ›", fontSize = 10.5.sp) }
                    }
                }
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索角色 / 性格", fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                TextButton(onClick = {
                    scope.launch {
                        loading = true
                        status = "正在拉取仓库清单…"
                        runCatching { withContext(Dispatchers.IO) { onLoad(true) } }
                            .onSuccess { list ->
                                packs = list
                                status = "共 ${list.size} 个包"
                            }
                            .onFailure { status = "清单加载失败：" + (it.message ?: "网络错误") }
                        loading = false
                    }
                }, enabled = !loading) { Text(if (loading) "…" else "刷新", fontSize = 11.sp) }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("全部", "常规", "病娇", "囚禁").forEach { f ->
                    FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f, fontSize = 11.sp) })
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("共 ${packs.size} 个包 · 显示 ${visible.size}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    runCatching { importer.launch(arrayOf("*/*")) }.onFailure { status = "没有文件选择器" }
                }, enabled = downloadingId == null) { Text("导入本地 .aist", fontSize = 10.5.sp) }
            }
            if (status.isNotBlank()) Text(status, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        items(visible, key = { it.id }) { pack ->
            val isY = pack.name.contains("病娇") || pack.desc.contains("病娇")
            val isQ = pack.name.contains("囚禁") || pack.desc.contains("囚禁")
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(pack.name, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                        Text(pack.desc, fontSize = 9.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (pack.zip.isNotBlank()) {
                                Badge("AIST", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer)
                            }
                            if (isY) Badge("病娇向", MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.errorContainer)
                            if (isQ) Badge("囚禁向·虚构", MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.errorContainer)
                            if (pack.extCount > 0) Text("含 ${pack.extCount} 个扩展包", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    if (downloadingId == pack.id) {
                        CircularProgressIndicator(Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
                    } else {
                        TextButton(onClick = {
                            scope.launch {
                                downloadingId = pack.id
                                runCatching { withContext(Dispatchers.IO) { onFetch(pack.id) } }
                                    .onSuccess { c ->
                                        preview = c
                                        previewWarn = isQ
                                        writeName = pack.name.substringBefore(" ·").take(24)
                                        status = "已下载：${c.parts} 个文件 ${c.bytes} 字"
                                    }
                                    .onFailure { status = "下载失败：" + (it.message ?: "网络错误") }
                                downloadingId = null
                            }
                        }) { Text("下载", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary) }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(6.dp)) }
    }

    preview?.let { c ->
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { preview = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                modifier = Modifier.fillMaxSize().padding(10.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("确认人设包", fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { preview = null }) { Text("关闭", fontSize = 11.sp) }
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
                        Text(c.name, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("${c.parts} 个文件 · ${c.bytes} 字", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (previewWarn) {
                            Spacer(Modifier.height(8.dp))
                            Surface(color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f), shape = RoundedCornerShape(8.dp)) {
                                Text(
                                    "⚠ 囚禁向是虚构剧情扩展：只作为双方明确同意、可随时退出的角色扮演。现实中限制他人自由是违法的；剧情里出现“今天就到这儿”必须立刻结束并回到日常。不写任何现实操作细节。",
                                    fontSize = 10.5.sp,
                                    modifier = Modifier.padding(8.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("名字", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        OutlinedTextField(value = writeName, onValueChange = { writeName = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("写入后不可改", fontSize = 10.sp) })
                        Spacer(Modifier.height(8.dp))
                        Text("正文预览（前 600 字）", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), shape = RoundedCornerShape(8.dp)) {
                            Text(
                                c.raw.take(600) + if (c.raw.length > 600) "…" else "",
                                fontSize = 10.sp,
                                modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()).padding(10.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(c.raw)) }) { Text("复制全文", fontSize = 11.sp) }
                        }
                        Spacer(Modifier.height(60.dp))
                    }
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { preview = null }) { Text("取消") }
                        Spacer(Modifier.width(6.dp))
                        TextButton(onClick = {
                            if (writeName.isBlank()) { status = "名字不能为空"; return@TextButton }
                            val msg = onCreate(writeName, "", "", "", "", c.raw)
                            status = msg
                            if (msg.contains("已强行")) { preview = null; onRefreshCurrent() }
                        }) { Text("写入当前对话") }
                        Spacer(Modifier.width(6.dp))
                        Button(onClick = {
                            if (writeName.isBlank()) { status = "名字不能为空"; return@Button }
                            onStartChat(writeName, c.raw)
                            preview = null
                            status = "已新开人设对话：" + writeName
                        }) { Text("开新对话并锁定") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Badge(text: String, color: androidx.compose.ui.graphics.Color, bg: androidx.compose.ui.graphics.Color) {
    Text(
        text,
        fontSize = 9.sp,
        color = color,
        modifier = Modifier
            .background(bg.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}
