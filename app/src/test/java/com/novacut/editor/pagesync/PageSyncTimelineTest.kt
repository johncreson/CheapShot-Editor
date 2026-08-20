package com.novacut.editor.pagesync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageSyncTimelineTest {
    @Test
    fun captureNextCue_appendsPerformedBoundary() {
        val cues = PageSyncTimeline.captureNextCue(
            cues = listOf(0L),
            currentPageIndex = 0,
            positionMs = 7_850L,
            pageCount = 3,
            totalDurationMs = 30_000L,
        )

        assertEquals(listOf(0L, 7_850L), cues)
    }

    @Test
    fun nudgeCue_neverCrossesNeighbor() {
        val cues = PageSyncTimeline.nudgeCue(
            cues = listOf(0L, 5_000L, 5_500L),
            pageIndex = 1,
            deltaMs = 10_000L,
            pageCount = 3,
            totalDurationMs = 20_000L,
        )

        assertTrue(cues[1] <= cues[2] - PAGE_SYNC_MIN_GAP_MS)
    }

    @Test
    fun redoFrom_keepsSelectedPageStartAndDropsLaterCues() {
        val cues = PageSyncTimeline.redoFrom(
            cues = listOf(0L, 4_000L, 8_000L, 12_000L),
            pageIndex = 2,
            pageCount = 4,
        )

        assertEquals(listOf(0L, 4_000L, 8_000L), cues)
    }

    @Test
    fun durations_holdsLastPageUntilAudioEnd() {
        val durations = PageSyncTimeline.durations(
            cues = listOf(0L, 3_000L, 7_000L),
            pageCount = 3,
            totalDurationMs = 10_000L,
        )

        assertEquals(listOf(3_000L, 4_000L, 3_000L), durations)
    }

    @Test
    fun deleteFirstPage_reanchorsNextPageAtZero() {
        val cues = PageSyncTimeline.deleteCue(
            cues = listOf(0L, 5_000L, 9_000L),
            deletedPageIndex = 0,
            pageCountBeforeDelete = 3,
        )

        assertEquals(0L, cues.first())
        assertEquals(2, cues.size)
    }
}
