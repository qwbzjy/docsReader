package com.wenbo.docsreader.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wenbo.docsreader.DocumentInfo
import com.wenbo.docsreader.ui.Load
import com.wenbo.docsreader.ui.rememberLoaded
import com.wenbo.docsreader.util.UriUtil

private const val MAX_TEXT_BYTES = 8L * 1024 * 1024 // 8MB 封顶，避免超大文本卡顿

/** 纯文本查看器：自动识别 UTF-8/GBK 编码。 */
@Composable
fun TextViewer(doc: DocumentInfo) {
    val ctx = LocalContext.current
    val load = rememberLoaded(doc.uri) {
        val bytes = UriUtil.readBytes(ctx, doc.uri, MAX_TEXT_BYTES)
        val total = UriUtil.size(ctx, doc.uri)
        UriUtil.decodeSmart(bytes) +
            if (total > MAX_TEXT_BYTES) "\n\n……（文件过大，仅显示前 8MB）" else ""
    }
    when (load) {
        Load.Loading -> Centered { CircularProgressIndicator() }
        is Load.Failure -> Centered { Text("打开失败：${load.message}", color = Color.DarkGray) }
        is Load.Success -> {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.White),
            ) {
                SelectionContainer {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                    ) {
                        if (load.value.isBlank()) {
                            Text("（空文件）", fontSize = 16.sp)
                        } else {
                            Text(
                                text = load.value,
                                fontSize = 16.sp,
                                lineHeight = 24.sp,
                                color = Color(0xFF1F2328),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
