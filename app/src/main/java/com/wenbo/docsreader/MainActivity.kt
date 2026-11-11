package com.wenbo.docsreader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wenbo.docsreader.ui.DocsReaderTheme
import com.wenbo.docsreader.ui.Load
import com.wenbo.docsreader.ui.rememberLoaded
import com.wenbo.docsreader.util.TreeEntry
import com.wenbo.docsreader.util.UriUtil
import com.wenbo.docsreader.viewer.HtmlViewer
import com.wenbo.docsreader.viewer.MarkdownViewer
import com.wenbo.docsreader.viewer.OfficeViewer
import com.wenbo.docsreader.viewer.PdfViewer
import com.wenbo.docsreader.viewer.TextViewer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val vm: ReaderViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DocsReaderTheme {
                AppRoot(vm)
            }
        }
        handleViewIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleViewIntent(intent)
    }

    /** 处理其他应用"用本应用打开"的跳转。 */
    private fun handleViewIntent(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> runCatching {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            }.getOrNull()
            else -> null
        }
        if (uri != null) vm.openFromPicker(uri)
    }
}

@Composable
fun AppRoot(vm: ReaderViewModel) {
    val doc = vm.doc
    val tree = vm.browseTree
    when {
        doc != null -> ViewerScreen(vm, doc)
        tree != null -> FolderBrowserScreen(vm, tree)
        else -> HomeScreen(vm)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: ReaderViewModel) {
    val treePicker = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) vm.openTree(uri)
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    TextButton(onClick = { treePicker.launch(null) }) {
                        Icon(
                            Icons.Filled.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.open_folder), fontSize = 14.sp)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (vm.recents.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "点击右上角「打开文件夹」选择文档目录\n支持 Markdown / PDF / Word / Excel / PPT / 文本\nMarkdown 相对路径图片需从文件夹内打开",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        Text(
                            stringResource(R.string.recent_files),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                    items(vm.recents, key = { it.uri.toString() }) { item ->
                        RecentRow(
                            item,
                            onOpen = { vm.openDocument(item.uri, item.treeUri) },
                            onDelete = { vm.removeRecent(item.uri) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun RecentRow(item: DocumentInfo, onOpen: () -> Unit, onDelete: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val dateText = UriUtil.formatDate(UriUtil.lastModified(ctx, item.uri))
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clickable(onClick = onOpen),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TypeBadge(item.type)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.name,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    listOf(UriUtil.formatSize(item.size), dateText)
                        .filter { it.isNotEmpty() }
                        .joinToString(" · "),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "移除", tint = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable
fun TypeBadge(type: DocType) {
    val (text, color) = when (type) {
        DocType.MARKDOWN -> "MD" to Color(0xFF1A7F37)
        DocType.PDF -> "PDF" to Color(0xFFD1242F)
        DocType.WORD -> "DOC" to Color(0xFF2B579A)
        DocType.EXCEL -> "XLS" to Color(0xFF217346)
        DocType.PPT -> "PPT" to Color(0xFFD24726)
        DocType.HTML -> "HTML" to Color(0xFF7C3AED)
        DocType.TEXT -> "TXT" to Color(0xFF57606A)
        DocType.UNSUPPORTED -> "?" to Color(0xFF9199A1)
    }
    Box(
        Modifier
            .size(44.dp)
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(vm: ReaderViewModel, doc: DocumentInfo) {
    androidx.activity.compose.BackHandler { vm.closeDoc() }
    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { vm.closeDoc() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                title = {
                    Text(
                        doc.name,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (doc.type) {
                DocType.MARKDOWN -> MarkdownViewer(doc)
                DocType.PDF -> PdfViewer(doc)
                DocType.WORD, DocType.EXCEL, DocType.PPT -> OfficeViewer(doc)
                DocType.HTML -> HtmlViewer(doc)
                DocType.TEXT -> TextViewer(doc)
                DocType.UNSUPPORTED -> UnsupportedView(doc)
            }
        }
    }
}

@Composable
fun UnsupportedView(doc: DocumentInfo) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                Icons.Filled.Description,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "暂不支持该格式",
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "目前支持阅读：Markdown (.md)、PDF (.pdf)、Word (.docx/.doc)、Excel (.xlsx/.xls)、" +
                    "PPT (.pptx/.ppt)、HTML (.html/.htm)、纯文本 (.txt/.json/.xml/.csv 等)。\n" +
                    "旧版 .doc/.xls/.ppt 以纯文本/表格形式阅读（复杂排版简化）。",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 文件夹浏览页：基于 SAF 树授权列目录。
 * 从授权根开始逐级进入，点击文档直接打开（带来源树，
 * Markdown 可解析同目录相对路径图片）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderBrowserScreen(vm: ReaderViewModel, treeUri: Uri) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val rootDocId = remember(treeUri) { DocumentsContract.getTreeDocumentId(treeUri) }
    var path by remember(treeUri) { mutableStateOf(listOf(rootDocId)) }
    val current = path.last()

    BackHandler(enabled = path.size > 1) { path = path.dropLast(1) }

    val load = rememberLoaded(treeUri to current) {
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            com.wenbo.docsreader.util.TreeResources.listChildren(ctx, treeUri, current)
        }
    }

    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                navigationIcon = {
                    IconButton(onClick = {
                        if (path.size > 1) path = path.dropLast(1) else vm.closeBrowser()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                title = {
                    Text(
                        current.substringAfterLast('/').substringAfterLast(':').ifBlank { "文件夹" },
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val l = load) {
                Load.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                is Load.Failure -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("读取目录失败：${l.message}", color = MaterialTheme.colorScheme.error)
                }
                is Load.Success -> {
                    if (l.value.isEmpty()) {
                        Box(Modifier.fillMaxSize(), Alignment.Center) {
                            Text("（空文件夹）", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(l.value, key = { it.docId }) { entry ->
                                BrowserRow(entry) {
                                    if (entry.isDir) {
                                        path = path + entry.docId
                                    } else {
                                        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, entry.docId)
                                        vm.openDocument(docUri, treeUri)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BrowserRow(entry: TreeEntry, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (entry.isDir) {
                Box(
                    Modifier.size(44.dp).background(
                        Color(0xFF1A6BFF).copy(alpha = 0.10f), RoundedCornerShape(8.dp),
                    ),
                    contentAlignment = Alignment.Center,
                ) { Text("📁", fontSize = 18.sp) }
            } else {
                TypeBadge(DocType.detect(entry.name, entry.mime))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.name, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (!entry.isDir && entry.size >= 0) {
                    Text(
                        UriUtil.formatSize(entry.size),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (entry.isDir) {
                Text("›", fontSize = 20.sp, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}
