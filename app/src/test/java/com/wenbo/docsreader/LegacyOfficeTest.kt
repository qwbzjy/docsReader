package com.wenbo.docsreader

import com.wenbo.docsreader.legacy.LegacyOffice
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File

/**
 * 旧版二进制格式解析验证。样例文件不存在时自动跳过。
 * 生成方式见 tools/gen_office_samples.py 及 README：
 * - test_legacy.doc  : macOS textutil 转换的真实 Word97
 * - test_legacy.xls  : python xlwt 生成的 BIFF8
 * - test_fs.xls      : filesamples.com 真实 .xls
 * - sample_filesamples.ppt : 真实 PowerPoint 97 二进制
 */
class LegacyOfficeTest {

    private val sampleDir = File("/tmp/office_samples")

    private fun file(name: String): File {
        val f = File(sampleDir, name)
        Assume.assumeTrue("样例不存在: $name", f.exists())
        return f
    }

    @Test
    fun docWord97() {
        val html = LegacyOffice.docToHtml(file("test_legacy.doc"))
        assertTrue("应提取到段落", html.contains("<p>"))
        assertTrue("应包含中文内容", html.contains("标题测试") || html.contains("第一段"))
    }

    @Test
    fun xlsFromXlwt() {
        val sheets = LegacyOffice.xlsToSheets(file("test_legacy.xls"))
        assertTrue("应有工作表", sheets.isNotEmpty())
        val html = sheets[0].html
        assertTrue("共享文本", html.contains("张三") && html.contains("李四"))
        assertTrue("小数", html.contains("1234.56"))
        assertTrue("日期", html.contains("2024"))
        assertTrue("合并单元格", html.contains("合并两列") && html.contains("colspan=\"2\""))
    }

    @Test
    fun xlsFromFileSamples() {
        val sheets = LegacyOffice.xlsToSheets(file("test_fs.xls"))
        assertTrue("filesamples .xls 应可解析", sheets.isNotEmpty())
    }

    @Test
    fun pptPowerPoint97() {
        val slides = LegacyOffice.pptToSlides(file("sample_filesamples.ppt"))
        assertTrue("应解析出幻灯片", slides.isNotEmpty())
        assertTrue("应含标题文本", slides.any { it.contains("PowerPoint") })
    }
}
