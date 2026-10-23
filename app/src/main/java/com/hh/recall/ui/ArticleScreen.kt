package com.hh.recall.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.hh.recall.AppModel
import com.hh.recall.ScreenMode
import kotlinx.coroutines.delay

@Composable
fun ArticleScreen(vm: AppModel, visible: Set<String>, onSave: () -> Unit) {
    val article = vm.selected ?: return
    var expanded by rememberSaveable(article.id) { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    var hintVersion by remember { mutableIntStateOf(0) }
    fun feedback(text: String) { hint = text; hintVersion++ }
    LaunchedEffect(hintVersion) { if (hint != null) { delay(1100); hint = null } }
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 12.dp).testTag("articlePage")) {
        if (vm.mode == ScreenMode.BODY) {
            OutlinedTextField(value = vm.title, onValueChange = vm::changeTitle, label = { Text("文章标题") },
                singleLine = true, enabled = !vm.busy, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp).testTag("title"))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Row(Modifier.fillMaxSize()) {
                Surface(Modifier.weight(1f).fillMaxHeight(), shape = RoundedCornerShape(22.dp), color = colors.surface,
                    border = BorderStroke(1.dp, if (vm.mode == ScreenMode.MASK && vm.marking) markingColor(vm.dark).copy(alpha = .45f) else colors.outlineVariant.copy(alpha = .55f))) {
                    if (vm.mode == ScreenMode.BODY) {
                        key(article.id) {
                            AndroidView(factory = { BodyEditor(it).apply { onReplace = vm::replaceBody; onUndo = vm::undo; onRedo = vm::redo; tag = "bodyEditor" } },
                                update = { it.bind(vm.snapshot.text, vm.fontSize, colors.onSurface.toArgb(), colors.onSurfaceVariant.toArgb()); it.isEnabled = !vm.busy },
                                modifier = Modifier.fillMaxSize().testTag("bodyEditor"))
                        }
                    } else {
                        key(article.id, vm.mode) {
                            AndroidView(factory = { ReaderView(it).apply {
                                initialOffset = vm.readingStart(); onReading = vm::reading
                                textView.onMark = vm::mark; textView.onRemove = vm::unmark; textView.onReveal = vm.reveal::toggle
                            } }, update = { it.bind(vm.snapshot.text, vm.snapshot.masks, visible, vm.mode, vm.marking, vm.fontSize,
                                colors.onSurface.toArgb(), colors.surface.toArgb(), colors.onSurfaceVariant.toArgb(), answerColor(vm.dark).toArgb(), markingColor(vm.dark).toArgb()) },
                                modifier = Modifier.fillMaxSize().testTag("reader"))
                        }
                    }
                }
                if (vm.mode == ScreenMode.READ) {
                    Box(Modifier.width(68.dp).fillMaxHeight(), contentAlignment = Alignment.CenterEnd) {
                        GlassPanel {
                            Column(Modifier.verticalScroll(rememberScrollState()).padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                ToolIcon(if (expanded) Icons.Outlined.Close else Icons.Outlined.Tune, if (expanded) "收起工具栏" else "展开工具栏", "toolbarToggle", enabled = !vm.busy) { expanded = !expanded }
                                AnimatedVisibility(expanded, enter = expandVertically(tween(180), expandFrom = Alignment.Top) + fadeIn(tween(180)),
                                    exit = shrinkVertically(tween(180), shrinkTowards = Alignment.Top) + fadeOut(tween(100))) {
                                    Column {
                                        HorizontalDivider(Modifier.width(32.dp).align(Alignment.CenterHorizontally).padding(vertical = 4.dp))
                                        ToolIcon(Icons.Outlined.Gesture, "编辑挖空", "editMasks", enabled = !vm.busy) { feedback("已进入挖空编辑"); vm.edit(ScreenMode.MASK) }
                                        ToolIcon(Icons.Outlined.EditNote, "编辑正文", "editBody", enabled = !vm.busy) { feedback("已进入正文编辑"); vm.edit(ScreenMode.BODY) }
                                        val all = vm.snapshot.masks.isNotEmpty() && vm.snapshot.masks.all { it.id in visible }
                                        ToolIcon(if (all) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (all) "全部遮住" else "全部显示", "toggleAll", enabled = !vm.busy && vm.snapshot.masks.isNotEmpty(), selected = all) {
                                            vm.reveal.toggleAll(vm.snapshot.masks.map { it.id }.toSet()); feedback(if (all) "答案已全部遮住" else "答案已全部显示")
                                        }
                                        HorizontalDivider(Modifier.width(32.dp).align(Alignment.CenterHorizontally).padding(vertical = 4.dp))
                                        ToolIcon(Icons.Outlined.TextDecrease, "缩小字号", "fontDown", enabled = !vm.busy && vm.fontSize > 16f) { vm.font(vm.fontSize - 2); feedback("字号 ${vm.fontSize.toInt()}") }
                                        ToolIcon(Icons.Outlined.TextIncrease, "放大字号", "fontUp", enabled = !vm.busy && vm.fontSize < 36f) { vm.font(vm.fontSize + 2); feedback("字号 ${vm.fontSize.toInt()}") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            androidx.compose.animation.AnimatedVisibility(hint != null, modifier = Modifier.align(Alignment.CenterEnd).padding(end = if (vm.mode == ScreenMode.READ) 76.dp else 12.dp),
                enter = fadeIn(tween(100)) + slideInHorizontally(tween(140)) { 10 }, exit = fadeOut(tween(150))) {
                Surface(shape = RoundedCornerShape(12.dp), color = colors.surfaceContainer, shadowElevation = 3.dp) {
                    Text(hint.orEmpty(), modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), fontSize = 13.sp)
                }
            }
        }
        if (vm.mode != ScreenMode.READ) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                if (vm.mode == ScreenMode.MASK) {
                    MarkBrowseSwitch(vm)
                    Spacer(Modifier.width(8.dp))
                }
                val version = vm.historyVersion
                IconButton(onClick = vm::undo, enabled = vm.canUndo && !vm.busy, modifier = Modifier.testTag("undo-$version")) { Icon(Icons.AutoMirrored.Outlined.Undo, "撤销") }
                IconButton(onClick = vm::redo, enabled = vm.canRedo && !vm.busy) { Icon(Icons.AutoMirrored.Outlined.Redo, "重做") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onSave, enabled = !vm.busy) { Icon(Icons.Outlined.Check, null, Modifier.size(17.dp)); Spacer(Modifier.width(4.dp)); Text(if (vm.mode == ScreenMode.MASK) "完成" else "保存") }
            }
        }
        Text(when (vm.mode) {
            ScreenMode.READ -> "点击空白查看答案 · 5 秒后自动隐藏"
            ScreenMode.MASK -> if (vm.marking) "划过文字添加挖空 · 双击红字取消 · 边缘自动滚动" else "上下滑动浏览 · 切回标记继续挖空"
            ScreenMode.BODY -> if (vm.affectedCount > 0) "本次修改将取消 ${vm.affectedCount} 处挖空，保存前可检查" else "未受影响的挖空会保留 · 草稿自动保留"
        }, fontSize = 11.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp, top = 12.dp, bottom = 8.dp))
    }
}

/** Only the decorative background is blurred; content and touch targets remain crisp. */
@Composable
private fun GlassPanel(content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(28.dp)
    Box(Modifier.shadow(6.dp, shape).clip(shape)) {
        Box(Modifier.matchParentSize().blur(12.dp).background(Brush.verticalGradient(listOf(colors.primaryContainer.copy(alpha = .5f), colors.surfaceContainerLow))))
        Surface(shape = shape, color = colors.surface.copy(alpha = .88f), border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = .5f)), content = content)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolIcon(icon: ImageVector, label: String, tag: String, enabled: Boolean = true, selected: Boolean = false, onClick: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .92f else 1f, tween(90), label = "toolPress")
    TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Left), tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) {
        IconButton(onClick = onClick, enabled = enabled, interactionSource = interactions,
            modifier = Modifier.size(48.dp).graphicsLayer { scaleX = scale; scaleY = scale }.testTag(tag),
            colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)) {
            Icon(icon, label, Modifier.size(23.dp))
        }
    }
}

@Composable
private fun MarkBrowseSwitch(vm: AppModel) {
    val fraction by animateFloatAsState(if (vm.marking) 0f else 1f, tween(180), label = "markBrowse")
    Box(Modifier.width(188.dp).height(48.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainer)) {
        Box(Modifier.offset { IntOffset((94.dp * fraction).roundToPx(), 0) }.padding(3.dp).width(94.dp).fillMaxHeight().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.primaryContainer))
        Row {
            TextButton(onClick = { vm.marking = true }, enabled = !vm.busy, modifier = Modifier.width(94.dp).fillMaxHeight().testTag("markMode")) { Text("标记", color = MaterialTheme.colorScheme.onSurface) }
            TextButton(onClick = { vm.marking = false }, enabled = !vm.busy, modifier = Modifier.width(94.dp).fillMaxHeight().testTag("browseMode")) { Text("浏览", color = MaterialTheme.colorScheme.onSurface) }
        }
    }
}
