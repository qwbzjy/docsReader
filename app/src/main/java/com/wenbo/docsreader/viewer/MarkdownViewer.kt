package com.wenbo.docsreader.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.text.Spanned
import android.util.TypedValue
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.TextView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wenbo.docsreader.DocumentInfo
import com.wenbo.docsreader.ui.Load
import com.wenbo.docsreader.ui.rememberLoaded
import com.wenbo.docsreader.util.TreeResources
import com.wenbo.docsreader.util.UriUtil
import com.wenbo.docsreader.util.ZipUtil
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.image.AsyncDrawableSpan
import io.noties.markwon.image.ImagesPlugin
import io.noties.markwon.image.MediaDecoder
import io.noties.markwon.image.file.FileSchemeHandler
import java.io.InputStream
import kotlin.math.abs

/** Markdown 查看器：Markwon 原生渲染（表格/删除线/任务列表/图片）。 */
@Composable
fun MarkdownViewer(doc: DocumentInfo) {
    val ctx = LocalContext.current
    val load = rememberLoaded(doc.uri) {
        val text = UriUtil.decodeSmart(UriUtil.readBytes(ctx, doc.uri, 4L * 1024 * 1024))
        // 文件夹授权打开时，把相对路径图片经树权限复制到缓存并改写为 file://
        TreeResources.rewriteMarkdown(ctx, text, doc.treeUri, doc.uri, ZipUtil.uriHash(doc.uri))
    }
    when (load) {
        Load.Loading -> Centered { CircularProgressIndicator() }
        is Load.Failure -> Centered { Text("打开失败：${load.message}", color = Color.DarkGray) }
        is Load.Success -> {
            val hasTree = doc.treeUri != null
            // 点击图片弹出的原图预览；只在 PreviewLayer 中读取，避免触发 AndroidView 重设 markdown
            val previewState = remember(doc.uri) { mutableStateOf<Bitmap?>(null) }
            val markwon = remember(ctx, hasTree) {
                Markwon.builder(ctx)
                    .usePlugin(TablePlugin.create(ctx))
                    .usePlugin(StrikethroughPlugin.create())
                    .usePlugin(TaskListPlugin.create(ctx))
                    // 图片：Markwon 4.6.2 默认仅注册 data: 与 http(s)（含明文 http）；
                    // file://（含 android_asset）需手动注册，否则树授权改写的缓存图无法加载
                    .usePlugin(
                        ImagesPlugin.create { plugin ->
                            plugin.addSchemeHandler(FileSchemeHandler.createWithAssets(ctx))
                            // 默认解码器在 decode 失败时静默返回空 BitmapDrawable，改为抛异常
                            plugin.defaultMediaDecoder(StrictMediaDecoder(ctx))
                            plugin.errorHandler { source, _ ->
                                ImageErrorDrawable(describeFailure(source, hasTree))
                            }
                        },
                    )
                    .build()
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.White),
            ) {
                AndroidView(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    factory = { c ->
                        val pad = TypedValue.applyDimension(
                            TypedValue.COMPLEX_UNIT_DIP, 16f, c.resources.displayMetrics,
                        ).toInt()
                        TextView(c).apply {
                            setPadding(pad, pad, pad, pad * 2)
                            setTextIsSelectable(true)
                            setTextColor(Color(0xFF1F2328).toArgb())
                            textSize = 16f
                            // 点击图片 → 全屏查看原图；拖动/滚动不触发
                            val slop = ViewConfiguration.get(c).scaledTouchSlop
                            var downX = 0f
                            var downY = 0f
                            var moved = false
                            setOnTouchListener { _, ev ->
                                when (ev.actionMasked) {
                                    MotionEvent.ACTION_DOWN -> {
                                        downX = ev.x; downY = ev.y; moved = false; false
                                    }
                                    MotionEvent.ACTION_MOVE -> {
                                        if (abs(ev.x - downX) + abs(ev.y - downY) > slop) moved = true
                                        false
                                    }
                                    MotionEvent.ACTION_UP -> {
                                        if (!moved) {
                                            val bmp = imageAt(this, ev.x, ev.y)
                                            if (bmp != null) {
                                                previewState.value = bmp
                                                return@setOnTouchListener true
                                            }
                                        }
                                        false
                                    }
                                    else -> false
                                }
                            }
                        }
                    },
                    update = { tv -> markwon.setMarkdown(tv, load.value) },
                )
            }
            PreviewLayer(previewState)
        }
    }
}

/**
 * 命中检测：触摸点 (x, y) 落在某张已加载完成的图片上时返回其原始位图。
 * 通过 Layout 把坐标映射到字符偏移，再在偏移处查找 Markwon 的图片 span。
 */
private fun imageAt(tv: TextView, x: Float, y: Float): Bitmap? {
    val layout = tv.layout ?: return null
    val text = tv.text as? Spanned ?: return null
    // Layout 坐标以文字区域左上角为原点，需减去 padding 才能与 View 内坐标对齐
    val tx = x + tv.scrollX - tv.totalPaddingLeft
    val ty = y + tv.scrollY - tv.totalPaddingTop
    for (span in text.getSpans(0, text.length, AsyncDrawableSpan::class.java)) {
        val start = text.getSpanStart(span)
        if (start < 0) continue
        val line = layout.getLineForOffset(start)
        if (ty < layout.getLineTop(line) || ty > layout.getLineBottom(line)) continue
        val x0 = layout.getPrimaryHorizontal(start)
        val width = span.drawable.bounds.width()
        if (width <= 0 || tx !in x0..(x0 + width)) continue
        val bitmap = (span.drawable.result as? BitmapDrawable)?.bitmap
        if (bitmap != null) return bitmap
    }
    return null
}

/** 预览层：读取状态并在独立组合作用域内展示，避免影响 AndroidView 的 update 重跑 */
@Composable
private fun PreviewLayer(state: MutableState<Bitmap?>) {
    val bitmap = state.value ?: return
    ImagePreview(bitmap) { state.value = null }
}

/** 全屏原图预览：双指缩放、拖动平移、双击放大/还原。 */
@Composable
private fun ImagePreview(bitmap: Bitmap, onDismiss: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            val halfW = constraints.maxWidth / 2f
            val halfH = constraints.maxHeight / 2f
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = { p ->
                            if (scale > 1f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                val target = 2.5f
                                val pRel = Offset(p.x - halfW, p.y - halfH)
                                // 缩放围绕点击点：保证该点在放大前后保持不动
                                offset = (pRel - pRel * target).clamped(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(), target)
                                scale = target
                            }
                        })
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            offset = if (scale > 1f) {
                                Offset(offset.x + pan.x, offset.y + pan.y).clamped(
                                    constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(), scale,
                                )
                            } else {
                                Offset.Zero
                            }
                        }
                    },
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y,
                        ),
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.35f), CircleShape),
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Color.White)
                }
                Text(
                    "双指缩放 · 双击放大/还原",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 12.sp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(24.dp)
                        .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** 平移量限制：放大 s 倍时可移动的最大距离为 (尺寸*(s-1))/2 */
private fun Offset.clamped(w: Float, h: Float, scale: Float): Offset {
    val mx = (w * (scale - 1f) / 2f).coerceAtLeast(0f)
    val my = (h * (scale - 1f) / 2f).coerceAtLeast(0f)
    return Offset(x.coerceIn(-mx, mx), y.coerceIn(-my, my))
}

/**
 * 严格图片解码器：Markwon 4.6.2 的 DefaultMediaDecoder 在 BitmapFactory 返回 null 时
 * 会构造出空 BitmapDrawable（不抛异常），导致损坏图片/SVG 静默渲染为空白、错误占位图失效。
 */
private class StrictMediaDecoder(private val ctx: Context) : MediaDecoder() {
    override fun supportedTypes(): Collection<String> = emptyList()
    override fun decode(contentType: String?, inputStream: InputStream): Drawable {
        val bitmap = BitmapFactory.decodeStream(inputStream)
            ?: throw IllegalStateException("无法解码图片（格式不支持或文件损坏）：$contentType")
        return BitmapDrawable(ctx.resources, bitmap)
    }
}

/** 图片加载失败说明 */
private fun describeFailure(source: String, hasTree: Boolean): String = when {
    source.startsWith("data:") -> "内联图片解码失败（不支持 SVG，请用 PNG/JPG）"
    source.substringBefore('?').substringBefore('#').endsWith(".svg", ignoreCase = true) ->
        "不支持 SVG 图片：${source.substringAfterLast('/').take(40)}"
    !source.contains("://") && !source.startsWith("/") -> {
        val short = source.substringAfterLast('/').take(40)
        if (hasTree) "找不到图片：$short"
        else "无法加载相对路径图片：$short\n请用「打开文件夹」授权图片所在目录"
    }
    else -> "图片加载失败：${source.substringAfterLast('/').take(40)}"
}

/** 加载失败占位图 */
private class ImageErrorDrawable(private val text: String) : android.graphics.drawable.Drawable() {
    private val bgPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.parseColor("#F0F1F3")
    }
    private val fgPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.parseColor("#9AA4B2")
        textSize = 30f
        isAntiAlias = true
    }

    override fun draw(canvas: android.graphics.Canvas) {
        canvas.drawRect(bounds, bgPaint)
        val textWidth = fgPaint.measureText("⚠ $text")
        val x = ((bounds.width() - textWidth) / 2f).coerceAtLeast(8f)
        canvas.drawText("⚠ $text", x, bounds.height() / 2f + 10, fgPaint)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = android.graphics.PixelFormat.OPAQUE
    override fun getIntrinsicWidth() = 640
    override fun getIntrinsicHeight() = 88
}
