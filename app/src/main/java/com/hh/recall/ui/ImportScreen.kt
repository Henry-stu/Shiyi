package com.hh.recall.ui

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hh.recall.importing.DocumentImport
import com.hh.recall.importing.ImportPage
import com.hh.recall.importing.ImportType
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(session: DocumentImport, saving: Boolean, onImport: () -> Unit) {
    var discard by remember { mutableStateOf(false) }
    var titleRequired by remember { mutableStateOf(false) }
    var zoom by remember { mutableStateOf<ImportPage?>(null) }
    var viewerError by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    fun leave() {
        if (saving) return
        if (session.preview != null) discard = true else session.dismiss()
    }
    Dialog(onDismissRequest = ::leave, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize()) {
            Scaffold(modifier = Modifier.fillMaxSize().imePadding().testTag("importScreen"),
                topBar = {
                    TopAppBar(title = { Text(if (session.running) "正在读取" else "导入预览", fontWeight = FontWeight.SemiBold) },
                        navigationIcon = { IconButton(onClick = ::leave, enabled = !saving && !session.cancelling, modifier = Modifier.testTag("importBack")) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
                        } })
                },
                bottomBar = {
                    if (session.preview != null) Surface(tonalElevation = 3.dp) {
                        Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp).widthIn(max = 960.dp)) {
                            if (session.preview?.needsReview == true) Row(verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable(enabled = !saving) { session.checked = !session.checked }) {
                                Checkbox(checked = session.checked, onCheckedChange = { session.checked = it }, enabled = !saving, modifier = Modifier.testTag("importReviewed"))
                                Text("已对照原文件核对文字和阅读顺序", fontSize = 14.sp)
                            }
                            Button(onClick = {
                                if (session.title.isBlank()) titleRequired = true else { keyboard?.hide(); onImport() }
                            }, enabled = !saving && session.body.isNotBlank() && (session.preview?.needsReview != true || session.checked),
                                modifier = Modifier.fillMaxWidth().testTag("confirmImport")) { Text(if (saving) "正在保存…" else "导入文章") }
                        }
                    }
                }
            ) { padding ->
                LazyColumn(state = list, modifier = Modifier.padding(padding).fillMaxSize().testTag("importContent"),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(session.type.label, style = MaterialTheme.typography.titleMedium)
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(session.status, modifier = Modifier.weight(1f).testTag("importHint"),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (session.preview != null && session.uri != null) TextButton(onClick = {
                                    try {
                                        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(session.uri, when(session.type.family) { "png" -> "image/png"; "pdf" -> "application/pdf"; else -> "text/plain" }).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                                    } catch (_: Exception) { viewerError = true }
                                }, modifier = Modifier.testTag("openImportSource")) {
                                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp)); Text("打开原文件")
                                }
                            }
                            if (viewerError) Text("没有可打开此文件的应用。", fontSize = 13.sp)
                            if (session.preview?.needsReview == true)
                                Text("图片识别可能出现错字、漏字或顺序差异，请校对后导入。", fontSize = 14.sp)
                            if (session.running) {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                                Text("识别在本机完成，长文件需要一些时间。", fontSize = 14.sp)
                                OutlinedButton(onClick = session::dismiss, enabled = !session.cancelling, modifier = Modifier.testTag("cancelImport")) { Text(if (session.cancelling) "正在取消…" else "取消读取") }
                            } else if (session.errorCode != null) {
                                Text("未创建文章，你可以返回后重新选择文件。", fontSize = 14.sp)
                                if (session.errorCode == "ENCODING_REQUIRED") OutlinedButton(onClick = session::retryLegacy) { Text("按 GB18030 重试") }
                            }
                        }
                    }
                    if (session.preview != null) {
                        item {
                            OutlinedTextField(value = session.title, onValueChange = session::title, enabled = !saving,
                                label = { Text("文章标题") }, singleLine = true,
                                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("importTitle"))
                        }
                        item {
                            val page = session.preview?.pages?.singleOrNull().takeIf { session.type == ImportType.PNG }
                            BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                if (page?.bitmap != null && maxWidth >= 840.dp) {
                                    Row(Modifier.fillMaxWidth().testTag("importComparisonWide"), horizontalArrangement = Arrangement.spacedBy(24.dp),
                                        verticalAlignment = Alignment.Top) {
                                        PngSource(page, Modifier.weight(1f)) { zoom = page }
                                        ImportBody(session, saving, Modifier.weight(1f))
                                    }
                                } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    if (page?.bitmap != null) PngSource(page, Modifier.fillMaxWidth()) { zoom = page }
                                    ImportBody(session, saving, Modifier.fillMaxWidth())
                                }
                            }
                        }
                        item {
                            session.preview!!.pages.flatMap { page -> page.hints.map { hint ->
                                if (session.type == ImportType.PDF) "第 ${page.number} 页：$hint" else hint
                            } }.distinct().forEach {
                                Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 6.dp))
                                }
                        }
                    }
                }
            }
            if (titleRequired) AlertDialog(onDismissRequest = { titleRequired = false }, title = { Text("文章标题还未命名") },
                text = { Text("请填写文章标题后再导入。") }, confirmButton = { TextButton(onClick = {
                    titleRequired = false
                    scope.launch { list.animateScrollToItem(1); focus.requestFocus(); keyboard?.show() }
                }) { Text("去命名") } })
            if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("放弃这次导入？") },
                text = { Text("本次预览和修改不会保存，已有文章不受影响。") },
                confirmButton = { TextButton(onClick = { discard = false; session.dismiss() }) { Text("放弃导入") } },
                dismissButton = { TextButton(onClick = { discard = false }) { Text("继续校对") } })
            zoom?.let { page ->
                Dialog(onDismissRequest = { zoom = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                    var scale by remember { mutableFloatStateOf(1f) }
                    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("原图 · 双指缩放、拖动查看", Modifier.weight(1f))
                                TextButton(onClick = { zoom = null }) { Text("关闭") }
                            }
                            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clipToBounds().testTag("importImageZoom"), contentAlignment = Alignment.Center) {
                                val density = androidx.compose.ui.platform.LocalDensity.current
                                val width = with(density) { maxWidth.toPx() }; val height = with(density) { maxHeight.toPx() }
                                val gestures = rememberTransformableState { factor, pan, _ ->
                                    scale = (scale * factor).coerceIn(1f, 8f)
                                    val next = if (scale == 1f) androidx.compose.ui.geometry.Offset.Zero else offset + pan
                                    offset = androidx.compose.ui.geometry.Offset(next.x.coerceIn(-width * (scale - 1) / 2, width * (scale - 1) / 2),
                                        next.y.coerceIn(-height * (scale - 1) / 2, height * (scale - 1) / 2))
                                }
                                page.bitmap?.let { Image(it.asImageBitmap(), "原图放大预览", contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize().transformable(gestures).graphicsLayer {
                                    scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
                                }) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PngSource(page: ImportPage, modifier: Modifier, onZoom: () -> Unit) {
    Column(modifier.testTag("importPage-${page.number}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("原图", style = MaterialTheme.typography.titleMedium)
        Text("点击图片可全屏放大，双指缩放查看细节。", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        page.bitmap?.let { bitmap ->
            Image(bitmap.asImageBitmap(), "PNG原图，点击放大", contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat() / bitmap.height)
                    .testTag("importSourceImage").clickable(onClick = onZoom))
        }
    }
}

@Composable
private fun ImportBody(session: DocumentImport, saving: Boolean, modifier: Modifier) {
    Column(modifier) {
        OutlinedTextField(value = session.body, onValueChange = session::body, enabled = !saving,
            label = { Text("文章正文") }, minLines = 10, maxLines = 24,
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp),
            modifier = Modifier.fillMaxWidth().testTag("importBody"))
        Text("${session.body.length} 字符", fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
    }
}
