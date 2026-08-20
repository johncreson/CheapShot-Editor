package com.novacut.editor.pagesync

import org.junit.Assert.assertTrue
import org.junit.Test

class PageSyncEditListTest {
    @Test
    fun build_preservesRecordTimingAndClipNames() {
        val edl = PageSyncEditList.build(
            projectName = "The Humming Pool",
            clipNames = listOf("cover.png", "page_01.png", "page_02.png"),
            cuePointsMs = listOf(0L, 5_000L, 12_500L),
            audioDurationMs = 20_000L,
            fps = 30,
        )

        assertTrue(edl.contains("TITLE: The Humming Pool"))
        assertTrue(edl.contains("00:00:05:00"))
        assertTrue(edl.contains("00:00:12:15"))
        assertTrue(edl.contains("* FROM CLIP NAME: cover.png"))
        assertTrue(edl.contains("* FROM CLIP NAME: page_02.png"))
    }
}
