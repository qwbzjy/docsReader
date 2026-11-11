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

/** 树授权下解析出的相对路径链接目标 */
data class ResolvedLink(val uri: Uri, val name: String, val mime: String?)

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
     * 解析 markdown 相对路径引用的任意目标文档（用于链接点击：视频/附件/其他文档）。
     * 已是绝对 scheme、无树授权或无法定位时返回 null。
     * 不解码文件内容、不复制到缓存，交由调用方交给系统播放器或应用内查看器。
     */
    fun resolveLink(ctx: Context, treeUri: Uri?, docUri: Uri, dest: String): ResolvedLink? {
        if (treeUri == null || dest.isEmpty()) return null
        if (hasLoadableScheme(dest)) return null
        val docId = runCatching { DocumentsContract.getDocumentId(docUri) }.getOrNull() ?: return null
        val stack = docId.split('/').dropLast(1).toMutableList() // 文档所在目录段
        if (stack.size < 1) return null
        val targetDocId = resolveDocId(stack, dest) ?: return null
        val targetUri = runCatching {
            DocumentsContract.buildDocumentUriUsingTree(treeUri, targetDocId)
        }.getOrNull() ?: return null
        // query 一次既确认目标存在（后续 getType 对不存在的文档也可能返回 null）
        // 又拿到展示名；个别 provider 不给名字时用 documentId 末段兜底
        val name = UriUtil.queryNameOrNull(ctx, targetUri)
            ?: targetDocId.substringAfterLast('/')
        val mime = runCatching { ctx.contentResolver.getType(targetUri) }.getOrNull()
        return ResolvedLink(targetUri, name, mime)
    }

    /**
     * 把相对引用拼接到 baseDir（文档所在目录的 documentId 段列表）上并规范化，
     * 返回目标 documentId；绝对路径（以 / 开头）或越过授权根目录时返回 null。
     */
    private fun resolveDocId(baseDir: List<String>, dest: String): String? =
        normalizeRelative(baseDir, dest)

    /**
     * 相对路径规范化（纯 JVM，便于单测）：
     * 去掉锚点/查询串、做百分号解码，再与文档目录拼接并消解 `.`/`..`。
     * 结果越出授权根目录或引用绝对路径时返回 null。
     */
    internal fun normalizeRelative(baseDir: List<String>, dest: String): String? {
        val cleaned = dest.substringBefore('#').substringBefore('?')
        if (cleaned.isEmpty()) return null
        val decoded = percentDecode(cleaned)
        if (decoded.startsWith("/")) return null // 绝对路径引用不支持
        val stack = baseDir.toMutableList()
        for (seg in decoded.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> {
                    if (stack.size <= 1) return null
                    stack.removeAt(stack.lastIndex)
                }
                else -> stack.add(seg)
            }
        }
        if (stack.size <= 1) return null // 越过了授权根目录
        return stack.joinToString("/")
    }

    /**
     * 百分号解码。不用 android.net.Uri.decode 是为了保持纯 JVM 可测试；
     * 先把 `+` 转义，避免 URLDecoder 把路径中的加号当成空格。
     */
    private fun percentDecode(s: String): String =
        runCatching { java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }
            .getOrDefault(s)

    /**
     * 把绝对文件路径映射到某个已持久授权的树内文档。
     * Android 10+ 分区存储下应用通常无法直读 /sdcard 下的文件，
     * 但只要用户授权过该目录所在的树，就能经 ContentResolver 读取。
     */
    fun resolveAbsoluteFile(ctx: Context, path: String): ResolvedLink? {
        val abs = normalizeStoragePath(path) ?: return null
        for (perm in ctx.contentResolver.persistedUriPermissions) {
            if (!perm.isReadPermission) continue
            val tree = perm.uri
            val treeId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: continue
            val rootPath = storagePathOf(treeId) ?: continue
            if (abs != rootPath && !abs.startsWith("$rootPath/")) continue
            val targetDocId = treeId + abs.removePrefix(rootPath)
            val targetUri = runCatching {
                DocumentsContract.buildDocumentUriUsingTree(tree, targetDocId)
            }.getOrNull() ?: continue
            val name = UriUtil.queryNameOrNull(ctx, targetUri) ?: continue
            val mime = runCatching { ctx.contentResolver.getType(targetUri) }.getOrNull()
            return ResolvedLink(targetUri, name, mime)
        }
        return null
    }

    /** 把 `/sdcard`、`/storage/self/primary` 等常见别名归一为 `/storage/emulated/0` */
    private fun normalizeStoragePath(path: String): String? {
        val abs = when {
            path == "/sdcard" || path.startsWith("/sdcard/") ->
                "/storage/emulated/0" + path.removePrefix("/sdcard")
            path.startsWith("/storage/self/primary/") ->
                "/storage/emulated/0/" + path.removePrefix("/storage/self/primary/")
            else -> path
        }.trimEnd('/')
        return abs.takeIf { it.startsWith("/storage/") && it.isNotEmpty() }
    }

    /** ExternalStorageProvider 的 documentId（`primary:Download/X`）→ 文件系统路径 */
    private fun storagePathOf(treeDocId: String): String? {
        val volume = treeDocId.substringBefore(':', "")
        val rel = treeDocId.substringAfter(':', "")
        val root = when (volume) {
            "primary", "home" -> "/storage/emulated/0"
            else -> "/storage/$volume"
        }
        return if (rel.isEmpty()) root else "$root/$rel"
    }

    /** 去掉锚点/查询串并做 URL 解码；空路径返回 null */
    private fun decodePath(dest: String): String? {
        val cleaned = dest.substringBefore('#').substringBefore('?')
        if (cleaned.isEmpty()) return null
        return percentDecode(cleaned)
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
        val targetDocId = resolveDocId(baseDir, dest) ?: return null
        val targetUri = runCatching {
            DocumentsContract.buildDocumentUriUsingTree(treeUri, targetDocId)
        }.getOrNull() ?: return null

        val flat = targetDocId.split('/').drop(1).joinToString("_") {
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
