package com.wenbo.docsreader

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wenbo.docsreader.util.UriUtil
import com.wenbo.docsreader.util.ZipUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 管理当前打开的文档、"最近查看"列表与文件夹浏览状态。
 * - 最近查看数量不限，重复打开同一文件会移到最前
 * - 应用内选择器/文件夹授权打开的文件带持久化权限，重启后仍可点击
 * - 其他应用跳转打开的文件仅在本次应用存活期间显示，权限失效自动清理
 * - 最近条目可附带"来源树 URI"（来自文件夹授权），打开时可解析相对路径资源
 */
class ReaderViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx: Context get() = getApplication()

    var doc by mutableStateOf<DocumentInfo?>(null)
        private set

    var recents by mutableStateOf<List<DocumentInfo>>(emptyList())
        private set

    /** 当前正在浏览的授权文件夹（null = 不浏览） */
    var browseTree by mutableStateOf<Uri?>(null)
        private set

    private val prefs = app.getSharedPreferences("recents", Context.MODE_PRIVATE)

    init {
        ZipUtil.cleanStaleCache(app)
        refreshRecents()
    }

    /**
     * 打开文档；tree 非空表示该文档来自"文件夹授权"。
     * tree 为空时自动从已持久授权的文件夹中查找包含该文档的树，
     * 使"最近查看"或单文件选择器打开的文件也能解析相对路径图片。
     */
    fun openDocument(uri: Uri, tree: Uri? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val treeUri = tree ?: findContainingTree(uri)
            val info = buildInfo(uri, strict = false)?.copy(treeUri = treeUri) ?: return@launch
            withContext(Dispatchers.Main) { doc = info }
            if (info.type != DocType.UNSUPPORTED) pushRecent(uri, treeUri)
        }
    }

    /** 在已持久授权的树中查找包含该文档的树，返回最具体（最深）的一个。 */
    private fun findContainingTree(docUri: Uri): Uri? {
        val authority = docUri.authority ?: return null
        val docId = runCatching { DocumentsContract.getDocumentId(docUri) }.getOrNull() ?: return null
        var best: Uri? = null
        var bestLen = -1
        for (perm in ctx.contentResolver.persistedUriPermissions) {
            if (!perm.isReadPermission) continue
            val tree = perm.uri
            if (tree.authority != authority) continue
            val treeId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: continue
            if ((docId == treeId || docId.startsWith("$treeId/")) && treeId.length > bestLen) {
                best = tree
                bestLen = treeId.length
            }
        }
        return best
    }

    /** 文档唯一键：同一 provider 下同一个 document 的树/非树包装 URI 视为同一条 */
    private fun docKey(uriStr: String): String = runCatching {
        val u = Uri.parse(uriStr)
        "${u.authority}|${DocumentsContract.getDocumentId(u)}"
    }.getOrDefault(uriStr)

    /** 用户主动打开（选择器 / 其他应用跳转）：先尝试升级为持久读权限。 */
    fun openFromPicker(uri: Uri) {
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        openDocument(uri)
    }

    /** 授权一个文件夹并进入浏览。 */
    fun openTree(uri: Uri) {
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        browseTree = uri
    }

    fun closeBrowser() { browseTree = null }

    fun closeDoc() {
        doc = null
        refreshRecents()
    }

    fun removeRecent(uri: Uri) {
        runCatching {
            ctx.contentResolver.releasePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        val key = uri.toString()
        val list = readOrder().toMutableList()
        list.removeAll { it.first == key }
        writeOrder(list)
        refreshRecents()
    }

    /**
     * 重建列表：按文档去重（保留最近一条）、缺失时补全可自动匹配的树授权，
     * 逐个校验条目仍可访问，不可访问的自动清理。
     */
    fun refreshRecents() {
        viewModelScope.launch(Dispatchers.IO) {
            val order = readOrder()
            val merged = LinkedHashMap<String, Pair<String, String?>>(order.size)
            for ((uriStr, treeStr) in order) {
                val key = docKey(uriStr)
                val recovered = treeStr ?: runCatching { Uri.parse(uriStr) }.getOrNull()
                    ?.let { findContainingTree(it) }?.toString()
                val prev = merged[key]
                merged[key] = if (prev == null) uriStr to recovered
                else prev.first to (prev.second ?: recovered)
            }
            val valid = ArrayList<Pair<String, String?>>(merged.size)
            val infos = ArrayList<DocumentInfo>(merged.size)
            for ((uriStr, treeStr) in merged.values) {
                val tree = treeStr?.let { runCatching { Uri.parse(it) }.getOrNull() }
                val info = buildInfo(Uri.parse(uriStr), strict = true)?.copy(treeUri = tree)
                if (info != null) {
                    valid.add(uriStr to treeStr)
                    infos.add(info)
                }
            }
            if (valid != order) writeOrder(valid)
            withContext(Dispatchers.Main) { recents = infos }
        }
    }

    private fun pushRecent(uri: Uri, tree: Uri?) {
        val key = docKey(uri.toString())
        val list = readOrder().toMutableList()
        list.removeAll { docKey(it.first) == key }
        list.add(0, uri.toString() to tree?.toString())
        writeOrder(list)
        refreshRecents()
    }

    /** 每行 `uri` 或 `uri\ttreeUri` */
    private fun readOrder(): List<Pair<String, String?>> =
        prefs.getString("order", "")?.split("\n")
            ?.filter { it.isNotBlank() }
            ?.map {
                val parts = it.split("\t")
                parts[0].trim() to parts.getOrNull(1)?.trim()?.takeIf { t -> t.isNotEmpty() }
            }
            ?: emptyList()

    private fun writeOrder(list: List<Pair<String, String?>>) {
        val joined = list.joinToString("\n") { (u, t) -> if (t == null) u else "$u\t$t" }
        prefs.edit().putString("order", joined).apply()
    }

    /**
     * 读取文档元信息。
     * strict=true 用于"最近查看"校验：要求能查到真实文件名；
     * strict=false 用于打开流程：查不到名字时退回 uri 末段，保证仍能打开。
     */
    private fun buildInfo(uri: Uri, strict: Boolean): DocumentInfo? = runCatching {
        val name: String = (if (strict) UriUtil.queryNameOrNull(ctx, uri) else UriUtil.displayName(ctx, uri))
            ?: return@runCatching null
        DocumentInfo(
            uri = uri,
            name = name,
            size = UriUtil.size(ctx, uri),
            type = DocType.detect(name, ctx.contentResolver.getType(uri)),
        )
    }.getOrNull()
}
