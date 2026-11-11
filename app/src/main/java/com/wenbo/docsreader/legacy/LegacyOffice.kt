package com.wenbo.docsreader.legacy

import com.wenbo.docsreader.ooxml.HtmlShell
import com.wenbo.docsreader.ooxml.HtmlShell.esc
import com.wenbo.docsreader.ooxml.XlsxToHtml
import org.apache.poi.hssf.usermodel.HSSFSheet
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.hwpf.extractor.WordExtractor
import org.apache.poi.poifs.filesystem.DocumentEntry
import org.apache.poi.poifs.filesystem.POIFSFileSystem
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.FormulaEvaluator
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * 旧版二进制 Office 格式只读解析：
 * - .doc  → Apache POI HWPF（文本流提取，不还原复杂排版）
 * - .xls  → Apache POI HSSF（工作表 → HTML 表格，支持数字/日期格式/合并单元格）
 * - .ppt  → 手写 OLE2 记录扫描（PowerPoint 97 text atoms），零 java.awt 依赖
 * 阅读场景够用：内容与结构可读，复杂版式简化为文档流。
 */
object LegacyOffice {

    private const val MAX_ROWS = 2000
    private const val MAX_COLS = 200

    // ---------- .doc (Word 97) ----------

    fun docToHtml(file: File): String =
        file.inputStream().use { ins ->
            WordExtractor(ins).use { we ->
                val sb = StringBuilder()
                for (raw in we.paragraphText) {
                    val text = raw
                        .replace('\u0007', ' ')    // 表格单元标记
                        .replace('\u000B', '\n')   // 软换行
                        .replace("\r", "")
                        .trim()
                    if (text.isEmpty()) continue
                    sb.append("<p>").append(esc(text).replace("\n", "<br/>")).append("</p>")
                }
                if (sb.isEmpty()) {
                    sb.append("<p style=\"color:#98a1ab\">（未提取到文本：可能是纯图片文档或受密码保护）</p>")
                }
                HtmlShell.wrap(sb.toString(), title = "Word 文档（旧版）")
            }
        }

    // ---------- .xls (Excel 97-2003) ----------

    fun xlsToSheets(file: File): List<XlsxToHtml.Sheet> =
        POIFSFileSystem(file).use { fs ->
            HSSFWorkbook(fs).use { wb ->
                val df = DataFormatter()
                val evaluator: FormulaEvaluator? =
                    runCatching { wb.creationHelper.createFormulaEvaluator() }.getOrNull()
                (0 until wb.numberOfSheets).map { i ->
                    XlsxToHtml.Sheet(wb.getSheetName(i), hssfSheetHtml(wb.getSheetAt(i), df, evaluator))
                }
            }
        }

    private fun cellString(cell: Cell?, df: DataFormatter, ev: FormulaEvaluator?): String {
        if (cell == null) return ""
        runCatching {
            return if (ev != null) df.formatCellValue(cell, ev).trim() else df.formatCellValue(cell).trim()
        }
        // 公式求值失败（不支持的函数等）：退回无 evaluator 格式化
        return runCatching { df.formatCellValue(cell).trim() }.getOrDefault("")
    }

    private fun hssfSheetHtml(sheet: HSSFSheet, df: DataFormatter, ev: FormulaEvaluator?): String {
        val values = HashMap<Pair<Int, Int>, String>()
        var minR = Int.MAX_VALUE; var maxR = -1
        var minC = Int.MAX_VALUE; var maxC = -1
        var r = sheet.firstRowNum
        val last = sheet.lastRowNum
        while (r <= last) {
            val row = runCatching { sheet.getRow(r) }.getOrNull()
            if (row != null) {
                val cells = row.lastCellNum.toInt().coerceAtMost(MAX_COLS)
                for (c in 0 until cells) {
                    val v = cellString(runCatching { row.getCell(c) }.getOrNull(), df, ev)
                    if (v.isNotEmpty()) {
                        values[r to c] = v
                        if (r < minR) minR = r
                        if (r > maxR) maxR = r
                        if (c < minC) minC = c
                        if (c > maxC) maxC = c
                    }
                }
            }
            r++
        }
        if (values.isEmpty()) {
            return HtmlShell.wrap("<p>（空工作表）</p>", extraCss = XLS_CSS, title = "Excel（旧版）")
        }

        // 合并单元格
        val mergeTopLeft = HashMap<Pair<Int, Int>, Pair<Int, Int>>()
        val mergeSkip = HashSet<Pair<Int, Int>>()
        val mergedCount = runCatching { sheet.numMergedRegions }.getOrDefault(0)
        for (m in 0 until mergedCount) {
            val cr = runCatching { sheet.getMergedRegion(m) }.getOrNull() ?: continue
            mergeTopLeft[cr.firstRow to cr.firstColumn] =
                (cr.lastRow - cr.firstRow + 1) to (cr.lastColumn - cr.firstColumn + 1)
            for (rr in cr.firstRow..cr.lastRow) {
                for (cc in cr.firstColumn..cr.lastColumn) {
                    if (rr != cr.firstRow || cc != cr.firstColumn) mergeSkip.add(rr to cc)
                }
            }
        }

        val startR = if (maxR - minR + 1 > MAX_ROWS) maxR - MAX_ROWS + 1 else minR
        val startC = if (maxC - minC + 1 > MAX_COLS) maxC - MAX_COLS + 1 else minC

        val sb = StringBuilder()
        if (startR > minR || startC > minC) {
            sb.append("<p style=\"color:#b45309;font-size:13px\">表格过大，仅显示部分区域。</p>")
        }
        sb.append("<table class=\"xlsx\">")
        for (rr in startR..maxR) {
            sb.append("<tr>")
            var cc = startC
            while (cc <= maxC) {
                val key = rr to cc
                if (key in mergeSkip) { cc++; continue }
                var (rs, cs) = mergeTopLeft[key] ?: (1 to 1)
                rs = rs.coerceAtMost(maxR - rr + 1)
                cs = cs.coerceAtMost(maxC - cc + 1)
                sb.append("<td")
                if (cs > 1) sb.append(" colspan=\"$cs\"")
                if (rs > 1) sb.append(" rowspan=\"$rs\"")
                if (rr == startR) sb.append(" class=\"hdr\"")
                sb.append(">").append(esc(values[key] ?: "")).append("</td>")
                cc += cs
            }
            sb.append("</tr>")
        }
        sb.append("</table>")
        return HtmlShell.wrap(sb.toString(), extraCss = XLS_CSS, title = "Excel（旧版）")
    }

    private const val XLS_CSS =
        ".xlsx{border-collapse:collapse;table-layout:auto}" +
            ".xlsx td{border:1px solid #cbd5e1;padding:3px 8px;min-width:28px;white-space:pre-wrap;vertical-align:top}" +
            ".xlsx td.hdr{background:#eef2f7;font-weight:600}"

    // ---------- .ppt (PowerPoint 97-2003) ----------

    // OLE2 流内二进制记录扫描（避免 HSLF 的 java.awt 依赖）
    private const val RT_DOCUMENT = 0x03E8          // 文档容器（标准结构入口）
    private const val RT_SLIDE = 0x03EE             // 单页幻灯片容器（兜底文本源）
    private const val RT_SLWT = 0x0FF0              // SlideListWithText：逐页文本权威来源
    private const val RT_PERSIST_TEXT = 0x03F3      // SLWT 内"下一张幻灯片"分隔标记
    private const val RT_TEXT_CHARS = 0x0FA0        // UTF-16LE 文本
    private const val RT_TEXT_BYTES = 0x0FA8        // 单字节文本

    fun pptToSlides(file: File): List<String> {
        val data = POIFSFileSystem(file).use { fs ->
            val root = fs.root
            val entry = runCatching { root.getEntry("PowerPoint Document") }.getOrNull()
                as? DocumentEntry
                ?: throw IllegalArgumentException("不是有效的 ppt 文件（缺少 PowerPoint Document 流）")
            root.createDocumentInputStream(entry).use { it.readBytes() }
        }
        val slideTexts = pptScan(data)
        if (slideTexts.isEmpty()) throw IllegalArgumentException("未找到幻灯片内容")
        return slideTexts.map { texts ->
            val body = if (texts.isEmpty()) {
                "<p style=\"color:#98a1ab\">（本页无可提取文本，可能为纯图形页）</p>"
            } else {
                texts.mapIndexed { i, t ->
                    val html = esc(t).replace("\n", "<br/>")
                    if (i == 0) "<h2>$html</h2>" else "<p>$html</p>"
                }.joinToString("")
            }
            HtmlShell.wrap(body, title = "PPT（旧版）")
        }
    }

    /** 文本原子解码清洗：去除结束符/控制字符；纯乱码（含控制字符）视为垃圾丢弃 */
    private fun cleanAtom(bytes: ByteArray, utf16: Boolean): String? {
        var s = if (utf16) String(bytes, StandardCharsets.UTF_16LE)
        else String(bytes, runCatching { java.nio.charset.Charset.forName("windows-1252") }
            .getOrDefault(StandardCharsets.ISO_8859_1))
            .replace('\u00A0', ' ')
        s = s.replace("\u0000", "").replace('\u000B', '\n').trimEnd('\r').trim()
        if (s.isEmpty()) return null
        // 允许 \n，其余控制字符视为乱码
        if (s.any { it.isISOControl() && it != '\n' }) return null
        return s
    }

    /**
     * 记录树扫描。返回按页分组的文本；优先 SlideListWithText（权威逐页结构，
     * PersistTextRefAtom 为分页标记），退化时使用 Slide 容器各自收为一页，
     * 最后才整文件收为一页。
     */
    private fun pptScan(data: ByteArray): List<List<String>> {
        val le16 = { i: Int -> (data[i].toInt() and 0xFF) or ((data[i + 1].toInt() and 0xFF) shl 8) }
        val le32 = { i: Int ->
            (data[i].toInt() and 0xFF) or ((data[i + 1].toInt() and 0xFF) shl 8) or
                ((data[i + 2].toInt() and 0xFF) shl 16) or ((data[i + 3].toInt() and 0xFF) shl 24)
        }

        val slwtBuckets = ArrayList<ArrayList<String>>()
        val slideBuckets = ArrayList<List<String>>()

        // —— SlideListWithText：0x03F3 标记分页，文本原子归入当前页 ——
        fun collectSlwt(off: Int, end: Int, depth: Int) {
            if (depth > 8) return
            var i = off
            while (i + 8 <= end) {
                val vi = le16(i)
                val type = le16(i + 2)
                val len = le32(i + 4)
                val body = i + 8
                val bodyEnd = (body + len).coerceAtMost(end)
                if (bodyEnd < body) return
                when {
                    (vi and 0x0F) == 0x0F && type != RT_SLIDE -> collectSlwt(body, bodyEnd, depth + 1)
                    type == RT_PERSIST_TEXT -> slwtBuckets.add(ArrayList())
                    type == RT_TEXT_CHARS || type == RT_TEXT_BYTES -> {
                        val t = cleanAtom(data.copyOfRange(body, bodyEnd), type == RT_TEXT_CHARS)
                        if (t != null) {
                            if (slwtBuckets.isEmpty()) slwtBuckets.add(ArrayList())
                            slwtBuckets.last().add(t)
                        }
                    }
                }
                i = bodyEnd
            }
        }

        // —— 普通容器内收集全部文本（Slide 容器兜底用）——
        fun collectAll(off: Int, end: Int, acc: ArrayList<String>, depth: Int) {
            if (depth > 10) return
            var i = off
            while (i + 8 <= end) {
                val vi = le16(i)
                val type = le16(i + 2)
                val len = le32(i + 4)
                val body = i + 8
                val bodyEnd = (body + len).coerceAtMost(end)
                if (bodyEnd < body) return
                when {
                    (vi and 0x0F) == 0x0F -> collectAll(body, bodyEnd, acc, depth + 1)
                    type == RT_TEXT_CHARS -> cleanAtom(data.copyOfRange(body, bodyEnd), true)?.let { acc.add(it) }
                    type == RT_TEXT_BYTES -> cleanAtom(data.copyOfRange(body, bodyEnd), false)?.let { acc.add(it) }
                }
                i = bodyEnd
            }
        }

        // —— 顶层遍历：流可能直接以多个容器并列（不规范导出），也可能标准地只有一个 Document 容器 ——
        fun walkTop(off: Int, end: Int, depth: Int) {
            if (depth > 3) return
            var i = off
            while (i + 8 <= end) {
                val vi = le16(i)
                val type = le16(i + 2)
                val len = le32(i + 4)
                val body = i + 8
                val bodyEnd = (body + len).coerceAtMost(end)
                if (bodyEnd < body) return
                if ((vi and 0x0F) == 0x0F) {
                    when (type) {
                        RT_SLWT -> collectSlwt(body, bodyEnd, 0)
                        RT_SLIDE -> {
                            val acc = ArrayList<String>()
                            collectAll(body, bodyEnd, acc, 0)
                            if (acc.isNotEmpty()) slideBuckets.add(acc)
                        }
                        RT_DOCUMENT -> walkTop(body, bodyEnd, depth + 1)
                    }
                }
                i = bodyEnd
            }
        }

        walkTop(0, data.size, 0)

        val slwt = slwtBuckets.filter { it.isNotEmpty() }
        if (slwt.isNotEmpty()) return slwt
        if (slideBuckets.isNotEmpty()) return slideBuckets
        val flat = ArrayList<String>()
        collectAll(0, data.size, flat, 0)
        return if (flat.isEmpty()) emptyList() else listOf(flat)
    }
}
