package com.wenbo.docsreader.ooxml

/** 生成最终展示用的 HTML 文档（含公共 CSS）。 */
object HtmlShell {

    const val BASE_CSS = """
        body { margin: 0; padding: 12px 14px; font-family: sans-serif;
               font-size: 15px; line-height: 1.55; color: #1f2328;
               -webkit-text-size-adjust: 100%; word-wrap: break-word; }
        img { max-width: 100%; height: auto; }
        table { border-collapse: collapse; }
        h1 { font-size: 1.6em; } h2 { font-size: 1.4em; } h3 { font-size: 1.25em; }
        h4 { font-size: 1.1em; } h5 { font-size: 1em; } h6 { font-size: 0.95em; }
        p { margin: 0.6em 0; }
        a { color: #1a6bff; }
        .page-break { border-top: 1.5px dashed #9aa4b2; margin: 1.2em 0; }
    """

    fun wrap(bodyHtml: String, extraCss: String = "", title: String = ""): String {
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
            (if (title.isNotEmpty()) "<title>${esc(title)}</title>" else "") +
            "<style>$BASE_CSS$extraCss</style></head><body>$bodyHtml</body></html>"
    }

    /** 不带 viewport meta：PPT 整页按幻灯片宽度输出，
     *  配合 WebView useWideViewPort + loadWithOverviewMode 自动缩放到屏宽。 */
    fun wrapNoViewport(bodyHtml: String, extraCss: String = "", title: String = ""): String {
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
            (if (title.isNotEmpty()) "<title>${esc(title)}</title>" else "") +
            "<style>html,body{margin:0;padding:0}$extraCss</style></head><body>$bodyHtml</body></html>"
    }

    fun esc(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (c in s) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&#39;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }
}
