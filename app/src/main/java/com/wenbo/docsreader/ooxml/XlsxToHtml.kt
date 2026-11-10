package com.wenbo.docsreader.ooxml

import org.w3c.dom.Element
import java.time.LocalDate
import java.util.zip.ZipFile
import com.wenbo.docsreader.ooxml.HtmlShell.esc
import com.wenbo.docsreader.ooxml.XmlUtil.attr
import com.wenbo.docsreader.ooxml.XmlUtil.child
import com.wenbo.docsreader.ooxml.XmlUtil.childAll
import com.wenbo.docsreader.ooxml.XmlUtil.children
import com.wenbo.docsreader.ooxml.XmlUtil.localPart

/**
 * xlsx -> 每个工作表一份 HTML 表格（只读展示）。
 * 支持：共享字符串、内联字符串、数字、布尔、日期时间格式、合并单元格。
 */
object XlsxToHtml {

    data class Sheet(val name: String, val html: String)

    private const val MAX_ROWS = 2000
    private const val MAX_COLS = 200

    fun convert(zip: ZipFile): List<Sheet> {
        val wbEntry = zip.getEntry("xl/workbook.xml")
            ?: throw IllegalArgumentException("不是有效的 xlsx 文件（缺少 xl/workbook.xml）")
        val rels = Rels.parse(zip, "xl/_rels/workbook.xml.rels", "xl/")
        val sharedStrings = parseSharedStrings(zip)
        val styles = parseStyles(zip)

        val wb = zip.getInputStream(wbEntry).use { XmlUtil.load(it) }
        val sheets = childAll(child(wb.documentElement, "sheets"), "sheet")

        return sheets.mapNotNull { sheetEl ->
            val name = sheetEl.attr("name") ?: "Sheet"
            val rid = sheetEl.attr("r:id") ?: return@mapNotNull null
            val rel = rels[rid] ?: return@mapNotNull null
            val entry = zip.getEntry(rel.target) ?: return@mapNotNull null
            val xml = zip.getInputStream(entry).use { XmlUtil.load(it) }
            Sheet(name, sheetHtml(xml.documentElement, sharedStrings, styles))
        }
    }

    // ---------- 共享字符串 ----------

    private fun parseSharedStrings(zip: ZipFile): List<String> {
        val entry = zip.getEntry("xl/sharedStrings.xml") ?: return emptyList()
        val doc = zip.getInputStream(entry).use { XmlUtil.load(it) }
        return childAll(doc.documentElement, "si").map { si ->
            // si 要么是单个 t，要么是多段 r/t（富文本），全部拼接
            StringBuilder().apply {
                for (t in collectT(si)) append(t.textContent)
            }.toString()
        }
    }

    private fun collectT(el: Element): List<Element> {
        if (el.localPart() == "t") return listOf(el)
        return children(el).flatMap { collectT(it) }
    }

    // ---------- 样式（数字格式） ----------

    /** cellXfs 索引 -> 是否是日期格式 */
    private class Styles(val xfIsDate: BooleanArray)

    private fun parseStyles(zip: ZipFile): Styles {
        val entry = zip.getEntry("xl/styles.xml") ?: return Styles(BooleanArray(0))
        val doc = zip.getInputStream(entry).use { XmlUtil.load(it) }
        val root = doc.documentElement

        val customDateFmt = HashMap<Int, Boolean>()
        child(root, "numFmts")?.let { nf ->
            for (f in childAll(nf, "numFmt")) {
                val id = f.attr("numFmtId")?.toIntOrNull() ?: continue
                customDateFmt[id] = looksLikeDateFormat(f.attr("formatCode") ?: "")
            }
        }

        val builtinDate = setOf(
            14, 15, 16, 17, 18, 19, 20, 21, 22,
            27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 45, 46, 47, 50, 51, 52, 53, 54, 55, 56, 57, 58
        )

        val xfs = child(root, "cellXfs") ?: return Styles(BooleanArray(0))
        val list = childAll(xfs, "xf").map { xf ->
            val id = xf.attr("numFmtId")?.toIntOrNull() ?: 0
            customDateFmt[id] ?: (id in builtinDate)
        }
        return Styles(list.toBooleanArray())
    }

    /**
     * 粗略判断格式码是否日期/时间：
     * 去掉引号字面量与转义后仍含 y/m/d/h/s 即视为日期格式。
     */
    internal fun looksLikeDateFormat(code: String): Boolean {
        var cleaned = code.replace(Regex("\"[^\"]*\""), "")
            .replace(Regex("\\[[^]]*]"), "")
            .replace(Regex("\\\\."), "")
        // 纯数字/货币/百分比不含日期字母；e 是科学计数，不算
        return cleaned.any { it in "ymdhs" }
    }

    /** Excel 序列值 -> 日期时间文本（1900 日期系统，兼容其虚构的 1900-02-29） */
    internal fun serialToText(serial: Double): String {
        if (serial < 0) return serial.toString()
        val days = if (serial < 1) 0L else kotlin.math.floor(serial).toLong()
        val frac = serial - days
        val date: LocalDate? = when {
            days == 0L -> null // 纯时间
            days in 1..59 -> LocalDate.of(1900, 1, 1).plusDays(days - 1)
            days == 60L -> LocalDate.of(1900, 2, 28) // Excel 的虚构日期
            else -> LocalDate.of(1899, 12, 30).plusDays(days)
        }
        val totalSec = Math.round(frac * 86400.0).toInt() % 86400
        val time = if (totalSec > 0 || date == null) {
            String.format(
                "%02d:%02d:%02d",
                totalSec / 3600, (totalSec % 3600) / 60, totalSec % 60,
            )
        } else ""
        return when {
            date != null && time.isNotEmpty() -> "$date $time"
            date != null -> date.toString()
            else -> time
        }
    }

    // ---------- 工作表 ----------

    private class Cell(val col: Int, val display: String)

    private fun sheetHtml(wsRoot: Element, shared: List<String>, styles: Styles): String {
        val sheetData = child(wsRoot, "sheetData") ?: return "<p>（空工作表）</p>"
        val rows = childAll(sheetData, "row")

        data class RawRow(val rowNum: Int, val cells: List<Cell>)
        val rawRows = ArrayList<RawRow>(rows.size)
        var maxRow = 0
        var maxCol = -1
        var minRow = Int.MAX_VALUE
        var minCol = Int.MAX_VALUE

        for (rowEl in rows) {
            val rowNum = rowEl.attr("r")?.toIntOrNull() ?: (rawRows.size + 1)
            val cells = ArrayList<Cell>()
            for (c in childAll(rowEl, "c")) {
                val col = refCol(c.attr("r")) ?: (cells.size)
                val text = cellText(c, shared, styles)
                if (text.isNotEmpty()) {
                    cells.add(Cell(col, text))
                    if (col > maxCol) maxCol = col
                    if (col < minCol) minCol = col
                }
            }
            if (cells.isNotEmpty()) {
                rawRows.add(RawRow(rowNum, cells))
                if (rowNum > maxRow) maxRow = rowNum
                if (rowNum < minRow) minRow = rowNum
            }
        }

        if (rawRows.isEmpty()) return "<p>（空工作表）</p>"
        if (minRow == Int.MAX_VALUE) { minRow = 1; maxRow = 1; minCol = 0; maxCol = 0 }

        // 合并单元格
        val mergeTopLeft = HashMap<Pair<Int, Int>, Pair<Int, Int>>() // (row,col) -> (rowspan, colspan)
        val mergeSkip = HashSet<Pair<Int, Int>>()
        child(wsRoot, "mergeCells")?.let { mc ->
            for (m in childAll(mc, "mergeCell")) {
                val ref = m.attr("ref") ?: continue
                val parts = ref.split(":")
                if (parts.size != 2) continue
                val (r1, c1) = parseRef(parts[0]) ?: continue
                val (r2, c2) = parseRef(parts[1]) ?: continue
                mergeTopLeft[r1 to c1] = (r2 - r1 + 1) to (c2 - c1 + 1)
                for (r in r1..r2) for (c in c1..c2) if (r != r1 || c != c1) mergeSkip.add(r to c)
            }
        }

        // 裁剪过大区域
        var startRow = minRow
        var startCol = minCol
        if (maxRow - startRow + 1 > MAX_ROWS) startRow = maxRow - MAX_ROWS + 1
        if (maxCol - startCol + 1 > MAX_COLS) startCol = maxCol - MAX_COLS + 1

        val rowMap = rawRows.associateBy { it.rowNum }
        val sb = StringBuilder()
        val truncated = startRow > minRow || startCol > minCol
        if (truncated) {
            sb.append("<p style=\"color:#b45309;font-size:13px\">表格过大，仅显示第 ")
              .append(startRow).append(" 行、第 ").append(refColName(startCol)).append(" 列起的区域。</p>")
        }

        sb.append("<table class=\"xlsx\">")
        val iterRowMap = rowMap.toSortedMap()
        // 逐行逐列生成，支持合并单元格跳格
        var r = startRow
        while (r <= maxRow) {
            sb.append("<tr>")
            var c = startCol
            while (c <= maxCol) {
                val key = r to c
                if (key in mergeSkip) { c++; continue }
                val cells = iterRowMap[r]?.cells ?: emptyList()
                val cell = cells.firstOrNull { it.col == c }
                val (rs, cs) = mergeTopLeft[key] ?: (1 to 1)
                val rowspan = if (rs > 1) " rowspan=\"$rs\"" else ""
                val colspan = if (cs > 1) " colspan=\"$cs\"" else ""
                val cls = if (r == startRow) " class=\"hdr\"" else ""
                val text = if (cell != null) esc(cell.display) else ""
                sb.append("<td$colspan$rowspan$cls>$text</td>")
                c += cs
            }
            sb.append("</tr>")
            r++
        }
        sb.append("</table>")
        return HtmlShell.wrap(
            sb.toString(),
            extraCss = ".xlsx{border-collapse:collapse;table-layout:auto}" +
                ".xlsx td{border:1px solid #cbd5e1;padding:3px 8px;min-width:28px;white-space:pre-wrap;vertical-align:top}" +
                ".xlsx td.hdr{background:#eef2f7;font-weight:600;position:sticky;top:0}",
            title = "Excel",
        )
    }

    private fun cellText(c: Element, shared: List<String>, styles: Styles): String {
        val type = c.attr("t") ?: "n"
        val v = child(c, "v")
        val styleIdx = c.attr("s")?.toIntOrNull()
        return when (type) {
            "s" -> {
                val idx = v?.textContent?.trim()?.toIntOrNull() ?: return ""
                shared.getOrNull(idx) ?: ""
            }
            "inlineStr" -> {
                val isEl = child(c, "is") ?: return ""
                collectT(isEl).joinToString("") { it.textContent }
            }
            "str", "e" -> v?.textContent?.trim() ?: ""
            "b" -> when (v?.textContent?.trim()) {
                "1", "true" -> "TRUE"
                "0", "false" -> "FALSE"
                else -> ""
            }
            else -> { // 数字
                val raw = v?.textContent?.trim() ?: return ""
                val num = raw.toDoubleOrNull() ?: return raw
                val isDate = styleIdx != null && styleIdx < styles.xfIsDate.size && styles.xfIsDate[styleIdx]
                if (isDate && num in 1.0..2958465.9999) serialToText(num) else formatNumber(num)
            }
        }
    }

    private fun formatNumber(num: Double): String {
        return if (num == kotlin.math.floor(num) && kotlin.math.abs(num) < 1e15) {
            num.toLong().toString()
        } else {
            // 保留原始精度，去掉浮点尾差
            var s = num.toString()
            if (s.contains('E')) s = num.toBigDecimal().toPlainString()
            s.trimEnd('0').trimEnd('.')
                .ifEmpty { "0" }
        }
    }

    /** "AB12" -> 列号(0基) */
    internal fun refCol(ref: String?): Int? {
        if (ref == null) return null
        var col = 0
        var seen = false
        for (ch in ref) {
            if (ch in 'A'..'Z') { col = col * 26 + (ch - 'A' + 1); seen = true }
            else if (ch in 'a'..'z') { col = col * 26 + (ch - 'a' + 1); seen = true }
            else break
        }
        return if (seen) col - 1 else null
    }

    /** "AB12" -> (行号1基, 列号0基) */
    private fun parseRef(ref: String): Pair<Int, Int>? {
        val m = Regex("^\\$?([A-Za-z]+)\\$?(\\d+)\$").find(ref) ?: return null
        val col = refCol(m.groupValues[1]) ?: return null
        val row = m.groupValues[2].toIntOrNull() ?: return null
        return row to col
    }

    private fun refColName(col0: Int): String {
        var n = col0 + 1
        val sb = StringBuilder()
        while (n > 0) {
            val rem = (n - 1) % 26
            sb.insert(0, ('A' + rem))
            n = (n - 1) / 26
        }
        return sb.toString()
    }
}
