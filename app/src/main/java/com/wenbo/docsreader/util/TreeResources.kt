package com.wenbo.docsreader.util

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/** 文件夹浏览条目 */
data class TreeEntry(
    val docId: String,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val mime: String?,
)

/**
 * 「授权整个文件夹」（OpenDocumentTree）后的资源解析：
 * SAF 单文件授权拿不到同目录图片，但树授权可以。
 * 把 markdown 中相对路径引用的图片经树 URI 读出、复制到应用缓存，
 * 并把引用改写为绝对 file:// 路径，交给 Markwon 的图片插件直接加载。
 */
object TreeResources {

    private val IMAGE = Regex("""!\[([^\]]*)\]\(([^)]+)\)""")

    /** 已是可直接加载的绝对引用（远程/内联/绝对 scheme） */
    private val LOADABLE_SCHEME = Regex("""(?i)^(?:https?:|data:|file:|content:|assets:|res:|//)""")

    /** dest 是否已带可加载 scheme（无需经树授权解析） */
    internal fun hasLoadableScheme(dest: String): Boolean =
        LOADABLE_SCHEME.containsMatchIn(dest)

    /** 列出树授权目录下某文档的子项 */
    fun listChildren(ctx: Context, treeUri: Uri, parentDocId: String): List<TreeEntry> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val out = ArrayList<TreeEntry>()
        runCatching {
            ctx.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                ),
                null, null, null,
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val iName = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val iMime = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val iSize = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                while (c.moveToNext()) {
                    val docId = c.getString(iId) ?: continue
                    val mime = if (iMime >= 0) c.getString(iMime) else null
                    val name = (if (iName >= 0) c.getString(iName) else null)
                        ?: docId.substringAfterLast('/')
                    val size = if (iSize >= 0 && !c.isNull(iSize)) c.getLong(iSize) else -1L
                    out.add(TreeEntry(docId, name, mime == DocumentsContract.Document.MIME_TYPE_DIR, size, mime))
                }
            }
        }
        out.sortWith(compareByDescending<TreeEntry> { it.isDir }.thenBy { it.name.lowercase() })
        return out
    }

    /** 改写 markdown 中相对路径图片引用为缓存 file:// 路径 */
    fun rewriteMarkdown(
        ctx: Context,
        text: String,
        treeUri: Uri?,
        docUri: Uri,
        cacheKey: String,
    ): String {
        if (treeUri == null) return text
        val docId = runCatching { DocumentsContract.getDocumentId(docUri) }.getOrNull() ?: return text
        val baseStack = docId.split('/')  // 如 ["primary:Notes", "2024", "a.md"]
        if (baseStack.size < 2) return text
        val baseDir = baseStack.dropLast(1).toMutableList() // 文档所在目录段

        val outDir = File(File(ctx.cacheDir, "tree"), cacheKey)
        ZipUtil.cleanDir(outDir)
        outDir.mkdirs()

        return IMAGE.replace(text) { m ->
            val alt = m.groupValues[1]
            val inner = m.groupValues[2].trim()
            val (dest, titleSuffix) = splitTitle(inner)
            val resolved = resolveAndCopy(ctx, treeUri, baseDir, dest, outDir)
            if (resolved == null) m.value
            else "![${alt}](${resolved}${if (titleSuffix.isEmpty()) "" else " $titleSuffix"})"
        }
    }

    /** 拆分 `path "title"`；dest 为 URL 编码前的原始串 */
    private fun splitTitle(inner: String): Pair<String, String> {
        val t = Regex("""\s+"[^"]*"\s*$""").find(inner)
        return if (t != null) inner.substring(0, t.range.first).trim() to t.value.trim()
        else inner to ""
    }

    /**
     * 解析相对引用并复制到缓存，返回 file:// 绝对路径；
     * 远程协议（http/data/file/content/assets/res）或无法定位/读取时返回 null（保持原样）。
     */
    private fun resolveAndCopy(
        ctx: Context,
        treeUri: Uri,
        baseDir: List<String>,
        dest: String,
        outDir: File,
    ): String? {
        if (dest.isEmpty()) return null
        if (hasLoadableScheme(dest)) return null
        val cleaned = dest.substringBefore('#').substringBefore('?')
        val decoded = runCatching { Uri.decode(cleaned) }.getOrElse { cleaned }
        if (decoded.startsWith("/")) return null // 绝对路径引用不支持

        // 相对路径与文档目录做规范化拼接
        val stack = baseDir.toMutableList()
        for (seg in decoded.split('/')) {
            when (seg) {
                "", ".", ".." -> if (seg == "..") {
                    if (stack.size <= 1) return null
                    stack.removeAt(stack.lastIndex)
                }
                else -> stack.add(seg)
            }
        }
        if (stack.size <= 1) return null // 越过了授权根目录
        val targetDocId = stack.joinToString("/")
        val targetUri = runCatching {
            DocumentsContract.buildDocumentUriUsingTree(treeUri, targetDocId)
        }.getOrNull() ?: return null

        val flat = stack.drop(1).joinToString("_") {
            it.replace(Regex("[^A-Za-z0-9.\\-_\\u4e00-\\u9fa5]"), "_")
        }.take(140) + "_" + targetDocId.hashCode().toUInt().toString(16)
        val target = File(outDir, flat)

        return runCatching {
            ctx.contentResolver.openInputStream(targetUri)?.use { ins ->
                target.outputStream().use { out -> ins.copyTo(out) }
            } ?: return null
            if (target.length() <= 0) return null
            "file://" + target.absolutePath
        }.getOrNull()
    }
}
