package com.hh.recall.importing

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.*
import com.hh.recall.AppLog
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

enum class ImportType(val label: String, val family: String, val mime: Array<String>) {
    MARKDOWN("Markdown 文件", "markdown", arrayOf("text/markdown", "text/x-markdown", "text/plain", "application/octet-stream")),
    PDF("PDF 文件", "pdf", arrayOf("application/pdf")),
    PNG("PNG图片", "png", arrayOf("image/png"))
}

data class ImportPage(val number: Int, val body: String, val bitmap: Bitmap?, val scanned: Boolean,
    val blank: Boolean, val hints: List<String>)
data class ImportPreview(val pages: List<ImportPage>, val needsReview: Boolean)

/** A ViewModel-owned, single import session. Database writes happen only in AppModel.confirmImport. */
class DocumentImport(private val app: Context, private val scope: CoroutineScope) {
    companion object {
        // Also serialize native work across a destroyed and newly created ViewModel.
        private val engineGate = Mutex()
        const val MAX_BODY = 1_000_000
    }
    var visible by mutableStateOf(false); private set
    var running by mutableStateOf(false); private set
    var cancelling by mutableStateOf(false); private set
    var status by mutableStateOf(""); private set
    var errorCode by mutableStateOf<String?>(null); private set
    var preview by mutableStateOf<ImportPreview?>(null); private set
    var title by mutableStateOf(""); private set
    var body by mutableStateOf(""); private set
    var checked by mutableStateOf(false)
    var uri: Uri? = null; private set
    var type by mutableStateOf(ImportType.MARKDOWN); private set
    var folderId: String? = null; private set
    private var cancel = AtomicBoolean()
    @Volatile private var closed = false

    fun title(value: String) { title = value.take(500) }
    fun body(value: String) { if (value.length <= MAX_BODY) body = value }

    fun start(source: Uri, kind: ImportType, destination: String? = null, legacy: Boolean = false) {
        if (running || closed) return
        uri = source; type = kind; folderId = destination
        visible = true; running = true; cancelling = false; preview = null
        title = ""; body = ""; checked = false; errorCode = null; status = "正在读取${kind.label}…"
        val token = AtomicBoolean(); cancel = token
        scope.launch(Dispatchers.IO) {
            var result: OcrImporter.Result? = null
            var output: ImportPreview? = null
            var text = ""; var name = ""; var failure: String? = null
            try {
                engineGate.withLock {
                    if (token.get()) throw TextImporter.Failure("CANCELLED")
                    name = displayName(source)
                    app.contentResolver.openInputStream(source)?.use { input ->
                        if (kind == ImportType.MARKDOWN) {
                            text = TextImporter.read(app, input, kind.family, if (legacy) "GB18030" else null, token) { _, _ -> }.body
                            output = ImportPreview(emptyList(), false)
                        } else {
                            OcrImporter(app, kind == ImportType.PNG).use { engine ->
                                result = engine.read(input, kind.family, token) { stage, completed, total ->
                                    scope.launch(Dispatchers.Main.immediate) {
                                        if (cancel === token && running && !cancelling && !closed) status = when {
                                            stage.startsWith("方向检查") || stage.startsWith("长图分片") || stage == "识别扫描页" -> "正在识别第 ${completed + 1} 页…"
                                            total > 0 -> "正在读取 · $completed / $total 页"
                                            else -> "正在识别文字…"
                                        }
                                    }
                                }
                            }
                            text = result!!.body
                            val pages = (0 until result!!.pages.length()).map { i ->
                                val page = result!!.pages.getJSONObject(i)
                                val hints = mutableListOf<String>()
                                page.optJSONArray("warnings")?.let { warnings ->
                                    for (j in 0 until warnings.length()) warning(warnings.optString(j))?.let(hints::add)
                                }
                                page.optJSONArray("lines")?.let { lines ->
                                    if ((0 until lines.length()).any { lines.optJSONObject(it)?.optDouble("confidence", 1.0)?.let { n -> n < .8 } == true })
                                        hints += "部分文字识别把握较低，请逐字检查。"
                                }
                                val scanned = page.optString("route") == "ocr"
                                val value = page.optString("body")
                                if (scanned && !page.optBoolean("blank") && value.codePoints().filter { Character.isLetterOrDigit(it) }.count() <= 2)
                                    hints += "疑似图形或极短文本，请确认这些文字确实来自原图。"
                                ImportPage(i + 1, value, result!!.previews.getOrNull(i), scanned, page.optBoolean("blank"), hints.distinct())
                            }
                            output = ImportPreview(pages, pages.any { it.scanned || it.hints.isNotEmpty() || it.blank })
                        }
                    } ?: throw TextImporter.Failure("READ_FAILED")
                    if (text.length > MAX_BODY) throw TextImporter.Failure("TEXT_LIMIT")
                    if (text.isBlank()) throw TextImporter.Failure("EMPTY_DOCUMENT")
                    if (token.get()) throw TextImporter.Failure("CANCELLED")
                }
            } catch (e: TextImporter.Failure) { failure = e.code }
            catch (e: CancellationException) { token.set(true); failure = "CANCELLED" }
            catch (e: OutOfMemoryError) { failure = "MEMORY_LIMIT" }
            catch (e: Exception) { failure = "READ_FAILED"; AppLog.failure("document_read_failed", e) }
            finally {
                // Dispatch cancellation can otherwise discard a returned bitmap before its owner sees it.
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    if (!closed && cancel === token) {
                        running = false
                        if (token.get()) { clear(); visible = false }
                        else if (failure != null) { errorCode = failure; status = errorMessage(failure!!) }
                        else {
                            title = suggestedTitle(name); body = text; preview = output
                            status = "请检查标题和正文，正文可在下方修改。"
                            // Published bitmaps remain GC-owned while Compose may still draw the old frame.
                            result = null
                        }
                    }
                    result?.close()
                }
            }
        }
    }

    fun retryLegacy() { uri?.let { start(it, type, folderId, legacy = true) } }
    fun dismiss() {
        if (running) { cancel.set(true); cancelling = true; status = "正在取消，请稍候…" }
        else { clear(); visible = false }
    }
    private fun clear() { preview = null; body = ""; title = ""; errorCode = null; checked = false; cancelling = false; uri = null }
    fun close() { closed = true; cancel.set(true); clear() }

    private fun displayName(source: Uri): String = runCatching {
        app.contentResolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else ""
        } ?: ""
    }.getOrDefault("")

    private fun suggestedTitle(name: String): String = name.substringBeforeLast('.', name).trim().take(500)

    private fun warning(code: String): String? = when (code) {
        "table_layout_requires_review" -> "表格已转为文字，请检查单元格对应关系。"
        "two_columns_heuristic_requires_review", "native_layout_requires_review" -> "此页可能含双栏或表格，请检查阅读顺序。"
        "font_mapping_requires_review" -> "字体映射可能不完整，请检查乱码或漏字。"
        "orientation_ambiguous_requires_review" -> "图片方向不确定，请核对文字方向。"
        "long_image_geometry_overlap_merge" -> "长图已分段识别，请检查接缝处漏字或重复。"
        "blank_heuristic_requires_review" -> "此页按空白处理，请对照原文件确认。"
        "contains_illustration_requires_review" -> "此页包含配图，请检查图片中是否有遗漏的文字。"
        "dark_background_inverted" -> "已处理深色背景，请核对浅色文字。"
        else -> null
    }

    private fun errorMessage(code: String): String = when (code) {
        "ENCODING_REQUIRED" -> "无法按 UTF 编码读取。如果确认文件使用 GB18030 编码，可以按该编码重试。"
        "BINARY_OR_CONTROL_DATA" -> "文件包含非文本内容，请选择 Markdown 文本文件。"
        "EMPTY_DOCUMENT", "NO_TEXT", "NO_TEXT_WITH_GRAPHICS" -> "未读取到可用文字，请检查原文件。"
        "INCOMPLETE_OCR", "PAGE_COUNT_MISMATCH" -> "部分页面未能完整读取，请检查原文件后重试。"
        "PASSWORD_REQUIRED" -> "PDF 受密码保护，请先在其他工具中解锁后导入。"
        "INVALID_IMAGE" -> "PNG 图片无效或已损坏。"
        "INVALID_PDF" -> "PDF 无效或无法完整解析。"
        "SIZE_LIMIT" -> "文件过大：Markdown 最多 8 MB，PDF 和 PNG 最多 32 MB。"
        "PAGE_LIMIT" -> "目前一次最多读取 30 页 PDF，请拆分后导入。"
        "PIXEL_LIMIT", "DETECTOR_PIXEL_LIMIT", "RECOGNIZER_WIDTH_LIMIT" -> "图片尺寸过大，请缩小或分段后重试。"
        "TEXT_LIMIT" -> "正文超过 100 万字符，请分成多个文件导入。"
        "MEMORY_LIMIT" -> "当前内存不足，请关闭其他应用，或缩小文件后重试。"
        else -> "文件读取失败，请检查文件是否可访问后重试。"
    }
}
