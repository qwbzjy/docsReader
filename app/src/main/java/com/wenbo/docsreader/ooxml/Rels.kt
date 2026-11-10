package com.wenbo.docsreader.ooxml

import java.util.zip.ZipFile
import com.wenbo.docsreader.ooxml.XmlUtil.attr
import com.wenbo.docsreader.ooxml.XmlUtil.childAll
import com.wenbo.docsreader.ooxml.XmlUtil.children
import com.wenbo.docsreader.ooxml.XmlUtil.localPart

/** OOXML 的 .rels 关系文件解析。 */
object Rels {

    data class Rel(val target: String, val external: Boolean)

    /**
     * 解析 [relsPath]（如 word/_rels/document.xml.rels），
     * 内部 target 会相对 [baseDir]（如 "word/"）解析成 zip 内完整路径。
     */
    fun parse(zip: ZipFile, relsPath: String, baseDir: String): Map<String, Rel> {
        val entry = zip.getEntry(relsPath) ?: return emptyMap()
        val out = HashMap<String, Rel>()
        zip.getInputStream(entry).use { input ->
            val doc = XmlUtil.load(input)
            for (el in XmlUtil.children(doc.documentElement)) {
                if (el.localPart() != "Relationship") continue
                val id = el.attr("Id") ?: continue
                val target = el.attr("Target") ?: continue
                val external = el.attr("TargetMode") == "External"
                out[id] = Rel(
                    target = if (external) target else resolve(baseDir, target),
                    external = external,
                )
            }
        }
        return out
    }

    /** 把相对路径解析为 zip 内绝对路径，处理 "../media/x.png" 之类 */
    fun resolve(baseDir: String, relative: String): String {
        if (relative.startsWith("/")) return relative.substring(1)
        val stack = baseDir.trimEnd('/').split("/").filter { it.isNotEmpty() }.toMutableList()
        for (seg in relative.split("/")) {
            when (seg) {
                "", "." -> {}
                ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                else -> stack.add(seg)
            }
        }
        return stack.joinToString("/")
    }
}
