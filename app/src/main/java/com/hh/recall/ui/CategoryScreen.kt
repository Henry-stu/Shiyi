package com.hh.recall.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.sp
import com.hh.recall.AppModel
import com.hh.recall.core.*

@Composable
fun CategoryScreen(vm: AppModel, articles: List<Article>, folders: List<Folder>, grid: LazyGridState,
    onRename: (Folder) -> Unit, onDelete: (Folder) -> Unit) {
    val counts = remember(articles) { articles.groupingBy { it.folderId }.eachCount() }
    val entries = listOf(Folder(id = "unfiled", name = "未分类")) + sortedFolders(folders, vm.folderSort)
    val visible = entries.filter { it.name.contains(vm.categoryQuery, ignoreCase = true) }
    Box(Modifier.fillMaxSize().testTag("categoryPage"), contentAlignment = Alignment.TopCenter) {
        BoxWithConstraints(Modifier.widthIn(max = 1120.dp).fillMaxSize().padding(horizontal = 20.dp)) {
            val columns = when { maxWidth >= 800.dp -> 4; maxWidth >= 560.dp -> 3; else -> 2 }
            Column(Modifier.fillMaxSize()) {
            val compact = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() } < 480.dp
            if (vm.categorySearching && !compact) {
                CategorySearchField(vm, Modifier.fillMaxWidth().padding(top = 12.dp))
            }
            Spacer(Modifier.height(if (compact) 8.dp else 20.dp))
            LazyVerticalGrid(columns = GridCells.Fixed(if (vm.folderList) 1 else columns), state = grid,
                modifier = Modifier.weight(1f).testTag("categoryGrid"), contentPadding = PaddingValues(bottom = 112.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(visible, key = { it.id }) { folder ->
                    var menu by remember(folder.id) { mutableStateOf(false) }
                    Box {
                        val colors = MaterialTheme.colorScheme
                        val dark = colors.background.luminance() < .2f
                        val accent = if (dark) Color(0xFF94C9AA) else colors.primary
                        val cardColor = if (dark) lerp(colors.surface, colors.surfaceContainer, .7f) else colors.surface
                        Surface(shape = RoundedCornerShape(22.dp), color = cardColor,
                            tonalElevation = 0.dp, shadowElevation = 3.dp,
                            border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = if (dark) .95f else .8f)),
                            modifier = Modifier.fillMaxWidth().testTag("category-${folder.id}").combinedClickable(
                                enabled = !vm.busy,
                                onClick = { vm.openFolder(folder.id.takeUnless { it == "unfiled" }) },
                                onLongClick = if (folder.id == "unfiled") null else ({ menu = true }))) {
                            val count = counts[folder.id.takeUnless { it == "unfiled" }] ?: 0
                            if (vm.folderList) Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                FolderArtwork(accent, dark, Modifier.size(44.dp))
                                Spacer(Modifier.width(12.dp))
                                FolderName(folder, Modifier.weight(1f))
                                Spacer(Modifier.width(10.dp))
                                FolderCount(folder.id, count)
                                RenameFolderButton(folder, accent, !vm.busy, onRename)
                            } else Box(Modifier.fillMaxWidth().height(188.dp)) {
                                Column(Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, top = 28.dp, bottom = 12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally) {
                                    FolderArtwork(accent, dark, Modifier.size(88.dp).testTag("folderArtwork-${folder.id}"))
                                    Spacer(Modifier.height(8.dp))
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        FolderName(folder, Modifier.weight(1f), centered = true)
                                        Spacer(Modifier.width(6.dp))
                                        FolderCount(folder.id, count)
                                    }
                                }
                                Box(Modifier.align(Alignment.TopEnd)) { RenameFolderButton(folder, accent, !vm.busy, onRename) }
                            }
                        }
                        DropdownMenu(menu, { menu = false }, shape = RoundedCornerShape(18.dp)) {
                            DropdownMenuItem(text = { Text("重命名") }, onClick = { menu = false; onRename(folder) })
                            DropdownMenuItem(text = { Text("删除文件夹") }, onClick = { menu = false; onDelete(folder) })
                        }
                    }
                }
                if (visible.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                    Text("没有匹配的文件夹", Modifier.fillMaxWidth().padding(vertical = 48.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            }
        }
    }
}

@Composable
fun CategorySearchField(vm: AppModel, modifier: Modifier = Modifier) {
    OutlinedTextField(vm.categoryQuery, { vm.categoryQuery = it }, singleLine = true,
        placeholder = { Text("搜索文件夹") }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
        trailingIcon = { if (vm.categoryQuery.isNotEmpty()) IconButton(onClick = { vm.categoryQuery = "" }) { Icon(Icons.Outlined.Close, "清除搜索") } },
        shape = RoundedCornerShape(18.dp), modifier = modifier.testTag("folderSearch"))
}

@Composable
private fun FolderName(folder: Folder, modifier: Modifier = Modifier, centered: Boolean = false) {
    Text(folder.name, modifier.testTag("folderName-${folder.id}"), maxLines = 2, minLines = 2,
        overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 23.sp,
        textAlign = if (centered) TextAlign.Center else TextAlign.Start)
}

@Composable
private fun FolderCount(id: String, count: Int) {
    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.testTag("folderCount-$id")) {
        Text("$count 篇", Modifier.padding(horizontal = 6.dp, vertical = 6.dp), maxLines = 1,
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RenameFolderButton(folder: Folder, accent: Color, enabled: Boolean, onRename: (Folder) -> Unit) {
    if (folder.id == "unfiled") {
        Spacer(Modifier.size(48.dp))
    } else IconButton(onClick = { onRename(folder) }, enabled = enabled,
        modifier = Modifier.size(48.dp).testTag("renameFolder-${folder.id}")
            .semantics { contentDescription = "重命名文件夹：${folder.name}" }) {
        Canvas(Modifier.size(20.dp)) {
            val tint = accent.copy(alpha = if (enabled) 1f else .38f)
            drawCircle(tint, radius = 2.dp.toPx(), center = Offset(center.x, center.y - 4.dp.toPx()))
            drawCircle(tint, radius = 2.dp.toPx(), center = Offset(center.x, center.y + 4.dp.toPx()))
        }
    }
}

@Composable
private fun FolderArtwork(accent: Color, dark: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val unit = size.width / 56f
        fun p(value: Float) = value * unit
        val back = Path().apply {
            moveTo(p(5f), p(13f)); quadraticTo(p(5f), p(8f), p(10f), p(8f))
            lineTo(p(22f), p(8f)); lineTo(p(28f), p(15f)); lineTo(p(46f), p(15f))
            quadraticTo(p(51f), p(15f), p(51f), p(20f)); lineTo(p(51f), p(44f))
            quadraticTo(p(51f), p(48f), p(46f), p(48f)); lineTo(p(10f), p(48f))
            quadraticTo(p(5f), p(48f), p(5f), p(43f)); close()
        }
        drawPath(back, accent.copy(alpha = if (dark) .5f else .7f))
        drawRoundRect(if (dark) Color(0xFFC9D7CE) else Color.White,
            topLeft = Offset(p(10f), p(20f)), size = Size(p(36f), p(22f)), cornerRadius = CornerRadius(p(3f)))
        val front = Path().apply {
            moveTo(p(3f), p(28f)); quadraticTo(p(2f), p(23f), p(7f), p(23f)); lineTo(p(49f), p(23f))
            quadraticTo(p(54f), p(23f), p(53f), p(28f)); lineTo(p(50f), p(45f))
            quadraticTo(p(49f), p(49f), p(45f), p(49f)); lineTo(p(11f), p(49f))
            quadraticTo(p(7f), p(49f), p(6f), p(45f)); close()
        }
        drawPath(front, if (dark) Color(0xFF6B9E83) else Color(0xFFA8CCB6))
        drawLine(accent.copy(alpha = .35f), Offset(p(14f), p(32f)), Offset(p(29f), p(32f)), p(2f))
    }
}

@Composable
fun CategoryActions(vm: AppModel) {
    var sorting by remember { mutableStateOf(false) }
    IconButton(onClick = { vm.categorySearching = !vm.categorySearching; if (!vm.categorySearching) vm.categoryQuery = "" }, modifier = Modifier.testTag("categorySearchToggle")) {
        Icon(Icons.Outlined.Search, "搜索文件夹")
    }
    Box {
        IconButton(onClick = { sorting = true }, modifier = Modifier.testTag("categorySort")) { Icon(Icons.AutoMirrored.Outlined.Sort, "排序方式") }
        DropdownMenu(sorting, { sorting = false }, shape = RoundedCornerShape(18.dp)) {
            listOf(FolderSort.NAME to "文件名排序", FolderSort.OPENED to "打开时间排序", FolderSort.MODIFIED to "修改时间排序").forEach { (sort, label) ->
                DropdownMenuItem(text = { Text(label) }, trailingIcon = { if (vm.folderSort == sort) Icon(Icons.Outlined.Check, null) },
                    onClick = { vm.sortFolders(sort); sorting = false })
            }
        }
    }
    IconButton(onClick = vm::folderView, modifier = Modifier.testTag("categoryView").semantics { stateDescription = if (vm.folderList) "列表" else "图标" }) {
        Icon(if (vm.folderList) Icons.Outlined.GridView else Icons.AutoMirrored.Outlined.ViewList, if (vm.folderList) "切换为图标" else "切换为列表")
    }
}

@Composable
fun FloatingNavigation(categories: Boolean, enabled: Boolean, onSelect: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.widthIn(max = 320.dp).fillMaxWidth().height(64.dp).testTag("bottomNavigation"),
        shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .94f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f)), shadowElevation = 8.dp) {
        BoxWithConstraints(Modifier.padding(6.dp)) {
            val width = maxWidth / 2
            val x by animateDpAsState(if (categories) width else 0.dp, tween(180), label = "navSelection")
            val density = LocalDensity.current
            Surface(Modifier.offset { IntOffset(with(density) { x.roundToPx() }, 0) }.width(width).fillMaxHeight(), shape = RoundedCornerShape(19.dp), color = MaterialTheme.colorScheme.primaryContainer) {}
            Row(Modifier.fillMaxSize()) {
                listOf(false to "拾忆", true to "分类").forEach { (category, label) ->
                    TextButton(onClick = { onSelect(category) }, enabled = enabled,
                        modifier = Modifier.weight(1f).fillMaxHeight().testTag(if (category) "navCategories" else "navHome")
                            .semantics { selected = categories == category }, shape = RoundedCornerShape(19.dp)) {
                        val color = if (category == categories) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                        Icon(if (category) Icons.Outlined.FolderOpen else Icons.Outlined.AutoStories, null, Modifier.size(21.dp), tint = color)
                        Spacer(Modifier.width(9.dp)); Text(label, color = color, fontWeight = if (category == categories) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
        }
    }
}
