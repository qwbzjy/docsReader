package com.wenbo.docsreader

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wenbo.docsreader.util.UriUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 管理当前打开的文档与"最近查看"列表。
 * - 列表数量不限，重复打开同一文件会移到最前
 * - 优先记录带持久化 URI 权限的文件（应用内选择器打开），重启后仍可点击
 * - 其他应用"打开方式"跳转的文件（仅临时权限）在应用存活期间也会显示，
 *   权限失效后自动从列表移除
 */
class ReaderViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx: Context get() = getApplication()

    var doc by mutableStateOf<DocumentInfo?>(null)
        private set

    var recents by mutableStateOf<List<DocumentInfo>>(emptyList())
        private set

    private val prefs = app.getSharedPreferences("recents", Context.MODE_PRIVATE)

    init {
        com.wenbo.docsreader.util.ZipUtil.cleanStaleCache(app)
        refreshRecents()
    }

    /** 打开文档，并把可读取的文件记入最近查看。 */
    fun openDocument(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val info = buildInfo(uri, strict = false) ?: return@launch
            withContext(Dispatchers.Main) { doc = info }
            if (info.type != DocType.UNSUPPORTED) pushRecent(uri)
        }
    }

    /** 用户主动打开（选择器 / 其他应用跳转）：先尝试升级为持久读权限。 */
    fun openFromPicker(uri: Uri) {
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        openDocument(uri)
    }

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
        val list = readOrder().toMutableList()
        list.remove(uri.toString())
        writeOrder(list)
        refreshRecents()
    }

    /** 重建列表：逐个校验条目仍可访问，不可访问的自动清理。 */
    fun refreshRecents() {
        viewModelScope.launch(Dispatchers.IO) {
            val order = readOrder()
            val valid = ArrayList<String>(order.size)
            val infos = ArrayList<DocumentInfo>(order.size)
            for (s in order) {
                val info = buildInfo(Uri.parse(s), strict = true)
                if (info != null) {
                    valid.add(s)
                    infos.add(info)
                }
            }
            if (valid != order) writeOrder(valid)
            withContext(Dispatchers.Main) { recents = infos }
        }
    }

    private fun pushRecent(uri: Uri) {
        val list = readOrder().toMutableList()
        list.remove(uri.toString())
        list.add(0, uri.toString())
        writeOrder(list)
        refreshRecents()
    }

    private fun readOrder(): List<String> =
        prefs.getString("order", "")?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()

    private fun writeOrder(list: List<String>) {
        prefs.edit().putString("order", list.joinToString("\n")).apply()
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
