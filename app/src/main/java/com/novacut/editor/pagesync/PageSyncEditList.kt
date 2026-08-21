package com.novacut.editor.pagesync

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToLong

/**
 * Small CMX3600-style edit-list exporter. The record side carries the exact
 * Page Sync cue timing; clip-name comments preserve the still image names so a
 * desktop NLE can relink or rebuild the picture sequence without deciphering
 * Android content URIs.
 */
internal class PageSyncEditList(private val context: Context) {

    suspend fun write(
        destinationUri: Uri,
        projectName: String,
        pageUris: List<Uri>,
        cuePointsMs: List<Long>,
        audioDurationMs: Long,
        fps: Int = PAGE_SYNC_EXPORT_FPS,
    ) = withContext(Dispatchers.IO) {
        require(pageUris.isNotEmpty()) { "Project has no pages." }
        require(audioDurationMs > 0L) { "Soundtrack duration is unavailable." }
        val cues = PageSyncTimeline.normalized(cuePointsMs, pageUris.size)
        require(cues.size == pageUris.size) {
            "Timing is incomplete. Finish the tap-through before exporting an edit list."
        }

        val names = pageUris.mapIndexed { index, uri ->
            displayName(uri).ifBlank { "page_${(index + 1).toString().padStart(4, '0')}" }
        }
        val edl = build(projectName, names, cues, audioDurationMs, fps)
        context.contentResolver.openOutputStream(destinationUri, "w")?.use { output ->
            output.writer(Charsets.UTF_8).use { it.write(edl) }
        } ?: error("Could not create edit-list file.")
    }

    private fun displayName(uri: Uri): String {
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment.orEmpty()
    }

    companion object {
        internal fun build(
            projectName: String,
            clipNames: List<String>,
            cuePointsMs: List<Long>,
            audioDurationMs: Long,
            fps: Int = PAGE_SYNC_EXPORT_FPS,
        ): String {
            require(clipNames.isNotEmpty())
            require(cuePointsMs.size == clipNames.size)
            require(audioDurationMs > 0L)
            require(fps > 0)

            val safeTitle = projectName.ifBlank { "Page Sync Project" }
                .replace('\n', ' ')
                .replace('\r', ' ')
            return buildString {
                appendLine("TITLE: $safeTitle")
                appendLine("FCM: NON-DROP FRAME")
                appendLine()
                clipNames.indices.forEach { index ->
                    val recordInMs = cuePointsMs[index]
                    val recordOutMs = if (index + 1 < clipNames.size) {
                        cuePointsMs[index + 1]
                    } else {
                        audioDurationMs
                    }
                    val durationMs = (recordOutMs - recordInMs).coerceAtLeast(1L)
                    val eventNumber = (index + 1).toString().padStart(3, '0')
                    val reel = "P${(index + 1).toString().padStart(4, '0')}"
                    append(eventNumber)
                    append("  ")
                    append(reel.padEnd(8))
                    append(" V     C        ")
                    append(timecode(0L, fps))
                    append(' ')
                    append(timecode(durationMs, fps))
                    append(' ')
                    append(timecode(recordInMs, fps))
                    append(' ')
                    appendLine(timecode(recordOutMs, fps))
                    appendLine("* FROM CLIP NAME: ${clipNames[index].replace('\n', ' ').replace('\r', ' ')}")
                    appendLine()
                }
            }
        }

        private fun timecode(ms: Long, fps: Int): String {
            val totalFrames = (ms.coerceAtLeast(0L) * fps / 1000.0).roundToLong()
            val frames = totalFrames % fps
            val totalSeconds = totalFrames / fps
            val seconds = totalSeconds % 60
            val totalMinutes = totalSeconds / 60
            val minutes = totalMinutes % 60
            val hours = totalMinutes / 60
            return "%02d:%02d:%02d:%02d".format(hours, minutes, seconds, frames)
        }
    }
}
