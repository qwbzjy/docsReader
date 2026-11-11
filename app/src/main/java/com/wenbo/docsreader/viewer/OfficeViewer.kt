package com.wenbo.docsreader.viewer

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.wenbo.docsreader.DocumentInfo
import com.wenbo.docsreader.DocType
import com.wenbo.docsreader.legacy.LegacyOffice
import com.wenbo.docsreader.ooxml.DocxToHtml
import com.wenbo.docsreader.ooxml.PptxToHtml
import com.wenbo.docsreader.ooxml.XlsxToHtml
import com.wenbo.docsreader.ui.Load
import com.wenbo.docsreader.ui.rememberLoaded
import com.wenbo.docsreader.util.ZipUtil
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Office 文档解析结果：一组页面，每页是独立 HTML + 可选 baseUrl（PPT 需要）。 */
private class OfficePages(
    val titles: List<String>,
    val pages: List<String>,
    val baseUrl: String?,
    val fitWidth: Boolean, // PPT 页需要整页缩放
)

/**
 * Word / Excel / PPT 查看器：
 * 后台线程解析 OOXML 为 HTML，交给 WebView 渲染；
 * 多页文档（多工作表 / 多幻灯片）用左右滑动 + 页签跳转。
 * 旧版二进制格式（.doc/.xls/.ppt）由 LegacyOffice（Apache POI / 记录扫描）解析。
 */
@Composable
fun OfficeViewer(doc: DocumentInfo) {
    val ctx = LocalContext.current
    val load = rememberLoaded(doc.uri) {
        withContext(Dispatchers.IO) { parseOffice(ctx, doc) }
    }
    when (load) {
        Load.Loading -> Centered { CircularProgressIndicator() }
        is Load.Failure -> Centered {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("打开失败", style = MaterialTheme.typography.titleMedium, color = Color.DarkGray)
                Text(
                    load.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        is Load.Success -> OfficePager(load.value)
    }
}

private suspend fun parseOffice(
    ctx: android.content.Context,
    doc: DocumentInfo,
): OfficePages {
    val uri = doc.uri
    val type = doc.type

    // ---- 旧版二进制格式 ----
    val legacyExt = doc.name.substringAfterLast('.', "").lowercase()
    if (legacyExt in setOf("doc", "xls", "ppt", "pps", "pot")) {
        val file = ZipUtil.copyToCache(ctx, uri, legacyExt)
        try {
            return when (type) {
                DocType.WORD -> OfficePages(listOf("正文"), listOf(LegacyOffice.docToHtml(file)), null, false)
                DocType.EXCEL -> {
                    val sheets = LegacyOffice.xlsToSheets(file)
                    if (sheets.isEmpty()) throw IllegalArgumentException("没有可用的工作表")
                    OfficePages(sheets.map { it.name }, sheets.map { it.html }, null, false)
                }
                DocType.PPT -> {
                    val slides = LegacyOffice.pptToSlides(file)
                    if (slides.isEmpty()) throw IllegalArgumentException("没有可用的幻灯片")
                    OfficePages(slides.indices.map { "第${it + 1}页" }, slides, null, false)
                }
                else -> throw IllegalArgumentException("不支持的类型")
            }
        } finally {
            ZipUtil.cleanDir(ZipUtil.docDir(ctx, uri))
        }
    }

    // ---- OOXML 新版格式 ----
    val ext = when (type) {
        DocType.WORD -> "docx"
        DocType.EXCEL -> "xlsx"
        DocType.PPT -> "pptx"
        else -> "bin"
    }
    val file = ZipUtil.copyToCache(ctx, uri, ext)
    val zip = ZipFile(file)
    try {
        return when (type) {
            DocType.WORD -> {
                val dir = ZipUtil.extractMedia(ctx, uri, zip)
                OfficePages(listOf("正文"), listOf(DocxToHtml.convert(zip)), Uri.fromFile(dir).toString() + "/", false)
            }
            DocType.EXCEL -> {
                val sheets = XlsxToHtml.convert(zip)
                if (sheets.isEmpty()) throw IllegalArgumentException("没有可用的工作表")
                OfficePages(sheets.map { it.name }, sheets.map { it.html }, null, false)
            }
            DocType.PPT -> {
                val dir = ZipUtil.extractMedia(ctx, uri, zip)
                val slides = PptxToHtml.convert(zip)
                if (slides.isEmpty()) throw IllegalArgumentException("没有可用的幻灯片")
                OfficePages(
                    slides.map { "第${it.index}页" },
                    slides.map { it.html },
                    Uri.fromFile(dir).toString() + "/",
                    true,
                )
            }
            else -> throw IllegalArgumentException("不支持的类型")
        }
    } finally {
        zip.close()
        runCatching { file.delete() }
    }
}

@Composable
private fun OfficePager(data: OfficePages) {
    val pagerState = rememberPagerState(pageCount = { data.pages.size })

    Column(Modifier.fillMaxSize().background(if (data.fitWidth) Color(0xFFEFEFEF) else Color.White)) {
        if (data.pages.size > 1) {
            val selected = pagerState.currentPage
            HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
                OfficeWebView(data.pages[page], data.baseUrl, data.fitWidth, key = page)
            }
            PageIndicator(
                current = selected + 1,
                total = data.pages.size,
                label = data.titles.getOrNull(selected).orEmpty(),
            )
        } else {
            OfficeWebView(data.pages[0], data.baseUrl, data.fitWidth, key = 0)
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun OfficeWebView(html: String, baseUrl: String?, fitWidth: Boolean, key: Int) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { c ->
            WebView(c).apply {
                setBackgroundColor(if (fitWidth) 0xFFEFEFEF.toInt() else 0xFFFFFFFF.toInt())
                settings.apply {
                    javaScriptEnabled = false
                    allowFileAccess = true
                    allowContentAccess = false
                    builtInZoomControls = true // Word/Excel/PPT 均支持双指缩放
                    displayZoomControls = false
                    // PPT 整页适配：以幻灯片逻辑宽度渲染，WebView 自动缩放到屏宽
                    useWideViewPort = fitWidth
                    loadWithOverviewMode = fitWidth
                    textZoom = 100
                }
                webViewClient = object : android.webkit.WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: android.webkit.WebResourceRequest?,
                    ): Boolean = true // 文档内链接一律不跳转
                }
            }
        },
        update = { web ->
            web.loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null)
        },
    )
}

@Composable
private fun PageIndicator(current: Int, total: Int, label: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$label · $current/$total",
            style = MaterialTheme.typography.labelLarge,
            color = Color.DarkGray,
        )
    }
}
