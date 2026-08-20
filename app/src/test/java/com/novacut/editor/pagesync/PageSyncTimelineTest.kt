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
        // Cues are positions on the (unchanged) audio timeline: deleting a
        // page merges its span into its neighbor, it does not shift audio.
        assertEquals(9_000L, cues[1])
    }

    @Test
    fun captureNextCue_ignoresCaptureBeyondCapturedRange() {
        // Page index 3 selected while only cue 0 exists: pages 1-2 have no
        // cues yet, so recording "the next page starts now" is meaningless
        // and must not append 12s as page 1's start.
        val cues = PageSyncTimeline.captureNextCue(
            cues = listOf(0L),
            currentPageIndex = 3,
            positionMs = 12_000L,
            pageCount = 6,
            totalDurationMs = 30_000L,
        )

        assertEquals(listOf(0L), cues)
    }

    @Test
    fun captureNextCue_replacesExistingBoundaryClampedBetweenNeighbors() {
        val cues = PageSyncTimeline.captureNextCue(
            cues = listOf(0L, 4_000L, 8_000L),
            currentPageIndex = 0,
            positionMs = 5_000L,
            pageCount = 3,
            totalDurationMs = 30_000L,
        )

        assertEquals(3, cues.size)
        assertEquals(5_000L, cues[1])
        assertTrue(cues[1] <= cues[2] - PAGE_SYNC_MIN_GAP_MS)
    }

    @Test
    fun normalized_enforcesMinGapCascadeAndTruncates() {
        val cues = PageSyncTimeline.normalized(
            cues = listOf(0L, 1_000L, 1_010L, 1_020L, 50_000L),
            pageCount = 4,
        )

        assertEquals(4, cues.size)
        assertEquals(0L, cues[0])
        for (i in 1 until cues.size) {
            assertTrue(cues[i] >= cues[i - 1] + PAGE_SYNC_MIN_GAP_MS)
        }
    }

    @Test
    fun pageIndexAt_selectsPageByBoundaryInclusive() {
        val cues = listOf(0L, 3_000L, 7_000L)

        assertEquals(0, PageSyncTimeline.pageIndexAt(2_999L, cues, 3))
        assertEquals(1, PageSyncTimeline.pageIndexAt(3_000L, cues, 3))
        assertEquals(2, PageSyncTimeline.pageIndexAt(60_000L, cues, 3))
    }

    @Test
    fun frameCounts_roundingErrorsTelescopeInsteadOfAccumulating() {
        // 24 boundaries that each land just past a 30fps frame edge: the old
        // per-segment ceil quantization drifted the final boundary ~0.4s
        // late; cumulative rounding must keep every boundary within half a
        // frame (17ms) of the performed cue.
        val pageCount = 24
        val cues = List(pageCount) { it * 1_001L }
        val totalMs = 30_000L
        val frames = PageSyncTimeline.frameCounts(cues, pageCount, totalMs, fps = 30)

        assertEquals(pageCount, frames.size)
        var boundaryFrames = 0L
        for (index in 1 until pageCount) {
            boundaryFrames += frames[index - 1]
            val boundaryMs = boundaryFrames * 1_000.0 / 30
            assertTrue(
                "boundary $index drifted to $boundaryMs vs cue ${cues[index]}",
                kotlin.math.abs(boundaryMs - cues[index]) <= 17.0,
            )
        }
        assertEquals(Math.round(totalMs * 30 / 1000.0), frames.sumOf { it.toLong() })
    }

    @Test
    fun frameCounts_totalMatchesAudioAndEveryPageGetsAFrame() {
        val frames = PageSyncTimeline.frameCounts(
            cues = listOf(0L, 3_000L, 7_000L),
            pageCount = 3,
            totalDurationMs = 10_000L,
            fps = 30,
        )

        assertEquals(listOf(90, 120, 90), frames)
    }

    @Test
    fun frameCounts_cuesBeyondAudioEndDegradeToSingleFrames() {
        val frames = PageSyncTimeline.frameCounts(
            cues = listOf(0L, 9_990L, 12_000L),
            pageCount = 3,
            totalDurationMs = 10_000L,
            fps = 30,
        )

        assertEquals(3, frames.size)
        assertTrue(frames.all { it >= 1 })
    }

    @Test
    fun frameCounts_incompleteCuesReturnEmpty() {
        val frames = PageSyncTimeline.frameCounts(
            cues = listOf(0L, 3_000L),
            pageCount = 4,
            totalDurationMs = 10_000L,
            fps = 30,
        )

        assertTrue(frames.isEmpty())
    }
}
