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
                ext == "docx" -> WORD
                ext == "xlsx" -> EXCEL
                ext in setOf("pptx", "pptm") -> PPT
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
                else -> UNSUPPORTED
            }
        }
    }
}

/** 一个待打开的文档。 */
data class DocumentInfo(
    val uri: android.net.Uri,
    val name: String,
    val size: Long,
    val type: DocType,
)
