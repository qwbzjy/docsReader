package com.wenbo.docsreader.viewer

import android.net.Uri
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.wenbo.docsreader.DocumentInfo
import com.wenbo.docsreader.ui.Load
import com.wenbo.docsreader.ui.rememberLoaded
import com.wenbo.docsreader.util.UriUtil
import com.wenbo.docsreader.util.ZipUtil
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_HTML_BYTES = 30L * 1024 * 1024 // 30MB 封顶

/**
 * HTML 查看器：WebView 渲染。
 * 把文件复制到应用缓存后经 file:// 加载，相对路径的内嵌资源（图片/CSS/JS）
 * 若与 html 同目录无法随 SAF 一起获得，仅当资源内联或走网络时完整显示。
 * 支持内联脚本（导出报告/图表常依赖 JS）与双指缩放。
 */
@Composable
fun HtmlViewer(doc: DocumentInfo) {
    val ctx = LocalContext.current
    val load = rememberLoaded(doc.uri) {
        withContext(Dispatchers.IO) {
            val total = UriUtil.size(ctx, doc.uri)
            if (total > MAX_HTML_BYTES) throw IOException("HTML 文件过大（>30MB）")
            val dir = File(ctx.cacheDir, "html/" + ZipUtil.uriHash(doc.uri))
            ZipUtil.cleanDir(dir)
            dir.mkdirs()
            // 文件名去除路径分隔符等非法字符
            val safeName = doc.name
                .replace(Regex("[/\\\\:*?\"<>|\\n\\r\\t]"), "_")
                .ifBlank { "page.html" }
            val file = File(dir, safeName)
            ctx.contentResolver.openInputStream(doc.uri)?.use { input ->
                file.outputStream().use { out -> input.copyTo(out) }
            } ?: throw IOException("无法打开文件流")
            Uri.fromFile(file).toString()
        }
    }
    when (load) {
        Load.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        is Load.Failure -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            Text("打开失败：${load.message}", color = Color.DarkGray)
        }
        is Load.Success -> HtmlWebView(load.value)
    }
}

@Composable
private fun HtmlWebView(url: String) {
    AndroidView(
        modifier = Modifier.fillMaxSize().background(Color.White),
        factory = { c ->
            WebView(c).apply {
                setBackgroundColor(0xFFFFFFFF.toInt())
                settings.apply {
                    javaScriptEnabled = true
                    allowFileAccess = true
                    allowContentAccess = true
                    builtInZoomControls = true
                    displayZoomControls = false
                    textZoom = 100
                }
                webViewClient = object : android.webkit.WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: android.webkit.WebResourceRequest?,
                    ): Boolean = true // 页面内跳转不离开阅读器，资源加载不受影响
                }
            }
        },
        update = { web ->
            if (web.url != url) web.loadUrl(url)
        },
    )
}
