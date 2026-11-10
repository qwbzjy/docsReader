package com.wenbo.docsreader.ooxml

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory

/** OOXML 解析公共工具：DOM 载入 + 按 localName 查找（忽略命名空间前缀差异）。 */
object XmlUtil {

    fun load(input: InputStream): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        // 防 XXE
        try { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (_: Exception) {}
        try { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) } catch (_: Exception) {}
        try { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) } catch (_: Exception) {}
        factory.setExpandEntityReferences(false)
        return factory.newDocumentBuilder().parse(input)
    }

    /** 直接子元素 */
    fun children(el: Node?): List<Element> {
        if (el == null) return emptyList()
        val out = ArrayList<Element>()
        val cs = el.childNodes
        for (i in 0 until cs.length) {
            val c = cs.item(i)
            if (c.nodeType == Node.ELEMENT_NODE) out.add(c as Element)
        }
        return out
    }

    /** 直接子元素中第一个 localName 匹配的 */
    fun child(el: Node?, name: String): Element? =
        children(el).firstOrNull { it.localPart() == name }

    /** 直接子元素中所有 localName 匹配的 */
    fun childAll(el: Node?, name: String): List<Element> =
        if (el == null) emptyList() else children(el).filter { it.localPart() == name }

    fun Element.localPart(): String = tagName.substringAfterLast(':')

    /** 取属性，支持 "r:id" 带前缀写法（命名空间不敏感解析下按原名查找，再做兜底） */
    fun Element.attr(name: String): String? =
        if (hasAttribute(name)) getAttribute(name)
        else {
            val local = name.substringAfterLast(':')
            if (local != name && hasAttribute(local)) getAttribute(local)
            else null
        }

    /** 收集子树所有文本（用于 a:t / w:t 已单独处理，这里给 rels 之类兜底） */
    fun textOf(el: Element?): String = el?.textContent ?: ""

    /** 属性转布尔：val 缺省为 true，"0"/"false" 为 false */
    fun Element.attrBool(name: String, default: Boolean = true): Boolean {
        val v = attr(name) ?: return default
        return v != "0" && !v.equals("false", true) && !v.equals("none", true)
    }
}
