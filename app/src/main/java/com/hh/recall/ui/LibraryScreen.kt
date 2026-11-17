package com.hh.recall.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.hh.recall.AppModel
import com.hh.recall.core.Article
import com.hh.recall.core.Folder
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun LibraryScreen(vm: AppModel, articles: List<Article>, folders: List<Folder>, draftIds: Set<String>, grid: LazyGridState,
    onOpen: (Article) -> Unit, onRename: (Article) -> Unit, onMove: (Article) -> Unit, onDelete: (Article) -> Unit,
    onFolder: (Folder?) -> Unit, onFolderDelete: (Folder) -> Unit,
    onDrop: (Article, String?) -> Unit, folderContent: Boolean = false) {
    val search = if (folderContent) vm.folderQuery else vm.query
    val filtered = articles.filter { (if (folderContent) it.folderId == vm.currentFolder else (vm.folderFilter == null || it.folderId == vm.folderFilter || (vm.folderFilter == "unfiled" && it.folderId == null))) && it.title.contains(search, ignoreCase = true) }
    val drag = remember { LibraryDragState() }
    val folderScroll = rememberScrollState()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val edge = with(density) { 40.dp.toPx() }
    var rootBounds by remember { mutableStateOf(Rect.Zero) }
    val dropAction by rememberUpdatedState(onDrop)
    val active = drag.article != null
    val target = drag.target
    val destination = target?.let { id -> if (id == "unfiled") "未分类" else folders.find { it.id == id }?.name }
    val sameFolder = target != null && drag.article?.folderId == target.takeUnless { it == "unfiled" }
    val hint = when {
        sameFolder -> "已经在此文件夹"
        destination != null -> "松手移入「$destination」"
        else -> "拖到文件夹后松手"
    }
    // Only animate while dragging. Compose applies the system animator duration scale.
    val wobble = if (active) {
        val motion = rememberInfiniteTransition(label = "dragWiggle")
        val angle by motion.animateFloat(-.65f, .65f,
            infiniteRepeatable(tween(180, easing = LinearEasing), RepeatMode.Reverse), label = "cardWiggle")
        angle
    } else 0f
    BackHandler(active) { drag.cancel() }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { drag.cancel() }
    LaunchedEffect(vm.busy, articles, folders) {
        if (vm.busy || (drag.article != null && articles.none { it.id == drag.article?.id })) drag.cancel()
        drag.targets.keys.retainAll(folders.map { it.id }.toSet() + "unfiled")
    }
    LaunchedEffect(active) {
        while (drag.article != null) {
            val bar = drag.folderBar
            val point = drag.pointer
            if (bar.contains(point)) {
                val speed = when {
                    point.x < bar.left + edge -> -((bar.left + edge - point.x) / edge).coerceIn(0f, 1f)
                    point.x > bar.right - edge -> ((point.x - bar.right + edge) / edge).coerceIn(0f, 1f)
                    else -> 0f
                }
                if (speed != 0f) folderScroll.scrollBy(speed * with(density) { 7.dp.toPx() })
            }
            delay(16)
        }
    }
    DisposableEffect(Unit) { onDispose { drag.cancel() } }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("library").onGloballyPositioned { rootBounds = it.boundsInRoot() }, contentAlignment = Alignment.TopCenter) {
        val columns = if (maxWidth >= 720.dp) 2 else 1
        val gutter = if (maxWidth >= 720.dp) 32.dp else 20.dp
        Column(Modifier.widthIn(max = 1120.dp).fillMaxSize().padding(horizontal = gutter)) {
            Spacer(Modifier.height(18.dp))
            LibrarySearch(value = search, onValueChange = { if (folderContent) vm.folderQuery = it else vm.query = it })
            if (!folderContent) Row(Modifier.fillMaxWidth().padding(top = 12.dp).onGloballyPositioned { drag.folderBar = it.boundsInRoot() }
                .horizontalScroll(folderScroll, enabled = !active).testTag("folderBar"), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(vm.folderFilter == null, { vm.selectHomeFolder(null) }, enabled = !active, label = { Text("全部") }, modifier = Modifier.testTag("folder-all"))
                Spacer(Modifier.width(8.dp))
                FolderDropTarget("unfiled", drag, target == "unfiled") {
                    FilterChip(vm.folderFilter == "unfiled" || target == "unfiled", { if (!active) vm.selectHomeFolder("unfiled") }, label = { Text("未分类") },
                        border = if (target == "unfiled") BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else FilterChipDefaults.filterChipBorder(true, vm.folderFilter == "unfiled"))
                }
                folders.forEach { folder ->
                    Spacer(Modifier.width(8.dp)); var menu by remember(folder.id) { mutableStateOf(false) }
                    FolderDropTarget(folder.id, drag, target == folder.id) {
                    InputChip(vm.folderFilter == folder.id || target == folder.id, { if (!active) vm.selectHomeFolder(if (vm.folderFilter == folder.id) null else folder.id) },
                        label = { Text(folder.name, maxLines = 1) }, leadingIcon = { Icon(Icons.Outlined.FolderOpen, null, Modifier.size(17.dp)) },
                        border = if (target == folder.id) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else InputChipDefaults.inputChipBorder(true, vm.folderFilter == folder.id),
                        trailingIcon = { Box {
                            IconButton(onClick = { menu = true }, enabled = !vm.busy && !active, modifier = Modifier.size(32.dp)) { Icon(Icons.Outlined.MoreVert, "文件夹操作", Modifier.size(18.dp)) }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text("重命名") }, onClick = { menu = false; onFolder(folder) })
                                DropdownMenuItem(text = { Text("删除文件夹") }, onClick = { menu = false; onFolderDelete(folder) })
                            }
                        } }) }
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { onFolder(null) }, enabled = !vm.busy && !active) { Icon(Icons.Outlined.CreateNewFolder, "新建文件夹") }
            }
            Row(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (active) "拖动归类" else "学习材料", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(12.dp)); Text("${filtered.size} 篇", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                FilledTonalButton(onClick = { vm.newArticle() }, enabled = !vm.busy && !active, modifier = Modifier.testTag("newArticle")) { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(5.dp)); Text("新建") }
            }
            LazyVerticalGrid(columns = GridCells.Fixed(columns), state = grid, modifier = Modifier.weight(1f).testTag("articleGrid"),
                horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 108.dp)) {
                items(filtered, key = { it.id }) { article ->
                    val interaction = remember { MutableInteractionSource() }
                    val pressed by interaction.collectIsPressedAsState()
                    val scale by animateFloatAsState(if (pressed) .98f else 1f, tween(110), label = "cardPress")
                    var menu by remember { mutableStateOf(false) }
                    var cardBounds by remember { mutableStateOf(Rect.Zero) }
                    val latestArticle by rememberUpdatedState(article)
                    val canDrag by rememberUpdatedState(!vm.busy)
                    Card(onClick = { if (!active) onOpen(article) }, enabled = !vm.busy, interactionSource = interaction,
                        modifier = Modifier.fillMaxWidth().onGloballyPositioned { cardBounds = it.boundsInRoot() }
                            .pointerInput(article.id, folderContent) {
                                if (folderContent) return@pointerInput
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { offset -> if (canDrag) {
                                        menu = false
                                        drag.start(latestArticle, cardBounds.topLeft + offset)
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    } },
                                    onDrag = { change, delta -> change.consume(); drag.move(delta) },
                                    onDragEnd = {
                                        val source = drag.article
                                        val folder = drag.target
                                        drag.cancel()
                                        if (source != null && folder != null) {
                                            val folderId = folder.takeUnless { it == "unfiled" }
                                            if (source.folderId != folderId) dropAction(source, folderId)
                                        }
                                    },
                                    onDragCancel = { drag.cancel() })
                            }
                            .graphicsLayer {
                                scaleX = scale; scaleY = scale
                                alpha = if (drag.article?.id == article.id) .35f else 1f
                                rotationZ = if (active && drag.article?.id != article.id) wobble * (if (article.id.hashCode() % 2 == 0) 1 else -1) else 0f
                            }.testTag("article-${article.id}"),
                        shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp, pressedElevation = 0.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))) {
                        Column(Modifier.padding(22.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(article.title, Modifier.weight(1f), fontSize = 19.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Box {
                                    IconButton(onClick = { menu = true }, enabled = !active && !vm.busy, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.MoreHoriz, "文章操作") }
                                    DropdownMenu(menu, { menu = false }) {
                                        DropdownMenuItem(text = { Text("重命名") }, onClick = { menu = false; onRename(article) })
                                        DropdownMenuItem(text = { Text("移动到文件夹") }, onClick = { menu = false; onMove(article) })
                                        DropdownMenuItem(text = { Text("删除文章") }, onClick = { menu = false; onDelete(article) })
                                    }
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            Text(article.body.replace('\n', ' '), maxLines = 3, minLines = 3, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, lineHeight = 23.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(22.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Gesture, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(7.dp))
                                Text("${article.masks.size} 处挖空" + if (article.id in draftIds) " · 有草稿" else "", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (filtered.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 44.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.AutoStories, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(18.dp)); Text(if (search.isNotEmpty()) "没有匹配的标题" else "从一篇文章开始", fontSize = 19.sp)
                        if (articles.isEmpty()) TextButton(onClick = { vm.newArticle(example = true) }, enabled = vm.loaded && !vm.busy) { Text("用一篇示例开始") }
                    }
                }
            }
        }
        if (active) Text(hint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 20.dp).padding(bottom = 96.dp))
        drag.article?.let { article ->
            val previewWidth = minOf(300.dp, maxWidth - 32.dp)
            val widthPx = with(density) { previewWidth.toPx() }
            val x = (drag.pointer.x - rootBounds.left - widthPx / 2).coerceIn(0f, (rootBounds.width - widthPx).coerceAtLeast(0f))
            val y = (drag.pointer.y - rootBounds.top - with(density) { 120.dp.toPx() }).coerceIn(0f, (rootBounds.height - with(density) { 120.dp.toPx() }).coerceAtLeast(0f))
            Surface(Modifier.align(Alignment.TopStart).offset { IntOffset(x.roundToInt(), y.roundToInt()) }.width(previewWidth).testTag("dragPreview"),
                shape = RoundedCornerShape(20.dp), shadowElevation = 12.dp, tonalElevation = 6.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .45f))) {
                Column(Modifier.padding(18.dp)) {
                    Text(article.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(hint, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun FolderDropTarget(id: String, drag: LibraryDragState, hovered: Boolean, content: @Composable () -> Unit) {
    val scale by animateFloatAsState(if (hovered) 1.05f else 1f, tween(120), label = "folderHover")
    Box(Modifier.onGloballyPositioned { drag.targets[id] = it.boundsInRoot() }
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .semantics { stateDescription = if (hovered) "松手移入" else "文件夹" }.testTag("folder-$id")) { content() }
}
