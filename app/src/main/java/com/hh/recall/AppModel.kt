package com.hh.recall

import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hh.recall.core.*
import com.hh.recall.data.*
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ScreenMode { READ, MASK, BODY }
enum class AppPage { HOME, CATEGORIES, FOLDER, ARTICLE }

class AppModel @JvmOverloads constructor(application: Application, databaseName: String = "recall.db") : AndroidViewModel(application) {
    val repository = Repository(application, databaseName)
    val reveal = RevealController(viewModelScope)
    private val preferences = application.getSharedPreferences("settings", 0)
    var loaded by mutableStateOf(false); private set
    var selected by mutableStateOf<Article?>(null); private set
    var mode by mutableStateOf(ScreenMode.READ); private set
    var title by mutableStateOf(""); private set
    var snapshot by mutableStateOf(TextSnapshot("", emptyList())); private set
    var marking by mutableStateOf(true)
    var historyVersion by mutableIntStateOf(0); private set
    var message by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false); private set
    var query by mutableStateOf("")
    var folderFilter by mutableStateOf<String?>(null)
    var page by mutableStateOf(AppPage.HOME); private set
    private var articleOrigin = AppPage.HOME
    var categoryQuery by mutableStateOf("")
    var categorySearching by mutableStateOf(false)
    var folderQuery by mutableStateOf("")
    var currentFolder by mutableStateOf<String?>(null); private set
    var folderSort by mutableStateOf(runCatching { FolderSort.valueOf(preferences.getString("folderSort", "NAME")!!) }.getOrDefault(FolderSort.NAME)); private set
    var folderList by mutableStateOf(preferences.getBoolean("folderList", false)); private set
    var fontSize by mutableFloatStateOf(preferences.getFloat("fontSize", 22f)); private set
    var dark by mutableStateOf(preferences.getBoolean("dark", false)); private set
    var pendingRecovery by mutableStateOf<Draft?>(null)
    private var history = EditHistory(snapshot)
    private var pendingComposition: TextSnapshot? = null
    private var draftJob: Job? = null
    private var session = 0L
    private var readOffset = 0
    private var savedArticle: Article? = null
    val canUndo get() = history.canUndo
    val canRedo get() = history.canRedo
    val isNew get() = savedArticle == null
    val dirty get() = mode != ScreenMode.READ &&
        (savedArticle == null || title != savedArticle?.title || snapshot.text != savedArticle?.body || snapshot.masks != savedArticle?.masks)
    val affectedCount get() = savedArticle?.masks?.count { old -> snapshot.masks.none { it.id == old.id } } ?: 0

    init {
        execute {
            repository.load()
            pendingRecovery = repository.drafts.value.firstOrNull()
            loaded = true
        }
    }

    fun execute(action: suspend () -> Unit) {
        viewModelScope.launch { try { action() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { AppLog.failure("operation_failed", e); message = e.message ?: "操作失败，请重试"; loaded = true; busy = false } }
    }
    fun notify(value: String) { message = value }
    fun font(value: Float) { fontSize = value.coerceIn(16f, 36f); preferences.edit().putFloat("fontSize", fontSize).apply() }
    fun theme(value: Boolean) { dark = value; preferences.edit().putBoolean("dark", value).apply() }
    fun sortFolders(value: FolderSort) { folderSort = value; preferences.edit().putString("folderSort", value.name).apply() }
    fun folderView() { folderList = !folderList; preferences.edit().putBoolean("folderList", folderList).apply() }
    fun selectHomeFolder(id: String?) {
        folderFilter = id
        id?.takeUnless { it == "unfiled" }?.let { execute { repository.openFolder(it) } }
    }
    fun navigateTab(categories: Boolean) {
        if (busy || page == AppPage.ARTICLE) return
        page = if (categories) AppPage.CATEGORIES else AppPage.HOME
    }
    fun openFolder(id: String?) {
        if (busy) return
        if (id != currentFolder) folderQuery = ""
        currentFolder = id; page = AppPage.FOLDER
        id?.let { execute { repository.openFolder(it) } }
    }
    fun backLibrary() {
        if (busy) return
        when (page) {
            AppPage.FOLDER -> page = AppPage.CATEGORIES
            AppPage.CATEGORIES -> if (categorySearching) { categorySearching = false; categoryQuery = "" } else page = AppPage.HOME
            else -> Unit
        }
    }
    private fun returnToOrigin() {
        page = if (articleOrigin == AppPage.FOLDER && currentFolder != null && repository.library.value.folders.none { it.id == currentFolder }) AppPage.CATEGORIES else articleOrigin
    }

    fun open(article: Article) {
        if (mode != ScreenMode.READ || busy) return
        savePosition()
        reveal.reset(); session++
        selected = article; savedArticle = article
        title = article.title; snapshot = TextSnapshot(article.body, article.masks)
        readOffset = article.readingOffset
        if (page != AppPage.ARTICLE) articleOrigin = page
        page = AppPage.ARTICLE
        preferences.edit().putString("lastArticle", article.id).apply()
    }

    fun home() {
        if (mode != ScreenMode.READ || busy) return
        savePosition(); reveal.reset(); session++
        returnToOrigin()
    }

    fun newArticle(example: Boolean = false) {
        if (busy || mode != ScreenMode.READ) return
        savePosition(); reveal.reset(); session++
        val text = if (example) "把知识留给自己\n\n手动挖空，是一种主动回忆的练习。先读懂文章，再把关键词、重要句子划成空白，用自己的记忆补全它。\n\n点击底部「编辑挖空」，进入标记状态。用手指直接划过文字；双击红字，可以取消该处挖空。切换到「浏览」后，就能上下滚动长文章。\n\n背诵时，点击空白查看答案，约五秒后自动隐藏。点击「全部显示」可以核对原文，答案会保持显示。\n\n你也可以编辑正文。没有被修改触及的挖空会跟随文字移动；受影响的挖空会在保存前提醒。\n\nPractice makes progress. 慢慢来，每次记住一点。" else ""
        articleOrigin = page
        val destination = if (page == AppPage.FOLDER) currentFolder else folderFilter?.takeUnless { it == "unfiled" }
        selected = Article(title = if (example) "从这里开始" else "", body = text, folderId = destination)
        savedArticle = null; title = selected!!.title
        snapshot = TextSnapshot(text, emptyList()); history = EditHistory(snapshot)
        readOffset = 0; mode = ScreenMode.BODY; pendingComposition = null
        page = AppPage.ARTICLE
        persistDraft()
    }

    fun edit(next: ScreenMode) {
        if (busy || selected == null || mode != ScreenMode.READ) return
        reveal.reset(); savePosition(); session++
        savedArticle = selected
        title = selected!!.title; snapshot = TextSnapshot(selected!!.body, selected!!.masks)
        history = EditHistory(snapshot); pendingComposition = null
        mode = next; marking = true; historyVersion++
    }

    fun changeTitle(value: String) { title = value.take(500); persistDraft() }

    /** Called with the actual Editable replacement range, not a search/diff across repeated sentences. */
    fun replaceBody(start: Int, before: Int, inserted: String, composing: Boolean) {
        if (mode != ScreenMode.BODY) return
        val current = pendingComposition ?: history.current
        if (start < 0 || start + before > current.text.length) return
        var next = MaskRules.replace(current, start, start + before, inserted)
        val original = savedArticle
        if (original != null && next.text == original.body) next = TextSnapshot(next.text, original.masks)
        snapshot = next
        if (composing) pendingComposition = next
        else { history.push(next); pendingComposition = null; historyVersion++ }
        persistDraft()
    }

    fun finishComposition() {
        pendingComposition?.let { history.push(it); snapshot = it; historyVersion++ }
        pendingComposition = null
    }

    fun mark(start: Int, end: Int) {
        if (busy || mode != ScreenMode.MASK || !marking) return
        updateMasks(MaskRules.add(snapshot.text, snapshot.masks, start, end))
    }
    fun unmark(offset: Int) {
        if (busy || mode != ScreenMode.MASK || !marking) return
        val hit = snapshot.masks.firstOrNull { offset in it.start until it.end } ?: return
        updateMasks(snapshot.masks.filterNot { it.id == hit.id })
    }
    private fun updateMasks(masks: List<Mask>) {
        history.push(snapshot.copy(masks = masks)); snapshot = history.current; historyVersion++; persistDraft()
    }
    fun undo() { finishComposition(); snapshot = history.undo(); historyVersion++; persistDraft() }
    fun redo() { finishComposition(); snapshot = history.redo(); historyVersion++; persistDraft() }

    private fun currentDraft(): Draft? = selected?.let {
        if (mode == ScreenMode.READ) null else Draft(it.id, title, snapshot, mode.name,
            savedArticle?.body ?: "", savedArticle?.masks ?: emptyList(), it.folderId)
    }
    private fun persistDraft() {
        draftJob?.cancel()
        val draft = currentDraft() ?: return
        val token = session
        draftJob = viewModelScope.launch {
            delay(250)
            if (token == session) {
                repository.draft(draft)
                AppLog.event("draft_saved", "mode=${draft.mode}")
            } else AppLog.event("draft_skipped_stale_session")
        }
    }
    fun background() {
        reveal.reset(); savePosition()
        draftJob?.cancel()
        currentDraft()?.let { draft -> viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { repository.draft(draft) } }
    }

    fun save() {
        if (busy) return
        finishComposition()
        val current = selected ?: return
        if (snapshot.text.isBlank()) { notify("正文不能为空"); return }
        if (title.isBlank()) { notify("请填写文章标题"); return }
        val creating = isNew
        val article = current.copy(title = title.trim(), body = snapshot.text, masks = snapshot.masks,
            updatedAt = System.currentTimeMillis(), readingOffset = readOffset.coerceIn(0, snapshot.text.length))
        busy = true; draftJob?.cancel(); session++
        AppLog.event("save_started", "creating=$creating masks=${article.masks.size}")
        execute {
            repository.save(article)
            selected = article; savedArticle = article; mode = ScreenMode.READ; busy = false
            reveal.reset()
            preferences.edit().putString("lastArticle", article.id).apply()
            if (creating) edit(ScreenMode.MASK)
            AppLog.event("save_completed", "creating=$creating masks=${article.masks.size}")
            notify(if (creating) "文章已保存，划过文字开始挖空" else "已保存")
        }
    }

    fun discard() {
        val id = selected?.id ?: return
        draftJob?.cancel(); session++; reveal.reset()
        execute {
            repository.discardDraft(id)
            mode = ScreenMode.READ
            val original = savedArticle
            selected = original
            snapshot = original?.let { TextSnapshot(it.body, it.masks) } ?: TextSnapshot("", emptyList())
            title = original?.title ?: ""
            pendingComposition = null
            if (original == null) returnToOrigin()
        }
    }

    fun restoreDraft(draft: Draft) {
        if (page != AppPage.ARTICLE) articleOrigin = page
        reveal.reset(); session++
        val original = repository.library.value.articles.find { it.id == draft.articleId }
        savedArticle = original
        selected = original ?: Article(id = draft.articleId, title = draft.title, body = draft.originalBody,
            folderId = draft.folderId?.takeIf { id -> repository.library.value.folders.any { it.id == id } })
        title = draft.title; snapshot = draft.snapshot; history = EditHistory(snapshot)
        pendingComposition = null; mode = if (draft.mode == "MASK") ScreenMode.MASK else ScreenMode.BODY
        marking = true; historyVersion++; pendingRecovery = null
        page = AppPage.ARTICLE
        AppLog.event("draft_restored", "mode=${draft.mode}")
    }
    fun discardRecovery(draft: Draft) { execute { repository.discardDraft(draft.articleId); pendingRecovery = null } }

    fun reading(offset: Int) { if (page == AppPage.ARTICLE && mode == ScreenMode.READ) readOffset = offset.coerceIn(0, snapshot.text.length) }
    fun readingStart() = readOffset
    private fun savePosition() {
        val article = selected ?: return
        if (savedArticle == null || page != AppPage.ARTICLE) return
        val offset = readOffset.coerceIn(0, article.body.length)
        execute { repository.position(article.id, offset) }
    }

    fun rename(article: Article, value: String) {
        if (value.isBlank()) return
        execute { val next = article.copy(title = value.trim().take(500), updatedAt = System.currentTimeMillis())
            repository.save(next, clearDraft = false); if (selected?.id == next.id) { selected = next; savedArticle = next; title = next.title } }
    }
    fun move(article: Article, folder: String?) {
        if (busy) return
        val current = repository.library.value.articles.find { it.id == article.id } ?: return
        val destination = folder?.let { id -> repository.library.value.folders.find { it.id == id } ?: return }
        if (current.folderId == folder) return
        execute {
            val next = current.copy(folderId = folder, updatedAt = System.currentTimeMillis())
            repository.save(next, clearDraft = false)
            if (selected?.id == next.id) { selected = next; savedArticle = next }
            notify("已移入「${destination?.name ?: "未分类"}」")
        }
    }
    fun delete(article: Article) { execute {
        repository.deleteArticle(article.id)
        if (selected?.id == article.id) { reveal.reset(); selected = null; savedArticle = null; if (page == AppPage.ARTICLE) returnToOrigin() }
    } }
    fun folder(name: String, existing: Folder? = null) {
        if (name.isBlank()) return
        execute { repository.saveFolder(existing?.copy(name = name.trim().take(200)) ?: Folder(name = name.trim().take(200))) }
    }
    fun deleteFolder(folder: Folder) { execute {
        repository.deleteFolder(folder.id); if (folderFilter == folder.id) folderFilter = null
        if (currentFolder == folder.id) { currentFolder = null; folderQuery = ""; if (page == AppPage.FOLDER) page = AppPage.CATEGORIES }
        selected?.takeIf { it.folderId == folder.id }?.let { selected = it.copy(folderId = null); savedArticle = selected }
    } }

    suspend fun backup(output: java.io.OutputStream) {
        busy = true
        try { val value = repository.snapshot(); withContext(Dispatchers.IO) { BackupCodec.write(value, output) }; notify("备份已导出") }
        finally { busy = false }
    }
    suspend fun restore(input: java.io.InputStream) {
        busy = true
        try {
            val value = withContext(Dispatchers.IO) { BackupCodec.read(input) }
            val name = "恢复 " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date())
            val imported = BackupCodec.asNewCollection(value, name)
            repository.importCollection(imported)
            AppLog.event("backup_restored", "articles=${value.articles.size} folders=${value.folders.size}")
            notify("已恢复 ${value.articles.size} 篇文章，原资料未覆盖")
        } finally { busy = false }
    }
}
