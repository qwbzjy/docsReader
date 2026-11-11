package com.wenbo.docsreader.viewer

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.DocumentsContract
import android.text.Spanned
import android.text.style.URLSpan
import android.util.TypedValue
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.webkit.MimeTypeMap
import android.widget.MediaController
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.core.content.FileProvider
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
import com.wenbo.docsreader.DocType
import com.wenbo.docsreader.DocumentInfo
import com.wenbo.docsreader.ui.Load
import com.wenbo.docsreader.ui.rememberLoaded
import com.wenbo.docsreader.util.TreeResources
import com.wenbo.docsreader.util.UriUtil
import com.wenbo.docsreader.util.ZipUtil
import io.noties.markwon.Markwon
import io.noties.markwon.core.spans.LinkSpan
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.image.AsyncDrawableSpan
import io.noties.markwon.image.ImagesPlugin
import io.noties.markwon.image.MediaDecoder
import io.noties.markwon.image.file.FileSchemeHandler
import java.io.File
import java.io.InputStream
import kotlin.math.abs

/** Markdown 查看器：Markwon 原生渲染（表格/删除线/任务列表/图片/链接）。 */
@Composable
fun MarkdownViewer(doc: DocumentInfo, onOpenDoc: (Uri) -> Unit = {}) {
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
            // 点击视频链接弹出的应用内播放器
            val videoState = remember(doc.uri) { mutableStateOf<VideoTarget?>(null) }
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
                                            // 链接优先：`[![缩略图](thumb.png)](video.mp4)` 这类
                                            // 图片被链接包裹时，点缩略图应播放视频；普通图片仍走预览
                                            val link = linkAt(this, ev.x, ev.y)
                                            if (link != null) {
                                                handleMarkdownLink(c, doc, link, onOpenDoc) {
                                                    videoState.value = it
                                                }
                                                return@setOnTouchListener true
                                            }
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
            VideoLayer(videoState)
        }
    }
}

/**
 * 命中检测：触摸点 (x, y) 落在链接文字上时返回链接目标。
 * Markwon 默认不会给 TextView 设置 MovementMethod，链接本身点不动，
 * 因此和图片一样通过 Layout 映射字符偏移后自行查找 LinkSpan。
 */
private fun linkAt(tv: TextView, x: Float, y: Float): String? {
    val layout = tv.layout ?: return null
    val text = tv.text as? Spanned ?: return null
    val tx = x + tv.scrollX - tv.totalPaddingLeft
    val ty = y + tv.scrollY - tv.totalPaddingTop
    val line = layout.getLineForVertical(ty.toInt())
    // 只做横向裁剪：点在行首之前/行尾之后时 getOffsetForHorizontal 会吸附到首尾字符，
    // 若该字符恰好是链接就会误触。纵向不再要求落在行内——
    // 行间空白（行距）里点击也应命中，否则要精确压到字面上才生效
    if (tx < layout.getLineLeft(line) || tx > layout.getLineRight(line)) return null
    val offset = layout.getOffsetForHorizontal(line, tx)
    if (offset !in 0 until text.length) return null
    // LinkSpan 继承 URLSpan，一次查询即可覆盖 Markwon 链接与普通 URLSpan
    for (span in text.getSpans(0, text.length, URLSpan::class.java)) {
        val start = text.getSpanStart(span)
        if (start < 0) continue
        if (offset in start until text.getSpanEnd(span)) {
            val link = (span as? LinkSpan)?.getLink() ?: span.url
            if (!link.isNullOrBlank()) return link
        }
    }
    return null
}

/**
 * 处理 Markdown 链接：
 * - 视频（本地/网络）用应用内播放器打开
 * - http(s)/mailto/tel 交给系统应用（浏览器/邮件/拨号）
 * - 相对路径链接经文件夹授权解析：文档类应用内打开，其余交给系统
 * - file:// 绝对路径经 FileProvider 分享给系统应用
 */
private fun handleMarkdownLink(
    ctx: Context,
    doc: DocumentInfo,
    raw: String,
    onOpenDoc: (Uri) -> Unit,
    onPlayVideo: (VideoTarget) -> Unit,
) {
    val dest = raw.trim()
    if (dest.isEmpty() || dest.startsWith("#")) return // 页内锚点暂不支持
    when {
        dest.startsWith("file:", true) -> openFileUri(ctx, dest, onPlayVideo)
        dest.startsWith("content:", true) -> {
            val uri = Uri.parse(dest)
            val mime = mimeOf(ctx, uri, uri.lastPathSegment)
            if (isVideo(mime, uri.lastPathSegment)) {
                onPlayVideo(VideoTarget(uri, uri.lastPathSegment ?: "视频"))
            } else {
                openExternal(ctx, uri, mime)
            }
        }
        dest.startsWith("http://", true) || dest.startsWith("https://", true) -> {
            val uri = Uri.parse(dest)
            if (isVideo(uri.lastPathSegment, uri.lastPathSegment)) {
                onPlayVideo(VideoTarget(uri, uri.lastPathSegment ?: "视频"))
            } else {
                openExternal(ctx, uri, null)
            }
        }
        dest.startsWith("mailto:", true) || dest.startsWith("tel:", true) ->
            openExternal(ctx, Uri.parse(dest), null)
        dest.startsWith("data:", true) -> Unit
        TreeResources.hasLoadableScheme(dest) -> Unit
        else -> openRelativeLink(ctx, doc, dest, onOpenDoc, onPlayVideo)
    }
}

/** 相对路径链接：在授权文件夹内定位目标，文档应用内打开，视频应用内播放，其余交给系统。 */
private fun openRelativeLink(
    ctx: Context,
    doc: DocumentInfo,
    dest: String,
    onOpenDoc: (Uri) -> Unit,
    onPlayVideo: (VideoTarget) -> Unit,
) {
    val target = TreeResources.resolveLink(ctx, doc.treeUri, doc.uri, dest)
    if (target == null) {
        toast(
            ctx,
            if (doc.treeUri == null) "无法打开相对路径链接，请先用「打开文件夹」授权链接所在目录"
            else "找不到链接目标：${dest.substringAfterLast('/').take(40)}",
        )
        return
    }
    if (target.mime == DocumentsContract.Document.MIME_TYPE_DIR) {
        toast(ctx, "链接指向文件夹，请在文件夹浏览页进入：${target.name}")
        return
    }
    val mime = target.mime ?: mimeOf(ctx, target.uri, target.name)
    if (isVideo(mime, target.name)) {
        onPlayVideo(VideoTarget(target.uri, target.name))
        return
    }
    if (DocType.detect(target.name, mime) != DocType.UNSUPPORTED) {
        onOpenDoc(target.uri)
        return
    }
    openExternal(ctx, target.uri, mime)
}

/**
 * file:// 链接：
 * 1. 先按普通文件读（应用私有目录 / 旧版存储权限）；
 * 2. 读不到时映射到已授权文件夹的树内文档（Android 10+ 无法直读 /sdcard）；
 * 3. 都不行则提示改用相对路径引用。
 */
private fun openFileUri(ctx: Context, dest: String, onPlayVideo: (VideoTarget) -> Unit) {
    val path = Uri.parse(dest).path ?: run {
        toast(ctx, "无效的文件链接"); return
    }
    val file = File(path)
    if (file.isFile && file.canRead()) {
        // 应用自己可读，视频直接在应用内播放
        if (isVideo(mimeOf(ctx, Uri.fromFile(file), file.name), file.name)) {
            onPlayVideo(VideoTarget(Uri.fromFile(file), file.name))
            return
        }
        val uri = runCatching {
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        }.getOrNull()
        if (uri != null) {
            openExternal(ctx, uri, mimeOf(ctx, uri, file.name))
            return
        }
        toast(ctx, "该路径不在可分享范围内：${file.name}")
        return
    }
    val target = TreeResources.resolveAbsoluteFile(ctx, path)
    if (target == null) {
        toast(
            ctx,
            "无法读取：${file.name}\nAndroid 10+ 应用不能直接读取存储卡路径，" +
                "请把链接改为相对路径并从「打开文件夹」进入",
        )
        return
    }
    val mime = target.mime ?: mimeOf(ctx, target.uri, target.name)
    if (isVideo(mime, target.name)) {
        onPlayVideo(VideoTarget(target.uri, target.name))
        return
    }
    if (DocType.detect(target.name, mime) != DocType.UNSUPPORTED) {
        toast(ctx, "该路径已授权，请重新从文件夹浏览页打开：${target.name}")
        return
    }
    openExternal(ctx, target.uri, mime)
}

/** 是否为应用内可播放的视频（MIME 或常见扩展名） */
private fun isVideo(mime: String?, name: String?): Boolean {
    if (mime?.startsWith("video/") == true) return true
    val ext = name?.substringAfterLast('.', "")?.lowercase() ?: return false
    return ext in VIDEO_EXT
}

private val VIDEO_EXT = setOf(
    "mp4", "m4v", "mov", "webm", "mkv", "avi", "3gp", "3gpp", "flv", "ts", "mpg", "mpeg", "wmv",
)

/** 用系统应用打开外部内容（浏览器 / 视频播放器 / 其他查看器）。 */
private fun openExternal(ctx: Context, uri: Uri, mime: String?) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        if (uri.scheme == "content") setDataAndType(uri, mime ?: "*/*") else data = uri
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (ctx !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // clipData 保证部分播放器在 API < 24 的 intent 授权路径上也能读到数据
        if (uri.scheme == "content") clipData = ClipData.newRawUri(null, uri)
    }
    runCatching { ctx.startActivity(intent) }.onFailure {
        toast(ctx, "没有可打开该内容的应用（${mime ?: uri.scheme}）")
    }
}

/** MIME：优先使用 provider 返回值，其次按扩展名推断 */
private fun mimeOf(ctx: Context, uri: Uri, name: String?): String? =
    runCatching { ctx.contentResolver.getType(uri) }.getOrNull()
        ?: name?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.isNotEmpty() }
            ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }

private fun toast(ctx: Context, message: String) {
    Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
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
        // 行间空白处也算命中（与 linkAt 一致），缩短上下边距让点击更好按
        if (ty < layout.getLineTop(line) - 8 || ty > layout.getLineBottom(line) + 8) continue
        val x0 = layout.getPrimaryHorizontal(start)
        val width = span.drawable.bounds.width()
        if (width <= 0 || tx !in x0..(x0 + width)) continue
        val bitmap = (span.drawable.result as? BitmapDrawable)?.bitmap
        if (bitmap != null) return bitmap
    }
    return null
}

/** 应用内可播放的视频目标 */
private data class VideoTarget(val uri: Uri, val title: String)

/** 预览层：读取状态并在独立组合作用域内展示，避免影响 AndroidView 的 update 重跑 */
@Composable
private fun PreviewLayer(state: MutableState<Bitmap?>) {
    val bitmap = state.value ?: return
    ImagePreview(bitmap) { state.value = null }
}

/**
 * 视频播放层：本地/网络视频在应用内用 VideoView 播放，
 * 避免依赖系统里是否装有第三方播放器。返回键或关闭按钮停止播放。
 */
@Composable
private fun VideoLayer(state: MutableState<VideoTarget?>) {
    val target = state.value ?: return
    val dismiss = { state.value = null }
    BackHandler(enabled = true, onBack = dismiss)
    Dialog(
        onDismissRequest = dismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { c ->
                    VideoView(c).apply {
                        tag = target.uri.toString()
                        setMediaController(MediaController(c).also { it.setAnchorView(this) })
                        setOnPreparedListener { it.start() }
                        setOnErrorListener { _, what, extra ->
                            // 编码不受 MediaPlayer 支持（如 mkv/avi）时，退回系统播放器
                            state.value = null
                            openExternal(c, target.uri, "video/*")
                            toast(c, "内置播放器不支持该编码（$what/$extra），已尝试系统播放器")
                            true
                        }
                        setVideoURI(target.uri)
                    }
                },
                update = { vv ->
                    // 同一播放层内切换视频源时重新加载
                    if (vv.tag != target.uri.toString()) {
                        vv.tag = target.uri.toString()
                        vv.setVideoURI(target.uri)
                    }
                },
                onRelease = { it.stopPlayback() },
            )
            IconButton(
                onClick = dismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.35f), CircleShape),
            ) {
                Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Color.White)
            }
            Text(
                target.title,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 13.sp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(14.dp),
            )
        }
    }
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
