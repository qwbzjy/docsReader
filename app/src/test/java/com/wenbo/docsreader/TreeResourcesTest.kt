package com.wenbo.docsreader

import com.wenbo.docsreader.util.TreeResources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** markdown 图片引用 scheme 前缀判断（纯 JVM）。 */
class TreeResourcesTest {

    @Test
    fun remoteAndInlineSchemesAreLoadable() {
        // String.matches 要求整串匹配，曾导致此前缀判断恒为 false
        assertTrue(TreeResources.hasLoadableScheme("https://example.com/a.png"))
        assertTrue(TreeResources.hasLoadableScheme("http://example.com/a.png"))
        assertTrue(TreeResources.hasLoadableScheme("data:image/png;base64,iVBOR"))
        assertTrue(TreeResources.hasLoadableScheme("file:///sdcard/a.png"))
        assertTrue(TreeResources.hasLoadableScheme("content://media/a.png"))
        assertTrue(TreeResources.hasLoadableScheme("//example.com/a.png"))
        assertTrue(TreeResources.hasLoadableScheme("HTTPS://example.com/a.png"))
    }

    @Test
    fun relativePathsAreNotLoadable() {
        assertFalse(TreeResources.hasLoadableScheme("img/pic.png"))
        assertFalse(TreeResources.hasLoadableScheme("./a.png"))
        assertFalse(TreeResources.hasLoadableScheme("../shared/a.png"))
        assertFalse(TreeResources.hasLoadableScheme("pic.png"))
        assertFalse(TreeResources.hasLoadableScheme(""))
    }
}
