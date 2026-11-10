package com.wenbo.docsreader.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.wenbo.docsreader.DocumentInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.floor

/** PdfRenderer 封装：加锁串行渲染，LruCache 缓存页面位图。 */
private class PdfSource(ctx: Context, uri: Uri) {
    private val pfd: ParcelFileDescriptor =
        ctx.contentResolver.openFileDescriptor(uri, "r")
            ?: throw java.io.IOException("无法打开 PDF 文件")
    private val renderer = PdfRenderer(pfd)
    private val mutex = Mutex()
    val pageCount: Int = renderer.pageCount

    private val cache = object : android.util.LruCache<Int, Bitmap>((Runtime.getRuntime().maxMemory() / 6).toInt()) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount.toInt()
    }

    suspend fun render(pageIndex: Int, targetWidthPx: Int): Bitmap {
        cache.get(pageIndex)?.takeIf { it.width == targetWidthPx && !it.isRecycled }?.let { return it }
        return mutex.withLock {
            // 双检：可能其他协程刚渲染过
            cache.get(pageIndex)?.takeIf { it.width == targetWidthPx && !it.isRecycled }?.let { return@withLock it }
            withContext(Dispatchers.IO) {
                val page = renderer.openPage(pageIndex)
                try {
                    val h = floor(page.height.toDouble() * targetWidthPx / page.width.toDouble())
                        .toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(targetWidthPx, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(
                        bmp, android.graphics.Rect(0, 0, targetWidthPx, h), null,
                        PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                    )
                    cache.put(pageIndex, bmp)
                    bmp
                } finally {
                    page.close()
                }
            }
        }
    }

    fun close() {
        runCatching { renderer.close() }
        runCatching { pfd.close() }
    }
}

/** PDF 查看器：横向翻页 + 双指缩放。 */
@Composable
fun PdfViewer(doc: DocumentInfo) {
    val ctx = LocalContext.current
    val result = remember(doc.uri) { runCatching { PdfSource(ctx, doc.uri) } }
    val source = result.getOrNull()

    DisposableEffect(source) {
        onDispose { source?.close() }
    }

    if (source == null) {
        Centered {
            Text(
                "PDF 打开失败：${result.exceptionOrNull()?.message ?: "文件损坏"}",
                color = ComposeColor.DarkGray,
            )
        }
        return
    }

    val pagerState = rememberPagerState(pageCount = { source.pageCount })
    Column(Modifier.fillMaxSize().background(ComposeColor(0xFF2B2B2B))) {
        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
            PdfPage(source, page, pagerState)
        }
        PageNumber(pagerState, source.pageCount)
    }
}

@Composable
private fun PageNumber(pagerState: PagerState, total: Int) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(ComposeColor.Black.copy(alpha = 0.55f))
            .padding(8.dp),
    ) {
        Text(
            "第 ${pagerState.currentPage + 1} / $total 页",
            color = ComposeColor.White,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

@Composable
private fun PdfPage(source: PdfSource, page: Int, pagerState: PagerState) {
    var widthPx by remember { mutableIntStateOf(0) }
    var bitmap by remember(page) { mutableStateOf<Bitmap?>(null) }
    var error by remember(page) { mutableStateOf<String?>(null) }

    // 只渲染当前页附近（±2 页），远处保持空白避免抢资源
    val settled = pagerState.settledPage
    LaunchedEffect(page, widthPx, settled) {
        if (widthPx > 0 && bitmap == null && error == null && abs(settled - page) <= 2) {
            try {
                bitmap = source.render(page, widthPx)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                // 翻页/settledPage 变化导致渲染协程被取消：不是错误，
                // key 变化后本 effect 会重新执行并继续渲染
            } catch (t: Throwable) {
                error = t.message ?: t.javaClass.simpleName
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { widthPx = it.width },
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        when {
            error != null -> Text(error!!, color = ComposeColor.Red)
            bmp != null -> ZoomableImage(bmp)
            else -> CircularProgressIndicator(color = ComposeColor.White)
        }
    }
}

/** 缩放图片：双击放大/复原；放大后支持双指捏合与拖动平移（未放大时手势让给翻页）。 */
@Composable
private fun ZoomableImage(bmp: Bitmap) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged {
                size = it
                offset = clampOffset(offset, scale, it)
            }
            .pointerInput(bmp) {
                // 双击：在 1x 与 2.5x 之间切换
                detectTapGestures(onDoubleTap = {
                    if (scale > 1.05f) {
                        scale = 1f
                        offset = Offset.Zero
                    } else {
                        scale = 2.5f
                        offset = Offset.Zero
                    }
                })
            }
            .pointerInput(scale > 1.05f) {
                // 仅在放大状态下拦截拖动/捏合，否则单指滑动留给 pager 翻页
                if (scale > 1.05f) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val newScale = (scale * zoom).coerceIn(1f, 5f)
                        scale = newScale
                        offset = if (newScale <= 1.05f) Offset.Zero
                        else clampOffset(Offset(offset.x + pan.x, offset.y + pan.y), newScale, size)
                    }
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
        )
    }
}

private fun clampOffset(o: Offset, scale: Float, size: IntSize): Offset {
    val maxX = size.width * (scale - 1f) / 2f
    val maxY = size.height * (scale - 1f) / 2f
    return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
}
