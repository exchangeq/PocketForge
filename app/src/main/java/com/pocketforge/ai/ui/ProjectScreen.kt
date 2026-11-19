package com.pocketforge.ai.ui

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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
    skills: List<Pair<String, String>>,
    onRefresh: () -> Unit,
    onCreate: (String, String, String) -> String,
    onSkillClick: (String) -> String,
) {
    val scope = rememberCoroutineScope()
    var showCreate by remember { mutableStateOf(false) }
    var projectName by remember { mutableStateOf("") }
    var projectType by remember { mutableStateOf("game") }
    var projectEngine by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var skillContent by remember { mutableStateOf<String?>(null) }
    var skillTitle by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(12.dp)) {
                Text("工作区", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(workspacePath, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                Text(
                    "写入只发生在这里；读手机根目录请到「设置」确认文件权限。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { showCreate = true }) { Text("新建项目骨架") }
            TextButton(onClick = onRefresh) { Text("刷新") }
        }
        if (message.isNotEmpty()) {
            Text(message, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }

        HorizontalDivider()
        Text("项目（${projects.size}）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

        if (projects.isEmpty()) {
            Text(
                "还没有项目。新建一个，或直接在对话里让 AI 建。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            items(projects) { name ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(
                            "$workspacePath/projects/$name",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        HorizontalDivider()
        Text("技能库（Markdown 规范）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        skills.forEach { (file, desc) ->
            ClickRow(title = file, subtitle = desc) {
                skillTitle = file
                skillContent = onSkillClick(file)
            }
        }
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
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "会生成 README / PROJECT.md / AGENTS.md / plan 与技能副本。",
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
                            message = withContext(Dispatchers.IO) {
                                onCreate(name, projectType, projectEngine.trim())
                            }
                            projectName = ""
                            onRefresh()
                        }
                    }
                }) { Text("创建") }
            },
            dismissButton = {
                TextButton(onClick = { showCreate = false }) { Text("取消") }
            },
        )
    }

    val content = skillContent
    if (content != null) {
        AlertDialog(
            onDismissRequest = { skillContent = null },
            title = { Text(skillTitle) },
            text = {
                SelectionContainer {
                    Column(
                        Modifier
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(content, fontSize = 12.sp, lineHeight = 17.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { skillContent = null }) { Text("关闭") }
            },
        )
    }
}
