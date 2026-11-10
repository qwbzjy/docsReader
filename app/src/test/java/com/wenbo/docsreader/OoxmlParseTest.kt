package com.wenbo.docsreader

import com.wenbo.docsreader.ooxml.DocxToHtml
import com.wenbo.docsreader.ooxml.PptxToHtml
import com.wenbo.docsreader.ooxml.Rels
import com.wenbo.docsreader.ooxml.XlsxToHtml
import com.wenbo.docsreader.util.UriUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** 用内存字符串构造 OOXML zip，验证解析器正确性。 */
class OoxmlParseTest {

    private fun zipOf(vararg parts: Pair<String, String>): ZipFile {
        val file = File.createTempFile("test", ".zip")
        file.deleteOnExit()
        ZipOutputStream(file.outputStream()).use { zos ->
            for ((name, content) in parts) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return ZipFile(file)
    }

    // ---------- docx ----------

    private val docxParts = arrayOf(
        "word/document.xml" to """
            <?xml version="1.0" encoding="UTF-8"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
                        xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
                        xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
                        xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"
                        xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing">
              <w:body>
                <w:p><w:pPr><w:pStyle w:val="Heading2"/></w:pPr><w:r><w:t>章标题</w:t></w:r></w:p>
                <w:p><w:r><w:rPr><w:b/><w:i/></w:rPr><w:t>粗斜体</w:t></w:r><w:r><w:t>&lt;正常&amp;&gt;</w:t></w:r></w:p>
                <w:p><w:pPr><w:numPr><w:ilvl w:val="0"/></w:numPr></w:pPr><w:r><w:t>列表项一</w:t></w:r></w:p>
                <w:p><w:pPr><w:numPr><w:ilvl w:val="0"/></w:numPr></w:pPr><w:r><w:t>列表项二</w:t></w:r></w:p>
                <w:p><w:r><w:drawing><wp:inline><a:graphic><a:graphicData>
                  <pic:pic><pic:blipFill><a:blip r:embed="rId3"/></pic:blipFill></pic:pic>
                </a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>
                <w:p><w:hyperlink r:id="rId2"><w:r><w:t>官网</w:t></w:r></w:hyperlink></w:p>
                <w:tbl>
                  <w:tr>
                    <w:tc><w:tcPr><w:gridSpan w:val="2"/></w:tcPr><w:p><w:r><w:t>跨两列</w:t></w:r></w:p></w:tc>
                  </w:tr>
                  <w:tr>
                    <w:tc><w:tcPr><w:vMerge w:val="restart"/></w:tcPr><w:p><w:r><w:t>纵合并</w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>右B</w:t></w:r></w:p></w:tc>
                  </w:tr>
                  <w:tr>
                    <w:tc><w:tcPr><w:vMerge/></w:tcPr><w:p/></w:tc>
                    <w:tc><w:p><w:r><w:t>右D</w:t></w:r></w:p></w:tc>
                  </w:tr>
                </w:tbl>
              </w:body>
            </w:document>
        """.trimIndent(),
        "word/_rels/document.xml.rels" to """
            <?xml version="1.0" encoding="UTF-8"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink"
                            Target="https://example.com" TargetMode="External"/>
              <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image"
                            Target="media/image1.png"/>
            </Relationships>
        """.trimIndent(),
    )

    @Test
    fun docx_basic() {
        val zip = zipOf(*docxParts)
        val html = DocxToHtml.convert(zip)
        zip.close()

        assertTrue("标题层级", html.contains("<h2>章标题</h2>"))
        assertTrue("粗斜体", html.contains("<strong><em>粗斜体</em></strong>"))
        assertTrue("html 转义", html.contains("&lt;正常&amp;&gt;"))
        assertTrue("列表", html.contains("<ul>") && html.contains("<li>列表项一</li>") && html.contains("</ul>"))
        assertTrue("图片路径解析到 zip 内部", html.contains("src=\"word/media/image1.png\""))
        assertTrue("超链接", html.contains("<a href=\"https://example.com\">官网</a>"))
        assertTrue("gridSpan", html.contains("colspan=\"2\""))
        assertTrue("vMerge rowspan", html.contains("rowspan=\"2\""))
        assertTrue("合并续格跳过", !html.contains("<td><p></p></td><td><p>右D</p></td>"))
        assertTrue("表格内容", html.contains("纵合并") && html.contains("右D"))
    }

    // ---------- xlsx ----------

    private val xlsxParts = arrayOf(
        "xl/workbook.xml" to """
            <?xml version="1.0" encoding="UTF-8"?>
            <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                      xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
              <sheets><sheet name="收入表" sheetId="1" r:id="rId1"/></sheets>
            </workbook>
        """.trimIndent(),
        "xl/_rels/workbook.xml.rels" to """
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Target="worksheets/sheet1.xml"/>
            </Relationships>
        """.trimIndent(),
        "xl/sharedStrings.xml" to """
            <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <si><t>名称</t></si>
              <si><r><t>富文</t></r><r><t>本串</t></r></si>
            </sst>
        """.trimIndent(),
        "xl/styles.xml" to """
            <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <cellXfs count="2">
                <xf numFmtId="0"/>
                <xf numFmtId="14"/>
              </cellXfs>
            </styleSheet>
        """.trimIndent(),
        "xl/worksheets/sheet1.xml" to """
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <sheetData>
                <row r="1">
                  <c r="A1" t="s"><v>0</v></c>
                  <c r="B1" t="s"><v>1</v></c>
                  <c r="C1" s="1"><v>44927</v></c>
                </row>
                <row r="2">
                  <c r="A2"><v>3.14</v></c>
                  <c r="B2" t="inlineStr"><is><t>内联字串</t></is></c>
                  <c r="C2"><v>0.5</v></c>
                </row>
                <row r="4">
                  <c r="A4"><v>1234567890</v></c>
                  <c r="B4" t="b"><v>1</v></c>
                </row>
              </sheetData>
              <mergeCells count="1"><mergeCell ref="C2:D2"/></mergeCells>
            </worksheet>
        """.trimIndent(),
    )

    @Test
    fun xlsx_basic() {
        val zip = zipOf(*xlsxParts)
        val sheets = XlsxToHtml.convert(zip)
        zip.close()

        assertEquals(1, sheets.size)
        assertEquals("收入表", sheets[0].name)
        val html = sheets[0].html
        assertTrue("共享字符串", html.contains("名称") && html.contains("富文本"))
        assertTrue("日期序列转日期", html.contains("2023-01-01"))
        assertTrue("小数", html.contains("3.14"))
        assertTrue("整数去尾", html.contains("1234567890") && !html.contains("1234567890.0"))
        assertTrue("布尔", html.contains("TRUE"))
        assertTrue("内联字符串", html.contains("内联字串"))
        assertTrue("合并单元格 colspan", html.contains("colspan=\"2\""))
    }

    @Test
    fun xlsx_date_serial() {
        assertEquals("2023-01-01", XlsxToHtml.serialToText(44927.0))
        assertEquals("1900-01-01", XlsxToHtml.serialToText(1.0))
        assertEquals("1900-02-28", XlsxToHtml.serialToText(59.0))
        assertEquals("1900-03-01", XlsxToHtml.serialToText(61.0))
        assertEquals("2023-01-01 12:30:00", XlsxToHtml.serialToText(44927.5208333333))
        assertTrue(XlsxToHtml.looksLikeDateFormat("yyyy\"年\"m\"月\"d\"日\""))
        assertTrue(XlsxToHtml.looksLikeDateFormat("h:mm:ss"))
        assertTrue(!XlsxToHtml.looksLikeDateFormat("#,##0.00"))
        assertTrue(!XlsxToHtml.looksLikeDateFormat("General"))
        assertEquals(0, XlsxToHtml.refCol("A1"))
        assertEquals(25, XlsxToHtml.refCol("Z1"))
        assertEquals(26, XlsxToHtml.refCol("AA7"))
        assertEquals(701, XlsxToHtml.refCol("ZZ9"))
    }

    // ---------- pptx ----------

    private val pptxParts = arrayOf(
        "ppt/presentation.xml" to """
            <p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
                            xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
              <p:sldSz cx="12192000" cy="6858000"/>
              <p:sldIdLst><p:sldId id="256" r:id="rId8"/></p:sldIdLst>
            </p:presentation>
        """.trimIndent(),
        "ppt/_rels/presentation.xml.rels" to """
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId8" Target="slides/slide1.xml"/>
            </Relationships>
        """.trimIndent(),
        "ppt/slides/slide1.xml" to """
            <p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
                   xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
                   xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
              <p:cSld><p:spTree>
                <p:sp>
                  <p:nvSpPr><p:nvPr><p:ph type="title"/></p:nvPr></p:nvSpPr>
                  <p:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="12192000" cy="1000000"/></a:xfrm></p:spPr>
                  <p:txBody>
                    <a:p><a:pPr algn="ctr"/><a:r><a:rPr sz="3200" b="1"/><a:t>演示标题</a:t></a:r></a:p>
                  </p:txBody>
                </p:sp>
                <p:sp>
                  <p:nvSpPr><p:nvPr/></p:nvSpPr>
                  <p:spPr><a:xfrm><a:off x="2000000" y="2000000"/><a:ext cx="4000000" cy="3000000"/></a:xfrm></p:spPr>
                  <p:txBody>
                    <a:p><a:r><a:rPr sz="1800"/><a:t>正文一段</a:t></a:r></a:p>
                    <a:p><a:pPr><a:buChar char="•"/></a:pPr><a:r><a:rPr sz="1800" i="1"/><a:t>项目二</a:t></a:r></a:p>
                  </p:txBody>
                </p:sp>
                <p:pic>
                  <p:spPr><a:xfrm><a:off x="8000000" y="2000000"/><a:ext cx="2000000" cy="2000000"/></a:xfrm></p:spPr>
                  <p:blipFill><a:blip r:embed="rId1"/></p:blipFill>
                </p:pic>
              </p:spTree></p:cSld>
            </p:sld>
        """.trimIndent(),
        "ppt/slides/_rels/slide1.xml.rels" to """
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Target="../media/pic1.png"/>
            </Relationships>
        """.trimIndent(),
    )

    @Test
    fun pptx_basic() {
        val zip = zipOf(*pptxParts)
        val slides = PptxToHtml.convert(zip)
        zip.close()

        assertEquals(1, slides.size)
        val html = slides[0].html
        assertTrue("标题文本+粗体", html.contains("<strong>"))
        assertTrue("标题字号 32pt→~42.7px", html.contains("42.7px"))
        assertTrue("居中对齐", html.contains("text-align:center"))
        assertTrue("斜体+项目符号", html.contains("<em>项目二</em>") && html.contains("•"))
        assertTrue("位置百分比", html.contains("left:16.404%") && html.contains("top:29.163%"))
        assertTrue("图片路径归一化", html.contains("ppt/media/pic1.png"))
        assertTrue("幻灯片固定像素尺寸", html.contains("width:1280px") && html.contains("height:720px"))
    }

    // ---------- 其他 ----------

    @Test
    fun rels_path_resolution() {
        assertEquals("word/media/i.png", Rels.resolve("word/", "media/i.png"))
        assertEquals("ppt/media/p.png", Rels.resolve("ppt/slides/", "../media/p.png"))
        assertEquals("xl/sheet1.xml", Rels.resolve("xl/", "/xl/sheet1.xml"))
    }

    @Test
    fun text_encoding_detection() {
        val utf8WithBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "中文abc".toByteArray(Charsets.UTF_8)
        assertEquals("中文abc", UriUtil.decodeSmart(utf8WithBom))

        val gbk = "中文文档".toByteArray(Charset.forName("GBK"))
        // GBK 高字节序列通常不是合法 UTF-8，应回退成功
        assertEquals("中文文档", UriUtil.decodeSmart(gbk))

        val plainUtf8 = "hello 世界".toByteArray(Charsets.UTF_8)
        assertEquals("hello 世界", UriUtil.decodeSmart(plainUtf8))
    }
}
