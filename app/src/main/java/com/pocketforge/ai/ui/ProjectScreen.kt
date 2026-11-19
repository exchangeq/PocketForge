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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val TYPES = listOf("game" to "游戏", "writing" to "写作", "app" to "应用")

@Composable
fun ProjectScreen(
    workspacePath: String,
    projects: List<String>,
    skills: List<Triple<String, String, Boolean>>,
    forced: Boolean,
    repoUrl: String,
    locks: List<Pair<String, String>>,
    onRefresh: () -> Unit,
    onCreate: (String, String, String, String) -> String,
    onSkillClick: (String) -> String,
    onSyncBundled: () -> String,
    onRemoteInstall: (String, Boolean) -> String,
    onForceInject: () -> String,
    onDisableForce: () -> String,
    onUnlock: (String) -> String,
    onPickFiles: () -> Unit = {},
    onProjectClick: (String) -> List<String> = { emptyList() },
    onReadFile: (String) -> String = { "" },
    onSaveFile: (String, String) -> String = { _, _ -> "" },
    onOpenChat: (String) -> String = { "" },
    onImportDocs: () -> Unit = {},
    onEnterProjectChat: (String, String) -> String = { _, _ -> "" },
    notice: String = "",
) {
    val scope = rememberCoroutineScope()
    var showCreate by remember { mutableStateOf(false) }
    var projectName by remember { mutableStateOf("") }
    var projectType by remember { mutableStateOf("game") }
    var projectEngine by remember { mutableStateOf("") }
    var projectScale by remember { mutableStateOf("standard") }
    var message by remember { mutableStateOf("") }
    var skillContent by remember { mutableStateOf<String?>(null) }
    var skillTitle by remember { mutableStateOf("") }
    var installUrl by remember { mutableStateOf("") }
    var forceInstall by remember { mutableStateOf(true) }
    var working by remember { mutableStateOf(false) }
    var createResult by remember { mutableStateOf<String?>(null) }
    var fileList by remember { mutableStateOf<List<String>?>(null) }
    var fileListTitle by remember { mutableStateOf("") }
    var editorPath by remember { mutableStateOf<String?>(null) }
    var editorText by remember { mutableStateOf("") }
    var editorTip by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
    ) {
        item {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "工作区",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(workspacePath, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    Text(
                        "写入只发生在这里；读手机根目录请到「设置」确认文件权限。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { showCreate = true },
                ) {
                    Text(
                        "新建项目骨架",
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
                TextButton(onClick = onImportDocs, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text("导入文档", fontSize = 12.sp)
                }
                TextButton(onClick = onRefresh, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text("刷新", fontSize = 12.sp)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "${projects.size} 个项目",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(projects) { name ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenChat(name) },
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(
                            "projects/$name",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    TextButton(
                        onClick = {
                            scope.launch {
                                fileListTitle = name
                                fileList = withContext(Dispatchers.IO) { onProjectClick(name) }
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 6.dp),
                    ) { Text("文件", fontSize = 11.sp) }
                    Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        item {
            if (projects.isEmpty()) {
                Text(
                    "还没有项目。新建一个，或直接在对话里让 AI 建。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            Column(Modifier.padding(top = 6.dp)) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                SectionTitle(
                    "技能中心",
                    if (forced) "强制注入已开启：本工作区只认 skills/ 里的文件（不影响别的项目）"
                    else "内置基线 + 工作区覆盖；远程可注入 .md 或 skills-index.json 清单"
                )
            }
        }

        item {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = installUrl,
                        onValueChange = { installUrl = it },
                        label = { Text("技能地址（目录或 .md / .json）") },
                        placeholder = { Text(repoUrl) },
                        singleLine = true,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = forceInstall, onCheckedChange = { forceInstall = it })
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("覆盖并强制注入", style = MaterialTheme.typography.labelMedium)
                            Text(
                                "只对当前工作区生效",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(
                            enabled = !working,
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            onClick = {
                                working = true
                                scope.launch {
                                    message = withContext(Dispatchers.IO) {
                                        onRemoteInstall(installUrl.trim(), forceInstall)
                                    }
                                    working = false
                                }
                            },
                        ) { Text(if (working) "注入中…" else "远程注入") }
                        TextButton(
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            onClick = onPickFiles,
                        ) { Text("手机文件注入") }
                        TextButton(
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            onClick = {
                                scope.launch {
                                    message = withContext(Dispatchers.IO) { onSyncBundled() }
                                }
                            },
                        ) { Text("同步内置") }
                        if (forced) {
                            TextButton(
                                contentPadding = PaddingValues(horizontal = 8.dp),
                                onClick = { message = onDisableForce() },
                            ) { Text("取消强制") }
                        } else {
                            TextButton(
                                contentPadding = PaddingValues(horizontal = 8.dp),
                                onClick = {
                                    scope.launch {
                                        message = withContext(Dispatchers.IO) { onForceInject() }
                                    }
                                },
                            ) { Text("强制注入") }
                        }
                    }
                    if (notice.isNotEmpty()) {
                        Text(
                            notice,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            lineHeight = 16.sp,
                        )
                    }
                    if (message.isNotEmpty()) {
                        Text(
                            message,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            lineHeight = 16.sp,
                        )
                    }
                }
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Text("技能文件", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(
                    "${skills.count { it.third }}/${skills.size} 已注入",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(skills) { (name, desc, installed) ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        skillTitle = name
                        skillContent = onSkillClick(name)
                    },
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                        Text(
                            desc,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 15.sp,
                        )
                    }
                    Surface(
                        shape = MaterialTheme.shapes.extraSmall,
                        color = if (installed) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Text(
                            if (installed) "已注入" else "内置",
                            fontSize = 10.sp,
                            color = if (installed) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        )
                    }
                }
            }
        }

        item {
            Column(Modifier.padding(top = 8.dp)) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                SectionTitle(
                    "锁定文档",
                    if (locks.isEmpty()) "定稿的设定/章节可以锁起来，AI 写入会被直接拒绝"
                    else "共 ${locks.size} 个：写入被拦，需要改动先解锁"
                )
            }
        }

        items(locks) { (path, scopeText) ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(path, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                        Text(
                            scopeText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    TextButton(
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        onClick = {
                            scope.launch {
                                message = withContext(Dispatchers.IO) { onUnlock(path) }
                                onRefresh()
                            }
                        },
                    ) { Text("解锁", fontSize = 11.5.sp) }
                }
            }
        }

        item {
            if (locks.isEmpty()) {
                Text(
                    "在对话里说「把 world.md 锁定」，AI 会调 doc_lock。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item { Spacer(Modifier.height(20.dp)) }
    }

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("新建项目骨架") },
            text = {
                Column {
                    OutlinedTextField(
                        value = projectName,
                        onValueChange = { projectName = it },
                        label = { Text("项目名") },
                        singleLine = true,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TYPES.forEach { (key, label) ->
                            TextButton(onClick = { projectType = key }) {
                                Text(
                                    label,
                                    color = if (projectType == key) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = if (projectType == key) FontWeight.Bold else FontWeight.Normal,
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = projectEngine,
                        onValueChange = { projectEngine = it },
                        label = { Text("引擎/框架（可留空）") },
                        singleLine = true,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("minimal" to "最小", "standard" to "标准", "full" to "完整").forEach { (key, label) ->
                            TextButton(onClick = { projectScale = key }) {
                                Text(
                                    label,
                                    color = if (projectScale == key) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = if (projectScale == key) FontWeight.Bold else FontWeight.Normal,
                                )
                            }
                        }
                    }
                    Text(
                        "最小只建目录与三份说明；标准加设计文档；完整再加代码骨架。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = projectName.trim()
                    if (name.isNotEmpty()) {
                        showCreate = false
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                onCreate(name, projectType, projectEngine.trim(), projectScale)
                            }
                            projectName = ""
                            projectEngine = ""
                            message = "已创建 $name"
                            projectScale = "standard"
                            createResult = result
                            onRefresh()
                        }
                    }
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("取消") } },
        )
    }

    createResult?.let { result ->
        AlertDialog(
            onDismissRequest = { createResult = null },
            title = {
                val failed = result.contains("失败") || result.contains("拒绝") || result.contains("缺少")
                Text(
                    if (failed) "没有建成，原因如下" else "骨架已生成",
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            text = {
                SelectionContainer {
                    Column(
                        Modifier
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            result,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 18.sp,
                            color = if (result.contains("失败") || result.contains("拒绝"))
                                MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { createResult = null }) { Text("知道了") } },
        )
    }

    editorPath?.let { path ->
        AlertDialog(
            onDismissRequest = { editorPath = null },
            title = { Text(path.substringAfterLast("/"), style = MaterialTheme.typography.titleSmall) },
            text = {
                Column {
                    OutlinedTextField(
                        value = editorText,
                        onValueChange = { editorText = it },
                        modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp),
                        textStyle = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    )
                    if (editorTip.isNotBlank()) {
                        Text(editorTip, fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        editorTip = withContext(Dispatchers.IO) { onSaveFile(path, editorText) }
                        if (editorTip.startsWith("已保存")) {
                            editorPath = null
                        }
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editorPath = null }) { Text("关闭") } },
        )
    }

    fileList?.let { files ->
        AlertDialog(
            onDismissRequest = { fileList = null },
            title = { Text("$fileListTitle（${files.size} 项）", style = MaterialTheme.typography.titleSmall) },
            text = {
                SelectionContainer {
                    Column(
                        Modifier
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        if (files.isEmpty()) {
                            Text("目录还是空的", style = MaterialTheme.typography.bodySmall)
                        } else {
                            files.forEach { path ->
                                val isDir = path.endsWith("/")
                                Row(
                                    Modifier.fillMaxWidth()
                                        .clickable(enabled = !isDir) {
                                            scope.launch {
                                                val full = "projects/$fileListTitle/" + path.removeSuffix("/")
                                                editorPath = full
                                                editorTip = ""
                                                editorText = withContext(Dispatchers.IO) { onReadFile(full) }
                                            }
                                        }
                                        .padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        path,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        lineHeight = 17.sp,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (!isDir) Text("编辑", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(onClick = {
                        val name = fileListTitle
                        fileList = null
                        if (name.isNotBlank()) message = onEnterProjectChat(name, name)
                    }) { Text("进入项目对话") }
                    TextButton(onClick = { fileList = null }) { Text("关闭") }
                }
            },
        )
    }

    val content = skillContent
    if (content != null) {
        AlertDialog(
            onDismissRequest = { skillContent = null },
            title = { Text(skillTitle, style = MaterialTheme.typography.titleMedium) },
            text = {
                SelectionContainer {
                    Column(
                        Modifier
                            .heightIn(max = 440.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        MarkdownText(source = content, baseSize = 13, baseLine = 20)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { skillContent = null }) { Text("关闭") } },
        )
    }
}
