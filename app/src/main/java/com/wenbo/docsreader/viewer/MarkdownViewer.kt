package com.wenbo.docsreader.viewer

import android.util.TypedValue
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.wenbo.docsreader.DocumentInfo
import com.wenbo.docsreader.ui.Load
import com.wenbo.docsreader.ui.rememberLoaded
import com.wenbo.docsreader.util.UriUtil
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin

/** Markdown 查看器：Markwon 原生渲染（表格/删除线/任务列表）。 */
@Composable
fun MarkdownViewer(doc: DocumentInfo) {
    val ctx = LocalContext.current
    val load = rememberLoaded(doc.uri) {
        UriUtil.decodeSmart(UriUtil.readBytes(ctx, doc.uri, 4L * 1024 * 1024))
    }
    when (load) {
        Load.Loading -> Centered { CircularProgressIndicator() }
        is Load.Failure -> Centered { Text("打开失败：${load.message}", color = Color.DarkGray) }
        is Load.Success -> {
            val markwon = remember(ctx) {
                Markwon.builder(ctx)
                    .usePlugin(TablePlugin.create(ctx))
                    .usePlugin(StrikethroughPlugin.create())
                    .usePlugin(TaskListPlugin.create(ctx))
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
                        }
                    },
                    update = { tv -> markwon.setMarkdown(tv, load.value) },
                )
            }
        }
    }
}
