package com.wenbo.docsreader.ooxml

import org.w3c.dom.Element
import java.util.zip.ZipFile
import com.wenbo.docsreader.ooxml.HtmlShell.esc
import com.wenbo.docsreader.ooxml.XmlUtil.attr
import com.wenbo.docsreader.ooxml.XmlUtil.attrBool
import com.wenbo.docsreader.ooxml.XmlUtil.child
import com.wenbo.docsreader.ooxml.XmlUtil.childAll
import com.wenbo.docsreader.ooxml.XmlUtil.children
import com.wenbo.docsreader.ooxml.XmlUtil.localPart

/**
 * docx -> HTML（只读展示）。
 * 支持：标题、加粗/斜体/下划线/删除线/上下标、段落对齐与缩进、项目符号、
 * 表格（含 gridSpan/vMerge 合并单元格）、超链接、内嵌图片、分页符。
 */
object DocxToHtml {

    fun convert(zip: ZipFile): String {
        val entry = zip.getEntry("word/document.xml")
            ?: throw IllegalArgumentException("不是有效的 docx 文件（缺少 word/document.xml）")
        val rels = Rels.parse(zip, "word/_rels/document.xml.rels", "word/")
        val doc = zip.getInputStream(entry).use { XmlUtil.load(it) }
        val body = child(doc.documentElement, "body")

        val html = StringBuilder()
        var listOpen = false
        for (el in children(body)) {
            val block = blockHtml(el, rels) ?: continue
            val isListItem = el.localPart() == "p" && isListParagraph(el)
            if (isListItem) {
                if (!listOpen) { html.append("<ul>"); listOpen = true }
                html.append(block)
            } else {
                if (listOpen) { html.append("</ul>"); listOpen = false }
                html.append(block)
            }
        }
        if (listOpen) html.append("</ul>")

        return HtmlShell.wrap(
            html.toString(),
            extraCss = "table.docx{border-collapse:collapse;width:100%}" +
                "table.docx td{border:1px solid #b8c2cf;padding:4px 8px;vertical-align:top}",
            title = "Word 文档",
        )
    }

    /** 列表段落：显式 numPr，或使用 List Bullet / List Number 样式（编号定义在 style 级） */
    private fun isListParagraph(p: Element): Boolean {
        val pPr = child(p, "pPr")
        if (child(pPr, "numPr") != null) return true
        val style = child(pPr, "pStyle")?.attr("w:val") ?: return false
        return style.replace(" ", "").matches(Regex("(?i)^list(bullet|number(ed)?)?$"))
    }

    private fun blockHtml(el: Element, rels: Map<String, Rels.Rel>): String? =
        when (el.localPart()) {
            "p" -> paragraphHtml(el, rels)
            "tbl" -> tableHtml(el, rels)
            "sdt" -> children(child(el, "sdtContent")).mapNotNull { blockHtml(it, rels) }.joinToString("")
            else -> null
        }

    // ---------- 段落 ----------

    private fun paragraphHtml(p: Element, rels: Map<String, Rels.Rel>): String {
        val pPr = child(p, "pPr")
        val style = child(pPr, "pStyle")?.attr("w:val") ?: ""
        val hLevel = headingLevel(style)

        val inner = inlineHtml(p, rels)
        if (inner.isBlank() && hLevel == null && isListParagraph(p).not()) {
            return "" // 跳过空段落，减少留白
        }

        val align = child(pPr, "jc")?.attr("w:val")
        val ind = child(pPr, "ind")
        val leftTwips = (ind?.attr("w:start") ?: ind?.attr("w:left"))?.toDoubleOrNull()

        val styleAttr = StringBuilder()
        if (align == "center") styleAttr.append("text-align:center;")
        if (align == "right") styleAttr.append("text-align:right;")
        if (leftTwips != null && leftTwips > 0) {
            styleAttr.append("margin-left:${"%.1f".format(leftTwips / 1440 * 72)}pt;")
        }

        val attr = if (styleAttr.isEmpty()) "" else " style=\"$styleAttr\""
        return when {
            hLevel != null -> "<h$hLevel$attr>$inner</h$hLevel>"
            isListParagraph(p) -> "<li$attr>$inner</li>"
            else -> "<p$attr>$inner</p>"
        }
    }

    private fun headingLevel(style: String): Int? {
        Regex("(?i)(heading|标题)\\s*([1-6])").find(style)?.let {
            return it.groupValues[2].toIntOrNull()
        }
        if (style.matches(Regex("[1-6]"))) return style.toInt()
        return null
    }

    /** 段落内联内容；跳过 pPr 等属性节点 */
    private fun inlineHtml(parent: Element, rels: Map<String, Rels.Rel>): String {
        val sb = StringBuilder()
        for (el in children(parent)) {
            when (el.localPart()) {
                "pPr", "proofErr", "bookmarkStart", "bookmarkEnd", "commentRangeStart",
                "commentRangeEnd", "sectPr", "pPrChange", "rPr", "permStart", "permEnd" -> {}
                "r" -> sb.append(runHtml(el, rels))
                "hyperlink" -> {
                    val text = inlineHtml(el, rels)
                    if (text.isNotEmpty()) {
                        val rel = el.attr("r:id")?.let { rels[it] }
                        if (rel != null && rel.external) {
                            sb.append("<a href=\"${esc(rel.target)}\">").append(text).append("</a>")
                        } else sb.append(text)
                    }
                }
                "drawing", "pict" -> sb.append(imageHtml(el, rels))
                "smartTag", "fldSimple", "ins", "oMath", "sdt", "sdtContent", "dir", "bdo" ->
                    sb.append(inlineHtml(el, rels))
                "del" -> {} // 修订删除内容不显示
            }
        }
        return sb.toString()
    }

    private fun runHtml(r: Element, rels: Map<String, Rels.Rel>): String {
        val rPr = child(r, "rPr")
        val content = StringBuilder()
        for (el in children(r)) {
            when (el.localPart()) {
                "rPr" -> {}
                "t" -> content.append(esc(el.textContent))
                "tab" -> content.append("&#160;&#160;&#160;")
                "br", "cr" -> content.append(
                    if (el.attr("w:type") == "page") "<div class=\"page-break\"></div>" else "<br/>"
                )
                "noBreakHyphen" -> content.append("-")
                "sym" -> content.append("&#9633;")
                "drawing", "pict" -> content.append(imageHtml(el, rels))
            }
        }
        var text = content.toString()
        if (text.isEmpty()) return ""
        if (rPr != null) {
            if (docFlag(rPr, "i")) text = "<em>$text</em>"
            if (docFlag(rPr, "b")) text = "<strong>$text</strong>"
            if (docFlag(rPr, "u")) text = "<u>$text</u>"
            if (docFlag(rPr, "strike") || docFlag(rPr, "dstrike")) text = "<s>$text</s>"
            if (child(rPr, "vertAlign")?.attr("w:val") == "superscript") text = "<sup>$text</sup>"
            if (child(rPr, "vertAlign")?.attr("w:val") == "subscript") text = "<sub>$text</sub>"
            val color = child(rPr, "color")?.attr("w:val")
            if (color != null && color.matches(Regex("[0-9a-fA-F]{6}")) && color != "auto") {
                text = "<span style=\"color:#$color\">$text</span>"
            }
        }
        return text
    }

    /** WordprocessingML 格式标志：<w:b w:val="false"/> 关闭；无 w:val 或值非 0/false/none 视为开启 */
    private fun docFlag(rPr: Element?, name: String): Boolean {
        val f = rPr?.let { child(it, name) } ?: return false
        val v = f.attr("w:val") ?: return true
        return v != "0" && !v.equals("false", ignoreCase = true) && v != "none"
    }

    private fun imageHtml(el: Element, rels: Map<String, Rels.Rel>): String {
        // w:drawing -> ... -> a:blip/@r:embed
        val embed = findBlipEmbed(el) ?: return ""
        val rel = rels[embed] ?: return ""
        // 外部链接原样使用；内部图片是相对解压根目录的 zip 路径，
        // 通过 WebView 的 baseUrl 定位到 word/media/xxx.png
        return if (rel.external) {
            "<img src=\"${esc(rel.target)}\"/>"
        } else {
            "<img src=\"${esc(rel.target)}\"/>"
        }
    }

    private fun findBlipEmbed(el: Element): String? {
        if (el.localPart() == "blip") {
            el.attr("r:embed")?.let { return it }
            el.attr("r:link")?.let { return it }
        }
        for (c in children(el)) findBlipEmbed(c)?.let { return it }
        return null
    }

    // ---------- 表格 ----------

    private fun tableHtml(tbl: Element, rels: Map<String, Rels.Rel>): String {
        val rows = childAll(tbl, "tr")
        if (rows.isEmpty()) return ""
        // 第一遍：解析每行单元格的起始列
        data class CellInfo(val tc: Element, val col: Int, val colspan: Int, val vmerge: String?)
        val gridBefore = Regex("^(\\d+)$")
        val matrix: List<List<CellInfo>> = rows.map { tr ->
            val infos = ArrayList<CellInfo>()
            var col = 0
            for (tc in children(tr)) {
                if (tc.localPart() != "tc") continue
                val tcPr = child(tc, "tcPr")
                // w:before？ 处理单元格左侧跨列占位: gridBefore
                val gb = tcPr?.let { child(it, "gridBefore")?.attr("w:val") }?.let { gridBefore.find(it)?.value?.toIntOrNull() }
                if (gb != null && gb > 0) col += gb
                val span = (tcPr?.let { child(it, "gridSpan")?.attr("w:val") }?.toIntOrNull()) ?: 1
                val vm = tcPr?.let { child(it, "vMerge") }
                val vmerge = vm?.attr("w:val") ?: if (vm != null) "continue" else null
                infos.add(CellInfo(tc, col, span, vmerge))
                col += span
            }
            infos
        }

        // 第二遍：计算 vMerge 起始单元格的 rowspan，并标记需要跳过的续接单元格
        val skip = HashSet<CellInfo>()
        val rowspan = HashMap<CellInfo, Int>()
        val colCount = matrix.maxOfOrNull { row -> row.maxOfOrNull { it.col + it.colspan } ?: 0 } ?: 0
        for (c in 0 until colCount) {
            var start: CellInfo? = null
            for (row in matrix) {
                val cell = row.firstOrNull { it.col == c }
                when {
                    cell == null -> { start?.let { rowspan[it] = (rowspan[it] ?: 1) }; }
                    cell.vmerge == null -> { start = null }
                    cell.vmerge == "restart" -> {
                        start = cell
                        rowspan[cell] = 1
                    }
                    cell.vmerge == "continue" -> {
                        if (start != null) rowspan[start] = (rowspan[start] ?: 1) + 1
                        skip.add(cell)
                    }
                }
            }
        }

        val sb = StringBuilder("<table class=\"docx\">")
        for (row in matrix) {
            sb.append("<tr>")
            for (info in row) {
                if (info in skip) continue
                val cs = if (info.colspan > 1) " colspan=\"${info.colspan}\"" else ""
                val rs = rowspan[info]?.takeIf { it > 1 }?.let { " rowspan=\"$it\"" } ?: ""
                val content = children(info.tc)
                    .mapNotNull { blockHtml(it, rels) }
                    .joinToString("")
                sb.append("<td$cs$rs>$content</td>")
            }
            sb.append("</tr>")
        }
        sb.append("</table>")
        return sb.toString()
    }
}
