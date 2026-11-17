package com.hh.recall.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hh.recall.AppModel
import com.hh.recall.AppPage
import com.hh.recall.BuildConfig
import com.hh.recall.ScreenMode
import com.hh.recall.core.Article
import com.hh.recall.core.Folder
import com.hh.recall.importing.ImportType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecallApp(vm: AppModel) {
    RecallTheme(vm.dark) {
        val library by vm.repository.library.collectAsStateWithLifecycle()
        val drafts by vm.repository.drafts.collectAsStateWithLifecycle()
        val visible by vm.reveal.revealed.collectAsStateWithLifecycle()
        val snackbar = remember { SnackbarHostState() }
        val grid = rememberLazyGridState()
        val categoryGrid = rememberLazyGridState()
        val folderGrid = androidx.compose.runtime.saveable.rememberSaveable(vm.currentFolder, saver = androidx.compose.foundation.lazy.grid.LazyGridState.Saver) { androidx.compose.foundation.lazy.grid.LazyGridState() }
        val context = LocalContext.current
        SideEffect {
            (context as? android.app.Activity)?.window?.let { window ->
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !vm.dark
                    isAppearanceLightNavigationBars = !vm.dark
                }
            }
        }
        var settings by remember { mutableStateOf(false) }
        var mainMenu by remember { mutableStateOf(false) }
        var importMenu by remember { mutableStateOf(false) }
        var pendingImportType by rememberSaveable { mutableStateOf(ImportType.MARKDOWN) }
        var titleRequired by remember { mutableStateOf(false) }
        var titleFocusRequest by remember { mutableIntStateOf(0) }
        var leaveDialog by remember { mutableStateOf(false) }
        var impactDialog by remember { mutableStateOf(false) }
        var rename by remember { mutableStateOf<Article?>(null) }
        var move by remember { mutableStateOf<Article?>(null) }
        var delete by remember { mutableStateOf<Article?>(null) }
        var folderForm by remember { mutableStateOf(false) }
        var editFolder by remember { mutableStateOf<Folder?>(null) }
        var folderDelete by remember { mutableStateOf<Folder?>(null) }
        var draftList by remember { mutableStateOf(false) }
        val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri != null) vm.execute { val output = context.contentResolver.openOutputStream(uri) ?: error("无法写入备份文件")
                output.use { vm.backup(it) } }
        }
        val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) vm.execute { val input = context.contentResolver.openInputStream(uri) ?: error("无法读取备份文件")
                input.use { vm.restore(it) } }
        }
        val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) vm.importer.start(uri, pendingImportType, vm.folderFilter?.takeUnless { it == "unfiled" })
        }
        fun finishEdit() {
            vm.finishComposition()
            if (vm.title.isBlank()) titleRequired = true
            else if (vm.mode == ScreenMode.BODY && vm.affectedCount > 0) impactDialog = true else vm.save()
        }
        fun back() {
            if (vm.busy) return
            if (vm.mode != ScreenMode.READ) {
                if (vm.dirty) leaveDialog = true else vm.discard()
            } else if (vm.page == AppPage.ARTICLE) vm.home() else vm.backLibrary()
        }
        LaunchedEffect(vm.message) {
            vm.message?.let { value -> vm.message = null; snackbar.showSnackbar(value) }
        }
        BackHandler(enabled = vm.page != AppPage.HOME) { back() }
        val home = vm.page == AppPage.HOME
        val articlePage = vm.page == AppPage.ARTICLE
        val categoryPage = vm.page == AppPage.CATEGORIES
        val folderPage = vm.page == AppPage.FOLDER
        val compactHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() } < 480.dp
        val compactKeyboard = compactHeight && WindowInsets.ime.getBottom(LocalDensity.current) > 0
        Scaffold(
            modifier = Modifier.imePadding(), containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                    title = {
                        if (home) Column {
                            Text("拾忆", fontWeight = FontWeight.SemiBold, fontSize = 25.sp)
                            Text("把知识，留给自己。", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else if (categoryPage && vm.categorySearching && compactHeight) CategorySearchField(vm, Modifier.fillMaxWidth())
                        else if (categoryPage) Text("分类", fontWeight = FontWeight.SemiBold, fontSize = 25.sp)
                        else if (folderPage) Text(library.folders.find { it.id == vm.currentFolder }?.name ?: "未分类", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 23.sp)
                        else Column {
                            Text(when (vm.mode) { ScreenMode.READ -> "专注背诵"; ScreenMode.MASK -> "编辑挖空"; ScreenMode.BODY -> if (vm.isNew) "新建文章" else "编辑正文" }, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                            Text("${vm.snapshot.text.codePointCount(0, vm.snapshot.text.length)} 字 · ${vm.snapshot.masks.size} 处挖空", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    navigationIcon = {
                        if (articlePage || folderPage) IconButton(onClick = ::back, enabled = !vm.busy, modifier = Modifier.testTag("back")) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
                        }
                    },
                    actions = {
                        if (categoryPage) CategoryActions(vm)
                        else if (articlePage && vm.mode != ScreenMode.READ) {
                            TextButton(onClick = ::finishEdit, enabled = !vm.busy, modifier = Modifier.testTag("save")) {
                                Icon(Icons.Outlined.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp))
                                Text(if (vm.mode == ScreenMode.BODY) "保存" else "完成")
                            }
                        } else {
                            if (home) ThemeToggle(vm.dark, vm::theme, enabled = !vm.busy)
                            if (home) Box {
                                IconButton(onClick = { importMenu = true }, enabled = !vm.busy,
                                    modifier = Modifier.testTag("importButton").semantics { contentDescription = "导入文件" }) {
                                    Icon(Icons.Outlined.Add, "导入文件")
                                }
                                DropdownMenu(importMenu, { importMenu = false }, shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)) {
                                    DropdownMenuItem(text = { Text("Markdown 文件") }, leadingIcon = { Icon(Icons.Outlined.Description, null) },
                                        onClick = {
                                            importMenu = false; pendingImportType = ImportType.MARKDOWN
                                            importPicker.launch(ImportType.MARKDOWN.mime)
                                        })
                                    DropdownMenuItem(text = { Text("PDF 文件") }, leadingIcon = { Icon(Icons.Outlined.PictureAsPdf, null) },
                                        onClick = { importMenu = false; pendingImportType = ImportType.PDF; importPicker.launch(ImportType.PDF.mime) })
                                    DropdownMenuItem(text = { Text("PNG图片") }, leadingIcon = { Icon(Icons.Outlined.Image, null) },
                                        onClick = { importMenu = false; pendingImportType = ImportType.PNG; importPicker.launch(ImportType.PNG.mime) })
                                }
                            }
                            Box {
                                IconButton(onClick = { mainMenu = true }, enabled = !vm.busy) { Icon(Icons.Outlined.MoreVert, "更多") }
                                DropdownMenu(mainMenu, { mainMenu = false }, shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp), containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .97f), tonalElevation = 0.dp, shadowElevation = 8.dp) {
                                    if (articlePage) vm.selected?.let { article ->
                                        DropdownMenuItem(text = { Text(article.title, maxLines = 2, overflow = TextOverflow.Ellipsis) }, onClick = {}, enabled = false)
                                        HorizontalDivider()
                                        DropdownMenuItem(text = { Text("重命名") }, onClick = { mainMenu = false; rename = article })
                                        DropdownMenuItem(text = { Text("移动到文件夹") }, onClick = { mainMenu = false; move = article })
                                        DropdownMenuItem(text = { Text("删除文章") }, onClick = { mainMenu = false; delete = article })
                                    }
                                    DropdownMenuItem(text = { Text("显示设置") }, onClick = { mainMenu = false; settings = true }, leadingIcon = { Icon(Icons.Outlined.Tune, null) })
                                    if (home) {
                                        if (drafts.isNotEmpty()) DropdownMenuItem(text = { Text("未完成草稿（${drafts.size}）") }, onClick = { mainMenu = false; draftList = true })
                                        DropdownMenuItem(text = { Text("导出完整备份") }, onClick = { mainMenu = false; backup.launch("拾忆备份-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.CHINA).format(Date())}.zip") })
                                        DropdownMenuItem(text = { Text("恢复备份（新增资料）") }, onClick = { mainMenu = false; restore.launch(arrayOf("application/zip", "application/octet-stream")) })
                                    }
                                }
                            }
                        }
                    },
                )
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    if (vm.busy || !vm.loaded) LinearProgressIndicator(Modifier.fillMaxWidth())
                    AnimatedContent(targetState = vm.page, modifier = Modifier.weight(1f), transitionSpec = {
                        (fadeIn(tween(200)) + slideInHorizontally(tween(200)) { if (targetState == AppPage.ARTICLE) 28 else -28 }) togetherWith fadeOut(tween(120))
                    }, label = "page") { page ->
                        when (page) {
                            AppPage.CATEGORIES -> CategoryScreen(vm, library.articles, library.folders, categoryGrid,
                                onRename = { editFolder = it; folderForm = true }, onDelete = { folderDelete = it })
                            AppPage.HOME, AppPage.FOLDER -> LibraryScreen(vm, library.articles, library.folders, drafts.map { it.articleId }.toSet(), if (page == AppPage.HOME) grid else folderGrid,
                                onOpen = { article ->
                                    val draft = drafts.find { it.articleId == article.id }
                                    if (draft != null) vm.pendingRecovery = draft else vm.open(article)
                                }, onRename = { rename = it }, onMove = { move = it }, onDelete = { delete = it },
                                onFolder = { editFolder = it; folderForm = true }, onFolderDelete = { folderDelete = it }, onDrop = vm::move,
                                folderContent = page == AppPage.FOLDER)
                            AppPage.ARTICLE -> vm.selected?.let { ArticleScreen(vm, visible, titleFocusRequest) }
                        }
                    }
                }
                if (!articlePage && !compactKeyboard) FloatingNavigation(!home, !vm.busy, vm::navigateTab,
                    Modifier.align(Alignment.BottomCenter).padding(horizontal = 20.dp).padding(bottom = 16.dp))
            }
        }

        if (vm.importer.visible) ImportScreen(vm.importer, vm.busy, vm::confirmImport)

        if (settings) AlertDialog(onDismissRequest = { settings = false }, title = { Text("阅读设置") }, text = {
            Column {
                Text("正文字号  ${vm.fontSize.toInt()}")
                Slider(value = vm.fontSize, onValueChange = vm::font, valueRange = 16f..36f, steps = 9)
                Spacer(Modifier.height(16.dp)); Text("资料仅保存在本机。卸载前请导出备份；覆盖安装会保留资料。", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("拾忆 ${BuildConfig.VERSION_NAME} · 离线手动挖空", fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
            }
        }, confirmButton = { TextButton(onClick = { settings = false }) { Text("完成") } })

        if (leaveDialog) AlertDialog(onDismissRequest = { leaveDialog = false }, title = { Text("保存这次编辑？") },
            text = { Text("保存后用于背诵；放弃修改会恢复原来的正文和挖空。") },
            confirmButton = { TextButton(onClick = { leaveDialog = false; finishEdit() }) { Text("保存") } },
            dismissButton = { Row { TextButton(onClick = { leaveDialog = false; vm.discard() }) { Text("放弃修改") }; TextButton(onClick = { leaveDialog = false }) { Text("继续编辑") } } })
        if (titleRequired) AlertDialog(onDismissRequest = { titleRequired = false }, title = { Text("文章标题还未命名") },
            text = { Text("请先填写文章标题，再保存这篇文章。") },
            confirmButton = { TextButton(onClick = { titleRequired = false; titleFocusRequest++ }) { Text("去命名") } })
        if (impactDialog) AlertDialog(onDismissRequest = { impactDialog = false }, title = { Text("检查受影响的挖空") },
            text = { Text("本次正文修改将取消 ${vm.affectedCount} 处挖空，其他挖空会保留。保存后可以重新标记这些内容。") },
            confirmButton = { TextButton(onClick = { impactDialog = false; vm.save() }) { Text("确认保存") } },
            dismissButton = { TextButton(onClick = { impactDialog = false }) { Text("返回检查") } })
        vm.pendingRecovery?.let { draft ->
            AlertDialog(onDismissRequest = { vm.pendingRecovery = null }, title = { Text("有一份未完成的草稿") },
                text = { Text("${draft.title.ifBlank { "未命名文章" }}\n恢复后可以继续编辑，已保存的原资料仍然保留。") },
                confirmButton = { TextButton(onClick = { vm.restoreDraft(draft) }) { Text("恢复草稿") } },
                dismissButton = { Row { TextButton(onClick = { vm.discardRecovery(draft) }) { Text("删除草稿") }; TextButton(onClick = { vm.pendingRecovery = null }) { Text("稍后") } } })
        }
        if (draftList) AlertDialog(onDismissRequest = { draftList = false }, title = { Text("未完成草稿") }, text = {
            LazyColumn {
                items(drafts, key = { it.articleId }) { draft ->
                    TextButton(onClick = { draftList = false; vm.pendingRecovery = draft }) {
                        Text(draft.title.ifBlank { "未命名文章" }, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { draftList = false }) { Text("关闭") } })
        rename?.let { article -> NameDialog("重命名文章", article.title, { rename = null }) { vm.rename(article, it); rename = null } }
        if (folderForm) NameDialog(if (editFolder == null) "新建文件夹" else "重命名文件夹", editFolder?.name ?: "", { folderForm = false }) { vm.folder(it, editFolder); folderForm = false }
        move?.let { article -> AlertDialog(onDismissRequest = { move = null }, title = { Text("移动到文件夹") }, text = {
            LazyColumn { item { TextButton(onClick = { vm.move(article, null); move = null }) { Text("未分类") } }
                items(library.folders, key = { it.id }) { folder -> TextButton(onClick = { vm.move(article, folder.id); move = null }) { Text(folder.name) } } }
        }, confirmButton = { TextButton(onClick = { move = null }) { Text("取消") } }) }
        delete?.let { article -> ConfirmDelete("删除「${article.title}」？", "正文和挖空将一起删除。", { delete = null }) { vm.delete(article); delete = null } }
        folderDelete?.let { folder -> ConfirmDelete("删除文件夹？", "「${folder.name}」中的文章会移到未分类，文章不会删除。", { folderDelete = null }) { vm.deleteFolder(folder); folderDelete = null } }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by remember(initial) { mutableStateOf(initial) }
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
                Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true,
                    label = { Text("名称") }, modifier = Modifier.fillMaxWidth().testTag("nameInput"))
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    TextButton(onClick = { onSave(value) }, enabled = value.isNotBlank()) { Text("保存") }
                }
            }
        }
    }
}

@Composable
private fun ConfirmDelete(title: String, body: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("删除", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
