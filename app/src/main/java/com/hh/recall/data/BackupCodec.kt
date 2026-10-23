package com.hh.recall.data

import com.hh.recall.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class Library(val articles: List<Article> = emptyList(), val folders: List<Folder> = emptyList())

object JsonCodec {
    fun masks(value: List<Mask>) = JSONArray().apply {
        value.forEach { put(JSONObject().put("id", it.id).put("start", it.start).put("end", it.end)) }
    }
    fun readMasks(value: JSONArray): List<Mask> {
        require(value.length() <= 100_000) { "挖空数量超出限制" }
        return (0 until value.length()).map { i -> value.getJSONObject(i).let {
            Mask(it.getInt("start"), it.getInt("end"), it.getString("id"))
        } }.sortedBy { it.start }
    }
    fun draft(value: Draft) = JSONObject().put("articleId", value.articleId).put("title", value.title)
        .put("body", value.snapshot.text).put("masks", masks(value.snapshot.masks))
        .put("mode", value.mode).put("originalBody", value.originalBody)
        .put("originalMasks", masks(value.originalMasks)).put("folderId", value.folderId ?: JSONObject.NULL).toString()
    fun readDraft(value: String): Draft = JSONObject(value).let {
        Draft(it.getString("articleId"), it.getString("title"),
            TextSnapshot(it.getString("body"), readMasks(it.getJSONArray("masks"))),
            it.getString("mode"), it.getString("originalBody"), readMasks(it.getJSONArray("originalMasks")),
            if (it.isNull("folderId")) null else it.getString("folderId"))
    }
}

object BackupCodec {
    private const val MAX_BYTES = 32 * 1024 * 1024
    fun write(library: Library, output: OutputStream) {
        val root = JSONObject().put("format", "hh-recall").put("version", 1)
        root.put("folders", JSONArray().apply { library.folders.forEach { put(JSONObject().put("id", it.id).put("name", it.name)
            .put("lastOpenedAt", it.lastOpenedAt).put("updatedAt", it.updatedAt)) } })
        root.put("articles", JSONArray().apply {
            library.articles.forEach { article -> put(JSONObject().put("id", article.id).put("title", article.title)
                .put("body", article.body).put("folderId", article.folderId ?: JSONObject.NULL)
                .put("updatedAt", article.updatedAt).put("readingOffset", article.readingOffset)
                .put("masks", JsonCodec.masks(article.masks))) }
        })
        val bytes = root.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "资料超过当前备份大小限制（32 MB）" }
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("recall.json")); zip.write(bytes); zip.closeEntry()
        }
    }

    /** Validate everything in memory before the caller starts a database transaction. No ZIP extraction. */
    fun read(input: InputStream): Library {
        val bytes = ZipInputStream(input).use { zip ->
            val entry = zip.nextEntry ?: error("备份为空")
            require(entry.name == "recall.json" && !entry.isDirectory) { "不是拾忆备份文件" }
            val buffer = ByteArray(8192)
            val collected = ByteArrayOutputStream()
            while (true) {
                val count = zip.read(buffer)
                if (count < 0) break
                require(collected.size() + count <= MAX_BYTES) { "备份解压后超过 32 MB" }
                collected.write(buffer, 0, count)
            }
            val data = collected.toByteArray()
            zip.closeEntry()
            require(zip.nextEntry == null) { "备份包含非预期内容" }
            data
        }
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        require(root.getString("format") == "hh-recall" && root.getInt("version") == 1) { "不支持的备份格式或版本" }
        val folderArray = root.getJSONArray("folders")
        val articleArray = root.getJSONArray("articles")
        require(folderArray.length() <= 2_000 && articleArray.length() <= 10_000) { "资料数量超出限制" }
        val folders = (0 until folderArray.length()).map { index -> folderArray.getJSONObject(index).let {
            fun timestamp(key: String): Long {
                if (!it.has(key)) return 0
                val raw = it.get(key)
                require((raw is Int || raw is Long) && (raw as Number).toLong() >= 0) { "文件夹时间无效" }
                return raw.toLong()
            }
            Folder(it.getString("id"), it.getString("name"), timestamp("lastOpenedAt"), timestamp("updatedAt"))
        } }
        require(folders.all { it.id.isNotBlank() && it.name.isNotBlank() && it.name.length <= 200 }) { "文件夹数据无效" }
        val folderIds = folders.map { it.id }.toSet()
        require(folderIds.size == folders.size) { "文件夹标识重复" }
        val articles = (0 until articleArray.length()).map { index -> articleArray.getJSONObject(index).let {
            Article(it.getString("id"), it.getString("title"), it.getString("body"),
                if (it.isNull("folderId")) null else it.getString("folderId"),
                JsonCodec.readMasks(it.getJSONArray("masks")), it.getLong("updatedAt"), it.optInt("readingOffset", 0))
        } }
        require(articles.map { it.id }.toSet().size == articles.size) { "文章标识重复" }
        articles.forEach {
            require(it.id.isNotBlank() && it.title.isNotBlank() && it.title.length <= 500 &&
                it.body.isNotBlank() && it.body.length <= 2_000_000) { "文章内容无效" }
            require(it.folderId == null || it.folderId in folderIds) { "文章所属文件夹不存在" }
            require(MaskRules.valid(it.body, it.masks)) { "挖空范围或标识无效" }
            require(it.readingOffset in 0..it.body.length) { "阅读位置无效" }
        }
        return Library(articles, folders)
    }

    fun asNewCollection(library: Library, name: String): Library {
        val root = Folder(name = name)
        val folderMap = library.folders.associate { it.id to UUID.randomUUID().toString() }
        return Library(
            library.articles.map { it.copy(id = UUID.randomUUID().toString(),
                folderId = it.folderId?.let(folderMap::get) ?: root.id,
                masks = it.masks.map { mask -> mask.copy(id = UUID.randomUUID().toString()) }, updatedAt = System.currentTimeMillis()) },
            listOf(root) + library.folders.map { it.copy(id = folderMap.getValue(it.id), name = "$name / ${it.name}".take(200)) },
        )
    }
}
