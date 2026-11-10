package com.wenbo.docsreader.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset

/** content:// Uri 的常用查询与读取。 */
object UriUtil {

    /** 查询不到 DISPLAY_NAME 时返回 null（用于最近查看的可读性校验） */
    fun queryNameOrNull(ctx: Context, uri: Uri): String? {
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && !c.isNull(idx)) return c.getString(idx)
                }
            }
        }
        return null
    }

    fun displayName(ctx: Context, uri: Uri): String =
        queryNameOrNull(ctx, uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "未命名文档"

    fun size(ctx: Context, uri: Uri): Long {
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0 && !c.isNull(idx)) return c.getLong(idx)
                }
            }
        }
        return -1
    }

    fun lastModified(ctx: Context, uri: Uri): Long {
        runCatching {
            // DocumentsContract.Document.COLUMN_LAST_MODIFIED = "last_modified"
            ctx.contentResolver.query(uri, arrayOf("last_modified"), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
            }
        }
        return 0
    }

    fun readBytes(ctx: Context, uri: Uri, limit: Long = Long.MAX_VALUE): ByteArray =
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream(64 * 1024)
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (total + n > limit) {
                    out.write(buf, 0, (limit - total).toInt().coerceAtLeast(0))
                    break
                }
                out.write(buf, 0, n)
                total += n
            }
            out.toByteArray()
        } ?: throw java.io.IOException("无法打开文件流")

    /**
     * 自动识别编码：UTF-8（含 BOM）优先，失败退回 GBK，再失败用 Latin-1 保证不抛异常。
     */
    fun decodeSmart(bytes: ByteArray): String {
        var off = 0
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            off = 3 // UTF-8 BOM
        }
        val len = bytes.size - off
        try {
            return Charset.forName("UTF-8").newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, off, len)).toString()
        } catch (_: CharacterCodingException) {
        }
        return try {
            Charset.forName("GBK").decode(ByteBuffer.wrap(bytes, off, len)).toString()
        } catch (_: Exception) {
            Charset.forName("ISO-8859-1").decode(ByteBuffer.wrap(bytes, off, len)).toString()
        }
    }

    fun formatSize(bytes: Long): String = when {
        bytes < 0 -> "未知大小"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
        else -> "%.2f GB".format(bytes / 1024.0 / 1024 / 1024)
    }

    fun formatDate(millis: Long): String {
        if (millis <= 0) return ""
        return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(millis))
    }
}
