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
 * pptx -> 每页幻灯片一份 HTML（只读展示）。
 * 支持：文本框（字体大小/颜色/加粗/斜体/对齐/项目符号）、表格、图片，
 * 形状位置按 EMU 坐标换算为百分比绝对定位；组合形状会应用组合变换；
 * 无显式位置的占位符从对应 slideLayout 继承，再退化为默认版式。
 */
object PptxToHtml {

    data class Slide(val index: Int, val html: String)

    private const val EMU_PER_PX = 9525.0 // 96dpi

    private class Theme(val colors: Map<String, String>)
    private class Xf(val x: Double, val y: Double, val cx: Double, val cy: Double)
    /** 局部坐标 -> 幻灯片坐标的仿射变换（纯平移+缩放） */
    private class Transform(val tx: Double, val ty: Double, val sx: Double, val sy: Double) {
        fun mapX(v: Double) = tx + sx * v
        fun mapY(v: Double) = ty + sy * v
    }

    fun convert(zip: ZipFile): List<Slide> {
        val presEntry = zip.getEntry("ppt/presentation.xml")
            ?: throw IllegalArgumentException("不是有效的 pptx 文件（缺少 ppt/presentation.xml）")
        val presRels = Rels.parse(zip, "ppt/_rels/presentation.xml.rels", "ppt/")
        val theme = parseTheme(zip)

        val pres = zip.getInputStream(presEntry).use { XmlUtil.load(it) }
        val sldSz = child(pres.documentElement, "sldSz")
        val slideCx = sldSz?.attr("cx")?.toDoubleOrNull() ?: 9144000.0
        val slideCy = sldSz?.attr("cy")?.toDoubleOrNull() ?: 6858000.0

        // 幻灯片顺序：sldIdLst -> r:id -> rels target
        val idLst = child(pres.documentElement, "sldIdLst")
        val ordered = idLst?.let { lst ->
            childAll(lst, "sldId").mapNotNull { id ->
                id.attr("r:id")?.let { rid -> presRels[rid]?.let { it.target } }
            }
        }.orEmpty()
        val slidePaths = ordered.ifEmpty {
            // 兜底：按编号排序
            zip.entries().asSequence()
                .filter { it.name.matches(Regex("ppt/slides/slide\\d+\\.xml")) }
                .map { it.name }
                .sortedBy { Regex("slide(\\d+)\\.xml").find(it)!!.groupValues[1].toInt() }
                .toList()
        }

        return slidePaths.mapIndexedNotNull { idx, path ->
            val entry = zip.getEntry(path) ?: return@mapIndexedNotNull null
            val baseDir = path.substringBeforeLast('/') + "/"
            val rels = Rels.parse(zip, baseDir + "_rels/" + path.substringAfterLast('/') + ".rels", baseDir)
            val slideRoot = zip.getInputStream(entry).use { XmlUtil.load(it) }

            // 版式默认占位符位置（部分占位符形状自身没有坐标，需要从 slideLayout 继承）
            val layout = rels.values.firstOrNull { it.target.contains("slideLayout") }
                ?.let { rel -> zip.getEntry(rel.target)?.let { zip.getInputStream(it) }?.use { XmlUtil.load(it) } }

            val spTree = child(child(slideRoot.documentElement, "cSld"), "spTree")
                ?: return@mapIndexedNotNull Slide(idx + 1, wrapSlide(emptyList(), slideCx, slideCy))
            val boxes = ArrayList<String>()
            walkShapes(spTree, Transform(0.0, 0.0, 1.0, 1.0), slideCx, slideCy, rels, theme, layout, boxes)
            Slide(idx + 1, wrapSlide(boxes, slideCx, slideCy))
        }
    }

    private fun wrapSlide(boxes: List<String>, cx: Double, cy: Double): String {
        val pxW = cx / EMU_PER_PX
        val pxH = cy / EMU_PER_PX
        val body = boxes.joinToString("\n")
        // 不用 viewport meta：配合 WebView 的 useWideViewPort + overview 模式整页缩放适配屏宽
        return HtmlShell.wrapNoViewport(
            "<div class=\"slide\">$body</div>",
            extraCss = ".slide{position:relative;width:%.0fpx;height:%.0fpx;background:#fff;margin:0 auto;overflow:hidden}"
                .format(pxW, pxH) +
                ".sh{position:absolute;box-sizing:border-box;overflow:hidden}" +
                ".sh p{margin:0.15em 0}.sh table{border-collapse:collapse;width:100%}" +
                ".sh td{border:1px solid #b8c2cf;padding:2px 6px;vertical-align:top}" +
                ".sh img{max-width:100%;max-height:100%}",
            title = "PPT",
        )
    }

    // ---------- 主题色 ----------

    private fun parseTheme(zip: ZipFile): Theme {
        val entry = zip.getEntry("ppt/theme/theme1.xml")
            ?: zip.entries().asSequence().firstOrNull { it.name.startsWith("ppt/theme/theme") && it.name.endsWith(".xml") }
            ?: return Theme(emptyMap())
        val doc = zip.getInputStream(entry).use { XmlUtil.load(it) }
        val scheme = child(doc.documentElement, "clrScheme") ?: return Theme(emptyMap())
        val colors = HashMap<String, String>()
        for (c in children(scheme)) {
            val name = c.localPart()
            val valEl = children(c).firstOrNull { it.localPart() == "srgbClr" || it.localPart() == "sysClr" }
            val hex = valEl?.attr("val") ?: valEl?.attr("lastClr")
            if (hex != null && hex.matches(Regex("[0-9a-fA-F]{6}"))) colors[name.lowercase()] = hex
        }
        // PowerPoint 语义名 -> 主题槽位
        val mapping = mapOf(
            "dk1" to colors["dk1"], "lt1" to colors["lt1"],
            "dk2" to colors["dk2"], "lt2" to colors["lt2"],
            "tx1" to (colors["dk1"] ?: "000000"),
            "bg1" to (colors["lt1"] ?: "FFFFFF"),
            "tx2" to (colors["dk2"] ?: "595959"),
            "bg2" to (colors["lt2"] ?: "F2F2F2"),
        )
        for ((k, v) in mapping) if (v != null) colors[k.lowercase()] = v
        return Theme(colors)
    }

    private fun colorOf(fillEl: Element?, theme: Theme): String? {
        if (fillEl == null) return null
        val srgb = child(fillEl, "srgbClr")
        if (srgb != null) srgb.attr("val")?.let { return it }
        val scheme = child(fillEl, "schemeClr")?.attr("val")?.lowercase()
        if (scheme != null) return theme.colors[scheme]
        return null
    }

    // ---------- 形状遍历 ----------

    private fun walkShapes(
        tree: Element,
        ctx: Transform,
        slideCx: Double, slideCy: Double,
        rels: Map<String, Rels.Rel>,
        theme: Theme,
        layout: org.w3c.dom.Document?,
        out: MutableList<String>,
    ) {
        for (el in children(tree)) {
            when (el.localPart()) {
                "sp" -> shapeHtml(el, ctx, slideCx, slideCy, rels, theme, layout)?.let { out.add(it) }
                "pic" -> pictureHtml(el, ctx, slideCx, slideCy, rels)?.let { out.add(it) }
                "graphicFrame" -> graphicFrameHtml(el, ctx, slideCx, slideCy, rels, theme)?.let { out.add(it) }
                "grpSp" -> {
                    val xfrm = child(child(el, "grpSpPr"), "xfrm")
                    if (xfrm != null) {
                        val off = child(xfrm, "off")
                        val ext = child(xfrm, "ext")
                        val chOff = child(xfrm, "chOff")
                        val chExt = child(xfrm, "chExt")
                        if (off != null && ext != null && chOff != null && chExt != null) {
                            val ox = off.attr("x")?.toDoubleOrNull() ?: 0.0
                            val oy = off.attr("y")?.toDoubleOrNull() ?: 0.0
                            val ecx = ext.attr("cx")?.toDoubleOrNull() ?: 1.0
                            val ecy = ext.attr("cy")?.toDoubleOrNull() ?: 1.0
                            val cox = chOff.attr("x")?.toDoubleOrNull() ?: 0.0
                            val coy = chOff.attr("y")?.toDoubleOrNull() ?: 0.0
                            val cxc = chExt.attr("cx")?.toDoubleOrNull()?.takeIf { it > 0 } ?: 1.0
                            val cyc = chExt.attr("cy")?.toDoubleOrNull()?.takeIf { it > 0 } ?: 1.0
                            val nsx = ctx.sx * ecx / cxc
                            val nsy = ctx.sy * ecy / cyc
                            val ntx = ctx.mapX(ox - cox * ecx / cxc)
                            val nty = ctx.mapY(oy - coy * ecy / cyc)
                            walkShapes(el, Transform(ntx, nty, nsx, nsy), slideCx, slideCy, rels, theme, layout, out)
                        } else {
                            walkShapes(el, ctx, slideCx, slideCy, rels, theme, layout, out)
                        }
                    } else {
                        walkShapes(el, ctx, slideCx, slideCy, rels, theme, layout, out)
                    }
                }
            }
        }
    }

    /** 解析 spPr/grpSpPr/p:xfrm 的绝对位置（EMU），映射到幻灯片坐标 */
    private fun absXf(spPr: Element?, ctx: Transform): Xf? {
        val xfrm = spPr?.let { child(it, "xfrm") } ?: return null
        val off = child(xfrm, "off") ?: return null
        val ext = child(xfrm, "ext") ?: return null
        val x = off.attr("x")?.toDoubleOrNull() ?: return null
        val y = off.attr("y")?.toDoubleOrNull() ?: 0.0
        val cx = ext.attr("cx")?.toDoubleOrNull() ?: return null
        val cy = ext.attr("cy")?.toDoubleOrNull() ?: return null
        return Xf(ctx.mapX(x), ctx.mapY(y), cx * ctx.sx, cy * ctx.sy)
    }

    /** 找占位符类型/索引：p:nvSpPr/p:nvPr/p:ph */
    private fun placeholderOf(sp: Element): Pair<String?, String?> {
        val ph = child(child(child(sp, "nvSpPr"), "nvPr"), "ph") ?: return null to null
        return ph.attr("type") to ph.attr("idx")
    }

    /** 从版式取占位符默认位置 */
    private fun layoutXf(type: String?, idx: String?, layout: org.w3c.dom.Document?, ctx: Transform): Xf? {
        if (layout == null) return null
        val tree = child(child(layout.documentElement, "cSld"), "spTree") ?: return null
        for (sp in children(tree)) {
            if (sp.localPart() != "sp") continue
            val (t, i) = placeholderOf(sp)
            val match = if (idx != null) i == idx else t == type || (t == null && type == null)
            if (match) {
                absXf(child(sp, "spPr"), ctx)?.let { return it }
            }
        }
        return null
    }

    /** 版式/母版都没有位置时的兜底默认布局（比例基于幻灯片） */
    private fun defaultXf(type: String?, slideCx: Double, slideCy: Double): Xf? {
        fun r(fx: Double, fy: Double, sw: Double, sh: Double) =
            Xf(fx * slideCx, fy * slideCy, sw * slideCx, sh * slideCy)
        return when (type) {
            "title", "ctrTitle" -> r(0.05, 0.04, 0.90, 0.18)
            "subTitle" -> r(0.05, 0.22, 0.90, 0.10)
            "body", "obj", null -> r(0.05, 0.24, 0.90, 0.70)
            "pic" -> r(0.10, 0.24, 0.80, 0.68)
            else -> r(0.05, 0.24, 0.90, 0.70) // 未知类型但含文字：按正文版式兜底
        }
    }

    private fun skipPlaceholder(type: String?): Boolean =
        type in setOf("dt", "ftr", "sldNum")

    private fun posStyle(xf: Xf, slideCx: Double, slideCy: Double): String {
        fun fmt(v: Double) = "%.3f".format(v)
        return "left:${fmt(xf.x / slideCx * 100)}%;top:${fmt(xf.y / slideCy * 100)}%;" +
            "width:${fmt(xf.cx / slideCx * 100)}%;height:${fmt(xf.cy / slideCy * 100)}%;"
    }

    private fun shapeHtml(
        sp: Element, ctx: Transform,
        slideCx: Double, slideCy: Double,
        rels: Map<String, Rels.Rel>, theme: Theme, layout: org.w3c.dom.Document?,
    ): String? {
        val (phType, phIdx) = placeholderOf(sp)
        if (skipPlaceholder(phType)) return null
        val txBody = child(sp, "txBody") ?: return null
        val inner = txBodyHtml(txBody, rels, theme)
        if (inner.isEmpty()) return null

        val xf = absXf(child(sp, "spPr"), ctx)
            ?: layoutXf(phType, phIdx, layout, Transform(0.0, 0.0, 1.0, 1.0))
            ?: defaultXf(phType, slideCx, slideCy)
            ?: return null

        val anchor = child(child(sp, "spPr"), "bodyPr")?.attr("anchor")
        val valign = when (anchor) {
            "ctr" -> "display:flex;flex-direction:column;justify-content:center;"
            "b" -> "display:flex;flex-direction:column;justify-content:flex-end;"
            else -> ""
        }
        val fontPx = defaultFontPx(phType)
        return "<div class=\"sh\" style=\"${posStyle(xf, slideCx, slideCy)}$valign" +
            "font-size:${fontPx}px;\">$inner</div>"
    }

    private fun defaultFontPx(type: String?): Int = when (type) {
        "title", "ctrTitle" -> 32
        "subTitle" -> 22
        else -> 18
    }

    private fun pictureHtml(
        pic: Element, ctx: Transform,
        slideCx: Double, slideCy: Double,
        rels: Map<String, Rels.Rel>,
    ): String? {
        val blip = child(child(pic, "blipFill"), "blip") ?: return null
        val rid = blip.attr("r:embed") ?: blip.attr("r:link") ?: return null
        val rel = rels[rid] ?: return null
        val xf = absXf(child(pic, "spPr"), ctx) ?: return null
        return "<div class=\"sh\" style=\"${posStyle(xf, slideCx, slideCy)}text-align:center;\">" +
            "<img src=\"${esc(rel.target)}\" style=\"height:100%;object-fit:contain;\"/></div>"
    }

    private fun graphicFrameHtml(
        el: Element, ctx: Transform,
        slideCx: Double, slideCy: Double,
        rels: Map<String, Rels.Rel>, theme: Theme,
    ): String? {
        val xfrm = child(el, "xfrm")
        val xf = xfrm?.let {
            val off = child(it, "off"); val ext = child(it, "ext")
            if (off != null && ext != null) {
                Xf(
                    ctx.mapX(off.attr("x")?.toDoubleOrNull() ?: 0.0),
                    ctx.mapY(off.attr("y")?.toDoubleOrNull() ?: 0.0),
                    (ext.attr("cx")?.toDoubleOrNull() ?: 100000.0) * ctx.sx,
                    (ext.attr("cy")?.toDoubleOrNull() ?: 100000.0) * ctx.sy,
                )
            } else null
        } ?: return null
        val tbl = collectTbl(el) ?: return null
        val inner = tableHtml(tbl, rels, theme)
        if (inner.isEmpty()) return null
        return "<div class=\"sh\" style=\"${posStyle(xf, slideCx, slideCy)}font-size:14px;\">$inner</div>"
    }

    private fun collectTbl(el: Element): Element? {
        if (el.localPart() == "tbl") return el
        for (c in children(el)) collectTbl(c)?.let { return it }
        return null
    }

    private fun tableHtml(tbl: Element, rels: Map<String, Rels.Rel>, theme: Theme): String {
        val sb = StringBuilder("<table>")
        for (tr in children(tbl)) {
            if (tr.localPart() != "tr") continue
            sb.append("<tr>")
            for (tc in children(tr)) {
                if (tc.localPart() != "tc") continue
                if (tc.attrBool("hMerge", false) || tc.attrBool("vMerge", false)) continue
                val cs = tc.attr("gridSpan")?.toIntOrNull() ?: 1
                val rs = tc.attr("rowSpan")?.toIntOrNull() ?: 1
                val tx = child(tc, "txBody")
                val text = if (tx != null) txBodyHtml(tx, rels, theme) else ""
                sb.append("<td")
                if (cs > 1) sb.append(" colspan=\"$cs\"")
                if (rs > 1) sb.append(" rowspan=\"$rs\"")
                sb.append(">$text</td>")
            }
            sb.append("</tr>")
        }
        sb.append("</table>")
        return sb.toString()
    }

    // ---------- DrawingML 文本 ----------

    private fun txBodyHtml(txBody: Element, rels: Map<String, Rels.Rel>, theme: Theme): String {
        val sb = StringBuilder()
        for (para in children(txBody)) {
            if (para.localPart() != "p") continue
            sb.append(paragraphHtml(para, rels, theme))
        }
        return sb.toString()
    }

    private fun paragraphHtml(para: Element, rels: Map<String, Rels.Rel>, theme: Theme): String {
        val pPr = child(para, "pPr")
        val algn = pPr?.attr("algn")
        val lvl = pPr?.attr("lvl")?.toIntOrNull() ?: 0
        val align = when (algn) {
            "ctr" -> "text-align:center;"
            "r" -> "text-align:right;"
            else -> ""
        }
        val indent = "padding-left:${lvl * 18}px;"

        // 项目符号：DrawingML 中只有显式声明 buChar/buAutoNum 的段落才显示符号
        val bullet = when {
            child(pPr, "buNone") != null -> ""
            child(pPr, "buChar") != null -> (child(pPr, "buChar")!!.attr("char") ?: "•") + " "
            child(pPr, "buAutoNum") != null -> "• "
            else -> ""
        }
        val runs = StringBuilder()
        for (el in children(para)) {
            when (el.localPart()) {
                "pPr", "defRPr" -> {}
                "r" -> runs.append(drawingRunHtml(el, rels, theme))
                "br" -> runs.append("<br/>")
                "fld" -> runs.append(textOfA_t(el))
            }
        }
        var content = runs.toString()
        if (content.isBlank()) return ""
        if (bullet.isNotEmpty()) content = "<span style=\"color:inherit\">$bullet</span>$content"
        return "<p style=\"$align$indent\">$content</p>"
    }

    private fun textOfA_t(el: Element): String =
        collectAt(el).joinToString("") { esc(it.textContent) }

    private fun collectAt(el: Element): List<Element> {
        if (el.localPart() == "t") return listOf(el)
        return children(el).flatMap { collectAt(it) }
    }

    private fun drawingRunHtml(r: Element, rels: Map<String, Rels.Rel>, theme: Theme): String {
        val t = child(r, "t")?.textContent ?: return ""
        if (t.isEmpty()) return ""
        var html = esc(t)

        val rPr = child(r, "rPr")
        if (rPr != null) {
            if (rPr.attrBool("b", false)) html = "<strong>$html</strong>"
            if (rPr.attrBool("i", false)) html = "<em>$html</em>"
            if (rPr.attr("u")?.let { it != "none" } == true) html = "<u>$html</u>"
            val strike = rPr.attr("strike")
            if (strike == "sngStrike" || strike == "dblStrike") html = "<s>$html</s>"
            val color = colorOf(child(rPr, "solidFill"), theme)
            val style = StringBuilder()
            if (color != null && color.lowercase() != "000000") style.append("color:#$color;")
            // 字号：sz 单位是 1/100 pt -> px(96dpi) = sz*4/300
            rPr.attr("sz")?.toDoubleOrNull()?.let { sz ->
                style.append("font-size:${"%.1f".format(sz / 100.0 * 96.0 / 72.0)}px;")
            }
            if (style.isNotEmpty()) html = "<span style=\"$style\">$html</span>"
        }

        // 超链接
        val hlink = rPr?.let { child(it, "hlinkClick") }
        if (hlink != null) {
            val rid = hlink.attr("r:id")
            val url = rid?.let { rels[it] }?.target
            if (url != null && url.startsWith("http")) {
                html = "<a href=\"${esc(url)}\">$html</a>"
            }
        }
        return html
    }
}
