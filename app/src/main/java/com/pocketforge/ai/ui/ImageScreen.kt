package com.pocketforge.ai.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketforge.ai.core.ImageClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val SIZE_PRESETS = listOf("1:1 方图" to "1024x1024", "3:4 竖版" to "1024x1536", "16:9 横版" to "1536x1024")

private val STYLE_CHIPS = listOf(
    "写实摄影" to "写实摄影风格，自然光，真实质感，浅景深",
    "胶片" to "胶片摄影，轻微颗粒，暖调，复古氛围",
    "二次元" to "日系二次元插画，干净线条，柔和赛璐璐上色",
    "像素" to "像素艺术，16bit 配色，清晰像素边缘",
    "水彩" to "水彩手绘，纸张纹理，晕染边缘",
    "3D 渲染" to "3D 渲染，柔和棚拍光，细腻材质",
    "扁平插画" to "扁平矢量插画，纯色块，无渐变",
    "国潮" to "国潮插画，朱红与金色，祥云纹样",
)

/** 生图页：上半是创作，下半是图库（按时间倒序，提示词可复用） */
@Composable
fun ImageScreen(
    busy: Boolean,
    provider: String,
    defaultModel: String,
    gallery: List<ImageClient.ImageMeta>,
    onGenerate: suspend (String, String, String) -> String,
    onRefreshGallery: () -> Unit,
    onDelete: (String) -> String,
) {
    var prompt by remember { mutableStateOf("") }
    var size by remember { mutableStateOf("1024x1024") }
    var model by remember { mutableStateOf(defaultModel.ifBlank { "flux" }) }
    var result by remember { mutableStateOf("") }
    var generating by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<ImageClient.ImageMeta?>(null) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val free = provider != "openai"

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("生图通道", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text(
                            if (free) "Pollinations 免费通道 · 默认 flux（当前免费模型里画质最好），不用填 Key，也不耗你的 API 额度"
                            else "OpenAI 兼容通道 · ${defaultModel}（消耗你自己的额度，需要 Key）",
                            fontSize = 10.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("提示词：主体 + 动作 + 场景 + 光线 + 风格") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                Text("一键加风格", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(STYLE_CHIPS.size) { i ->
                        val (label, suffix) = STYLE_CHIPS[i]
                        AssistChip(
                            onClick = { prompt = (prompt.trim() + "，" + suffix).trimStart('，') },
                            label = { Text(label, fontSize = 11.sp) },
                            colors = AssistChipDefaults.assistChipColors(),
                        )
                    }
                }
            }

            item {
                Text("画幅", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SIZE_PRESETS.forEach { (label, value) ->
                        FilterChip(
                            selected = size == value,
                            onClick = { size = value },
                            label = { Text(label, fontSize = 11.sp) },
                        )
                    }
                }
            }

            if (!free) {
                item {
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        label = { Text("图片模型（OpenAI 兼容通道用）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("模型", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        FilterChip(selected = model == "flux", onClick = { model = "flux" }, label = { Text("flux 画质好", fontSize = 11.sp) })
                        Spacer(Modifier.width(6.dp))
                        FilterChip(selected = model == "turbo", onClick = { model = "turbo" }, label = { Text("turbo 更快", fontSize = 11.sp) })
                    }
                }
            }

            item {
                Button(
                    onClick = {
                        if (prompt.isBlank()) {
                            result = "先写提示词，哪怕一句「一只橘猫趴在窗台晒太阳」"
                            return@Button
                        }
                        generating = true
                        result = "正在出图…（免费通道排队时可能要 10–30 秒）"
                        scope.launch {
                            val msg = withContext(Dispatchers.IO) { onGenerate(prompt, size, model) }
                            generating = false
                            result = msg
                            onRefreshGallery()
                        }
                    },
                    enabled = !generating && !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (generating) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (generating) "生成中…" else "生成图片", fontSize = 14.sp)
                }
            }

            if (result.isNotBlank()) {
                item {
                    Text(
                        result,
                        fontSize = 11.5.sp,
                        color = if (result.contains("失败")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("图库 · ${gallery.size} 张", fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = onRefreshGallery) { Text("刷新", fontSize = 11.sp) }
                }
            }

            if (gallery.isEmpty()) {
                item {
                    Text(
                        "还没有生成过图片。上面写好提示词点「生成图片」，成品会自动存到工作区 assets/images/ 并出现在这里。",
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (gallery.isNotEmpty()) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(bottom = 10.dp),
            ) {
                items(gallery, key = { it.path }) { item ->
                    GalleryTile(item, onClick = { preview = item })
                }
            }
        }
    }

    preview?.let { item ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text(item.name, fontSize = 13.sp, maxLines = 2) },
            text = {
                Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                    val bmp = remember(item.path) { runCatching { BitmapFactory.decodeFile(item.path) }.getOrNull() }
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxWidth().aspectRatio(bmp.width.toFloat() / bmp.height),
                            contentScale = ContentScale.Fit,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("提示词", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(item.prompt, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    Text("${item.provider} · ${item.model} · ${item.size} · ${formatTime(item.time)}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    prompt = item.prompt
                    size = item.size.ifBlank { size }
                    preview = null
                }) { Text("复用提示词") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(item.prompt))
                        preview = null
                    }) { Text("复制") }
                    TextButton(onClick = {
                        val msg = onDelete(item.path)
                        result = msg
                        preview = null
                        onRefreshGallery()
                    }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                }
            },
        )
    }
}

@Composable
private fun GalleryTile(item: ImageClient.ImageMeta, onClick: () -> Unit) {
    val thumb = remember(item.path) {
        runCatching {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(item.path, opts)
            var sample = 1
            val target = 360
            while (opts.outWidth / sample > target * 2) sample *= 2
            BitmapFactory.decodeFile(item.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
    }
    Box(
        Modifier
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (thumb != null) {
            Image(
                bitmap = thumb.asImageBitmap(),
                contentDescription = item.prompt.take(40),
                modifier = Modifier.fillMaxSize().padding(2.dp),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text("读取失败", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatTime(ms: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(if (ms <= 0) System.currentTimeMillis() else ms))
