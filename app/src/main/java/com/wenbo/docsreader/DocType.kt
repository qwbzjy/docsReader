package com.wenbo.docsreader

/**
 * 根据文件名扩展名和 MIME 类型判断文档类型。
 */
enum class DocType {
    MARKDOWN, PDF, WORD, EXCEL, PPT, HTML, TEXT, UNSUPPORTED;

    companion object {
        fun detect(fileName: String, mimeType: String?): DocType {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            return when {
                ext in setOf("md", "markdown", "mdown", "mkd") -> MARKDOWN
                ext == "pdf" -> PDF
                ext == "docx" || ext == "doc" -> WORD
                ext == "xlsx" || ext == "xls" -> EXCEL
                ext in setOf("pptx", "pptm", "ppt", "pps", "pot") -> PPT
                ext in setOf("html", "htm") -> HTML
                ext in setOf(
                    "txt", "log", "json", "xml", "csv", "tsv", "yaml", "yml",
                    "ini", "conf", "properties", "code",
                    "kt", "java", "py", "js", "ts", "c", "cpp", "h", "go", "rs", "sh", "sql", "toml"
                ) -> TEXT
                // 扩展名不可靠时回退到 MIME 判断
                mimeType == "application/pdf" -> PDF
                mimeType == "text/markdown" -> MARKDOWN
                mimeType in setOf(
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.template"
                ) -> WORD
                mimeType in setOf(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.template"
                ) -> EXCEL
                mimeType in setOf(
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    "application/vnd.openxmlformats-officedocument.presentationml.slideshow"
                ) -> PPT
                mimeType == "text/html" -> HTML
                mimeType != null && (mimeType.startsWith("text/") || mimeType in setOf("application/json", "application/xml")) -> TEXT
                // 旧版 MIME
                mimeType == "application/msword" -> WORD
                mimeType == "application/vnd.ms-excel" -> EXCEL
                mimeType == "application/vnd.ms-powerpoint" -> PPT
                else -> UNSUPPORTED
            }
        }
    }
}

/** 一个待打开的文档。treeUri 非空表示来自"文件夹授权"，支持解析相对路径资源。 */
data class DocumentInfo(
    val uri: android.net.Uri,
    val name: String,
    val size: Long,
    val type: DocType,
    val treeUri: android.net.Uri? = null,
)
