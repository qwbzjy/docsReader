package com.wenbo.docsreader.util

import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/** OOXML 文件的缓存与解压管理。 */
object ZipUtil {

    /** Uri 的稳定短哈希，作为缓存目录名 */
    fun uriHash(uri: Uri): String =
        MessageDigest.getInstance("SHA-1").digest(uri.toString().toByteArray())
            .joinToString("") { "%02x".format(it) }.substring(0, 16)

    /** 每份文档的缓存目录：<cache>/ooxml/<hash>/ */
    fun docDir(ctx: Context, uri: Uri): File =
        File(File(ctx.cacheDir, "ooxml"), uriHash(uri))

    /** 把 content 流复制为缓存文件（ZipFile 需要随机访问的本地文件） */
    fun copyToCache(ctx: Context, uri: Uri, ext: String): File {
        val dir = docDir(ctx, uri)
        cleanDir(dir)
        dir.mkdirs()
        val file = File(dir, "source.$ext")
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { out -> input.copyTo(out) }
        } ?: throw java.io.IOException("无法打开文件流")
        return file
    }

    /** 解压 zip 中图片等资源，返回解压根目录（供 WebView baseUrl 使用）。
     *  只解压媒体相关目录，其他文件跳过以省时间。 */
    fun extractMedia(ctx: Context, uri: Uri, zip: ZipFile): File {
        val unzipDir = File(docDir(ctx, uri), "unzip")
        unzipDir.mkdirs()
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.isDirectory) continue
            val name = entry.name
            // 只解出图片等媒体文件（word/media、ppt/media、xl/media），其余跳过
            if (!name.contains("/media/")) continue
            val target = File(unzipDir, name)
            // 防 zip-slip
            if (!target.canonicalPath.startsWith(unzipDir.canonicalPath)) continue
            target.parentFile?.mkdirs()
            zip.getInputStream(entry).use { input ->
                target.outputStream().use { out -> input.copyTo(out) }
            }
        }
        return unzipDir
    }

    fun cleanDir(dir: File) {
        runCatching { dir.deleteRecursively() }
    }

    /** 清理所有过期缓存（超过 1 天未修改的）：ooxml 解压目录 + markdown 图片缓存 */
    fun cleanStaleCache(ctx: Context) {
        val cutoff = System.currentTimeMillis() - 24L * 3600 * 1000
        listOf(File(ctx.cacheDir, "ooxml"), File(ctx.cacheDir, "tree")).forEach { root ->
            root.listFiles()?.forEach { d ->
                if (d.lastModified() < cutoff) cleanDir(d)
            }
        }
    }
}
