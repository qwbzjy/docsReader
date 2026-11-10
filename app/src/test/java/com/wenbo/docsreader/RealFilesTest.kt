package com.wenbo.docsreader

import com.wenbo.docsreader.ooxml.DocxToHtml
import com.wenbo.docsreader.ooxml.PptxToHtml
import com.wenbo.docsreader.ooxml.XlsxToHtml
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * 对真实 Office 库（python-docx / openpyxl / python-pptx）生成的文件做端到端解析验证。
 * 样例不存在时自动跳过。重新生成样例：python3 tools/gen_office_samples.py
 */
class RealFilesTest {

    private val sampleDir = File("/tmp/office_samples")

    private fun zip(name: String): ZipFile {
        Assume.assumeTrue("样例文件不存在，跳过", File(sampleDir, name).exists())
        return ZipFile(File(sampleDir, name))
    }

    @Test
    fun realDocx() {
        val zip = zip("sample.docx")
        val html = DocxToHtml.convert(zip)
        zip.close()
        check(html.contains("项目报告")) { "缺少标题" }
        check(html.contains("加粗文字")) { "缺少加粗内容" }
        check(html.contains("官方文档")) { "缺少超链接文本" }
        check(html.contains("合并两列")) { "缺少表格内容" }
        check(html.contains("colspan")) { "缺少列合并" }
        check(html.contains("纵向合并")) { "缺少纵向合并" }
        check(html.contains("word/media/")) { "缺少图片引用" }
        check(html.contains("<li>无序列表项 A</li>") && html.contains("<ul>")) { "列表样式未识别" }
        check(Regex("<h1[^>]*>项目报告</h1>").containsMatchIn(html)) { "缺少 h1 标题" }
    }

    @Test
    fun realXlsx() {
        val zip = zip("sample.xlsx")
        val sheets = XlsxToHtml.convert(zip)
        zip.close()
        check(sheets.size >= 2) { "工作表数量错误: ${sheets.size}" }
        val ws = sheets.first { it.name == "销售表" }
        check(ws.html.contains("苹果") && ws.html.contains("香蕉")) { "缺少共享字符串" }
        check(ws.html.contains("660")) { "缺少数字" }
        check(ws.html.contains("-42.75")) { "缺少负数小数" }
        check(ws.html.contains("2024-03-15")) { "日期序列转换错误" }
        check(ws.html.contains("合并文本")) { "缺少合并区文本" }
        check(ws.html.contains("备注列")) { "缺少尾列" }
    }

    @Test
    fun realPptx() {
        val zip = zip("sample.pptx")
        val slides = PptxToHtml.convert(zip)
        zip.close()
        check(slides.size == 3) { "幻灯片数量错误: ${slides.size}" }
        val s1 = slides[0].html
        check(s1.contains("季度总结")) { "缺少标题（占位符继承版式）" }
        check(s1.contains("2026 年第一季度")) { "缺少副标题" }
        val s2 = slides[1].html
        check(s2.contains("正文第一段")) { "缺少正文" }
        check(s2.contains("加粗第二段")) { "缺少加粗" }
        check(s2.contains("营收") && s2.contains("1.5亿")) { "缺少表格内容" }
        check(s2.contains("ppt/media/")) { "缺少图片" }
        val s3 = slides[2].html
        check(s3.contains("项目符号一")) { "缺少段落" }
        // 占位符标题不应出现 left/top 都为 0 且不可见
        assertTrue("幻灯片尺寸", slides[0].html.contains("width:1280px"))
    }
}
