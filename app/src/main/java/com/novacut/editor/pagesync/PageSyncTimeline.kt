package com.novacut.editor.pagesync

import kotlin.math.max

internal const val PAGE_SYNC_MIN_GAP_MS = 80L

/**
 * Pure timeline helpers for the Page Sync performer UI.
 * Cue point i is the timeline start of page i. Cue 0 is always zero.
 */
object PageSyncTimeline {
    fun normalized(cues: List<Long>, pageCount: Int): List<Long> {
        if (pageCount <= 0) return emptyList()
        if (cues.isEmpty()) return listOf(0L)
        val result = ArrayList<Long>(minOf(cues.size, pageCount))
        result += 0L
        for (i in 1 until minOf(cues.size, pageCount)) {
            val floor = result.last() + PAGE_SYNC_MIN_GAP_MS
            result += max(cues[i], floor)
        }
        return result
    }

    fun pageIndexAt(positionMs: Long, cues: List<Long>, pageCount: Int): Int {
        if (pageCount <= 0) return 0
        val safe = normalized(cues, pageCount)
        var index = 0
        for (i in 1 until safe.size) {
            if (positionMs >= safe[i]) index = i else break
        }
        return index.coerceIn(0, pageCount - 1)
    }

    /** Records or replaces the start cue for the next page. */
    fun captureNextCue(
        cues: List<Long>,
        currentPageIndex: Int,
        positionMs: Long,
        pageCount: Int,
        totalDurationMs: Long = Long.MAX_VALUE,
    ): List<Long> {
        if (pageCount <= 1 || currentPageIndex >= pageCount - 1) return normalized(cues, pageCount)
        val base = normalized(cues, pageCount).toMutableList()
        val nextIndex = currentPageIndex + 1
        val previous = base.getOrElse(currentPageIndex) { 0L }
        val upper = if (nextIndex + 1 < base.size) {
            base[nextIndex + 1] - PAGE_SYNC_MIN_GAP_MS
        } else {
            (totalDurationMs - PAGE_SYNC_MIN_GAP_MS).coerceAtLeast(previous + PAGE_SYNC_MIN_GAP_MS)
        }
        val captured = positionMs
            .coerceAtLeast(previous + PAGE_SYNC_MIN_GAP_MS)
            .coerceAtMost(upper)

        if (nextIndex < base.size) {
            base[nextIndex] = captured
        } else {
            base += captured
        }
        return normalized(base, pageCount)
    }

    fun nudgeCue(
        cues: List<Long>,
        pageIndex: Int,
        deltaMs: Long,
        pageCount: Int,
        totalDurationMs: Long,
    ): List<Long> {
        if (pageIndex <= 0 || pageIndex >= pageCount) return normalized(cues, pageCount)
        val base = normalized(cues, pageCount).toMutableList()
        if (pageIndex >= base.size) return base
        val lower = base[pageIndex - 1] + PAGE_SYNC_MIN_GAP_MS
        val upper = if (pageIndex + 1 < base.size) {
            base[pageIndex + 1] - PAGE_SYNC_MIN_GAP_MS
        } else {
            (totalDurationMs - PAGE_SYNC_MIN_GAP_MS).coerceAtLeast(lower)
        }
        base[pageIndex] = (base[pageIndex] + deltaMs).coerceIn(lower, upper)
        return base
    }

    /** Keep the selected page start, throw away later timing, and resume performing from there. */
    fun redoFrom(cues: List<Long>, pageIndex: Int, pageCount: Int): List<Long> {
        if (pageCount <= 0) return emptyList()
        val safe = normalized(cues, pageCount)
        val keepThrough = pageIndex.coerceIn(0, safe.lastIndex)
        return safe.take(keepThrough + 1)
    }

    fun deleteCue(cues: List<Long>, deletedPageIndex: Int, pageCountBeforeDelete: Int): List<Long> {
        if (pageCountBeforeDelete <= 1) return emptyList()
        val safe = normalized(cues, pageCountBeforeDelete).toMutableList()
        if (deletedPageIndex in safe.indices) safe.removeAt(deletedPageIndex)
        if (safe.isNotEmpty()) safe[0] = 0L
        return normalized(safe, pageCountBeforeDelete - 1)
    }

    fun durations(cues: List<Long>, pageCount: Int, totalDurationMs: Long): List<Long> {
        if (pageCount <= 0 || totalDurationMs <= 0L) return emptyList()
        val safe = normalized(cues, pageCount)
        if (safe.size < pageCount) return emptyList()
        return List(pageCount) { index ->
            val start = safe[index]
            val end = if (index + 1 < pageCount) safe[index + 1] else totalDurationMs
            (end - start).coerceAtLeast(PAGE_SYNC_MIN_GAP_MS)
        }
    }
}
