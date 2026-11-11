package com.wenbo.docsreader

import com.wenbo.docsreader.util.TreeResources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    // ---- Markdown 链接相对路径解析 ----

    private val notesDir = listOf("primary:Notes", "2024")

    @Test
    fun linkResolvesSiblingFile() {
        // [视频](video.mp4) 与 markdown 同目录
        assertEquals("primary:Notes/2024/video.mp4", TreeResources.normalizeRelative(notesDir, "video.mp4"))
    }

    @Test
    fun linkResolvesNestedAndParentPaths() {
        assertEquals("primary:Notes/2024/media/a.mp4".let { it }, TreeResources.normalizeRelative(notesDir, "./media/a.mp4"))
        assertEquals(
            "primary:Notes/2024/media/a.mp4",
            TreeResources.normalizeRelative(notesDir, "media/../media/a.mp4"),
        )
        // 上级目录仍在授权范围内
        assertEquals("primary:Notes/shared/a.mp4", TreeResources.normalizeRelative(notesDir, "../shared/a.mp4"))
    }

    @Test
    fun linkStripsAnchorAndQuery() {
        assertEquals("primary:Notes/2024/a.mp4", TreeResources.normalizeRelative(notesDir, "a.mp4#t=10"))
        assertEquals("primary:Notes/2024/a.mp4", TreeResources.normalizeRelative(notesDir, "a.mp4?raw=1"))
    }

    @Test
    fun linkDecodesPercentEncodingButKeepsPlus() {
        assertEquals("primary:Notes/2024/我的 视频.mp4", TreeResources.normalizeRelative(notesDir, "%E6%88%91%E7%9A%84%20%E8%A7%86%E9%A2%91.mp4"))
        assertEquals("primary:Notes/2024/a+b.mp4", TreeResources.normalizeRelative(notesDir, "a+b.mp4"))
    }

    @Test
    fun linkRejectsEscapingGrantRoot() {
        assertNull(TreeResources.normalizeRelative(notesDir, "../../../etc/passwd"))
        assertNull(TreeResources.normalizeRelative(notesDir, "/absolute/a.mp4"))
        assertNull(TreeResources.normalizeRelative(notesDir, "#only-anchor"))
        assertNull(TreeResources.normalizeRelative(notesDir, ""))
    }
}
