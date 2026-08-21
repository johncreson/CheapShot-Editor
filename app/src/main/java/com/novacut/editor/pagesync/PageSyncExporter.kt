package com.novacut.editor.pagesync

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.novacut.editor.engine.FFmpegEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume

internal const val PAGE_SYNC_EXPORT_FPS = 30

internal data class PageSyncExportResult(
    val publishedUri: Uri? = null,
    val file: File? = null,
    val error: String? = null,
)

/**
 * Small, deliberately boring exporter for narrated still-page books.
 * It materializes SAF media into a temporary folder, asks the app's existing
 * FFmpeg engine to concatenate the stills at the performed cue durations, and
 * publishes the MP4 to Movies/CheapShot.
 */
internal class PageSyncExporter(
    private val context: Context,
    private val ffmpegEngine: FFmpegEngine,
) {
    suspend fun export(
        audioUri: Uri,
        pageUris: List<Uri>,
        cuePointsMs: List<Long>,
        audioDurationMs: Long,
        onProgress: (Float) -> Unit = {},
    ): PageSyncExportResult = withContext(Dispatchers.IO) {
        if (pageUris.isEmpty()) return@withContext PageSyncExportResult(error = "No pages selected")
        val frames = PageSyncTimeline.frameCounts(
            cuePointsMs, pageUris.size, audioDurationMs, PAGE_SYNC_EXPORT_FPS,
        )
        if (frames.size != pageUris.size) {
            return@withContext PageSyncExportResult(error = "Finish syncing every page before export")
        }

        val workDir = File(context.cacheDir, "page_sync_export_${System.currentTimeMillis()}")
        workDir.mkdirs()
        try {
            val audioFile = File(workDir, "audio${extensionFor(audioUri, audio = true)}")
            copyUri(audioUri, audioFile)

            val pageFiles = pageUris.mapIndexed { index, uri ->
                File(workDir, "page_${index.toString().padStart(4, '0')}${extensionFor(uri, audio = false)}")
                    .also { copyUri(uri, it) }
            }

            val output = File(workDir, "page_sync.mp4")
            val command = buildCommand(pageFiles, frames, audioFile, output)
            onProgress(0.03f)
            val exitCode = ffmpegEngine.execute(command, progressDurationMs = audioDurationMs) { engineProgress ->
                onProgress((0.05f + engineProgress.coerceIn(0f, 1f) * 0.88f).coerceIn(0f, 0.93f))
            }
            if (exitCode != 0 || !output.isFile || output.length() <= 0L) {
                return@withContext PageSyncExportResult(error = "FFmpeg export failed (code $exitCode)")
            }

            onProgress(0.95f)
            val published = publishVideo(output)
            onProgress(1f)
            published
        } catch (t: Throwable) {
            PageSyncExportResult(error = t.message ?: t::class.java.simpleName)
        } finally {
            // The published copy is independent of this scratch directory.
            workDir.deleteRecursively()
        }
    }

    private fun buildCommand(
        pageFiles: List<File>,
        framesPerPage: List<Int>,
        audioFile: File,
        output: File,
    ): String {
        val args = mutableListOf<String>()
        args += "-y"
        pageFiles.forEachIndexed { index, file ->
            args += listOf(
                "-loop", "1",
                "-framerate", PAGE_SYNC_EXPORT_FPS.toString(),
                // Generous bound so the looping input terminates; the trim
                // filter below cuts the segment to its exact frame count.
                "-t", seconds((framesPerPage[index] + 2) * 1000L / PAGE_SYNC_EXPORT_FPS + 1000L),
                "-i", file.absolutePath,
            )
        }
        val audioIndex = pageFiles.size
        args += listOf("-i", audioFile.absolutePath)

        val filters = buildList {
            pageFiles.indices.forEach { index ->
                add(
                    "[$index:v]scale=1080:1920:force_original_aspect_ratio=decrease," +
                        "pad=1080:1920:(ow-iw)/2:(oh-ih)/2:color=black,setsar=1," +
                        // Exact per-page frame counts: cue boundaries are
                        // cumulative-rounded to the fps grid, so per-page
                        // rounding errors telescope instead of accumulating
                        // into audibly late page turns.
                        "fps=$PAGE_SYNC_EXPORT_FPS,trim=end_frame=${framesPerPage[index]}," +
                        "setpts=PTS-STARTPTS[v$index]"
                )
            }
            add(pageFiles.indices.joinToString(separator = "") { "[v$it]" } +
                "concat=n=${pageFiles.size}:v=1:a=0[outv]")
        }.joinToString(";")

        args += listOf(
            "-filter_complex", quote(filters),
            "-map", quote("[outv]"),
            "-map", "$audioIndex:a:0?",
            // The vendored LGPL FFmpeg profile always carries the native MPEG-4
            // encoder. ClearCut's normal editor can later re-encode to H.264/HEVC.
            "-c:v", "mpeg4",
            "-q:v", "3",
            "-pix_fmt", "yuv420p",
            "-c:a", "aac",
            "-b:a", "192k",
            "-shortest",
            "-movflags", "+faststart",
            output.absolutePath,
        )
        return args.joinToString(" ")
    }

    private fun quote(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

    private fun seconds(ms: Long): String = String.format(Locale.US, "%.3f", ms / 1000.0)

    private fun copyUri(uri: Uri, destination: File) {
        destination.parentFile?.mkdirs()
        context.contentResolver.openInputStream(uri)?.use { input ->
            destination.outputStream().buffered().use { output -> input.copyTo(output) }
        } ?: error("Could not open $uri")
    }

    private fun extensionFor(uri: Uri, audio: Boolean): String {
        return when (context.contentResolver.getType(uri)?.lowercase(Locale.US)) {
            "image/png" -> ".png"
            "image/webp" -> ".webp"
            "image/heic", "image/heif" -> ".heic"
            "image/gif" -> ".gif"
            "image/jpeg", "image/jpg" -> ".jpg"
            "audio/wav", "audio/x-wav" -> ".wav"
            "audio/mpeg" -> ".mp3"
            "audio/mp4", "audio/aac", "audio/x-m4a" -> ".m4a"
            "audio/flac" -> ".flac"
            "audio/ogg" -> ".ogg"
            else -> if (audio) ".audio" else ".img"
        }
    }

    private suspend fun publishVideo(source: File): PageSyncExportResult {
        val name = "CheapShot_PageSync_${System.currentTimeMillis()}.mp4"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/CheapShot")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: return PageSyncExportResult(error = "Could not create Movies/CheapShot output")
            try {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    source.inputStream().buffered().use { input -> input.copyTo(output) }
                } ?: error("Could not open output")
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
                PageSyncExportResult(publishedUri = uri)
            } catch (t: Throwable) {
                context.contentResolver.delete(uri, null, null)
                PageSyncExportResult(error = t.message ?: "Could not publish video")
            }
        } else {
            // Pre-Q: the app-private external dir is invisible to gallery apps
            // and dies with an uninstall. Publish to the public Movies
            // collection (WRITE_EXTERNAL_STORAGE, maxSdkVersion 28, granted by
            // the activity before export) and media-scan it so it is indexed.
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "CheapShot",
            )
            if (!dir.isDirectory && !dir.mkdirs()) {
                return PageSyncExportResult(error = "Could not create ${dir.absolutePath}")
            }
            val destination = File(dir, name)
            source.copyTo(destination, overwrite = true)
            val scannedUri = scanFile(destination)
            PageSyncExportResult(publishedUri = scannedUri, file = destination)
        }
    }

    private suspend fun scanFile(file: File): Uri? = suspendCancellableCoroutine { continuation ->
        MediaScannerConnection.scanFile(
            context,
            arrayOf(file.absolutePath),
            arrayOf("video/mp4"),
        ) { _, uri ->
            if (continuation.isActive) continuation.resume(uri)
        }
    }
}
