package com.novacut.editor.pagesync

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

internal const val PAGE_SYNC_PROJECT_MIME = "application/vnd.cheapshot.pagesync+zip"
internal const val PAGE_SYNC_PROJECT_MANIFEST = "pagesync.json"

internal data class ImportedPageSyncProject(
    val name: String,
    val audioUri: Uri,
    val pageUris: List<Uri>,
    val cuePointsMs: List<Long>,
    val audioDurationMs: Long,
)

/**
 * Portable Page Sync archive reader.
 *
 * The archive intentionally stores only the state a narrated still-page book
 * needs: ordered page media, one soundtrack, page-start cues, and canvas
 * metadata. That keeps the format stable and lets external tooling build a
 * project without inventing ClearCut database identifiers.
 *
 * Expected archive shape:
 *
 *   pagesync.json
 *   audio/...
 *   pages/...
 *
 * Optional SHA-256 checksums in the manifest are verified when present.
 */
internal class PageSyncProjectArchive(private val context: Context) {

    fun open(sourceUri: Uri): ImportedPageSyncProject {
        val projectRoot = File(
            context.filesDir,
            "page-sync-projects/import-${System.currentTimeMillis()}",
        )
        require(projectRoot.mkdirs() || projectRoot.isDirectory) {
            "Could not create Page Sync project storage."
        }

        try {
            extractArchive(sourceUri, projectRoot)
            val manifestFile = File(projectRoot, PAGE_SYNC_PROJECT_MANIFEST)
            require(manifestFile.isFile) {
                "This archive has no $PAGE_SYNC_PROJECT_MANIFEST manifest."
            }

            val manifest = JSONObject(manifestFile.readText(Charsets.UTF_8))
            require(manifest.optString("format") == "cheapshot.pagesync") {
                "This is not a Cheap Shot Page Sync project."
            }
            require(manifest.optInt("version", 0) == 1) {
                "Unsupported Page Sync project version ${manifest.optInt("version", 0)}."
            }

            val audioObject = manifest.getJSONObject("audio")
            val audioFile = safeRelativeFile(projectRoot, audioObject.getString("path"))
            require(audioFile.isFile) { "Project soundtrack is missing." }
            val audioDurationMs = audioObject.optLong("durationMs", 0L).coerceAtLeast(0L)

            val sequence = manifest.getJSONArray("sequence")
            require(sequence.length() > 0) { "Project has no pages." }
            require(sequence.length() <= MAX_PAGE_COUNT) { "Project has too many pages." }

            val pageFiles = ArrayList<File>(sequence.length())
            val cues = ArrayList<Long>(sequence.length())
            for (index in 0 until sequence.length()) {
                val item = sequence.getJSONObject(index)
                val pageFile = safeRelativeFile(projectRoot, item.getString("path"))
                require(pageFile.isFile) { "Project page ${index + 1} is missing." }
                pageFiles += pageFile
                cues += item.getLong("cueMs").coerceAtLeast(0L)
            }

            val normalizedCues = PageSyncTimeline.normalized(cues, pageFiles.size)
            require(normalizedCues.size == pageFiles.size) { "Project timing is incomplete." }
            require(cues.first() == 0L) { "The first Page Sync cue must start at zero." }
            require(cues == normalizedCues) {
                "Project cues are not in a valid increasing Page Sync order."
            }
            if (audioDurationMs > 0L) {
                require(normalizedCues.last() < audioDurationMs) {
                    "The last page starts after the soundtrack ends."
                }
            }

            verifyChecksumsIfPresent(manifest, projectRoot)

            val authority = "${context.packageName}.fileprovider"
            fun contentUri(file: File): Uri =
                FileProvider.getUriForFile(context, authority, file)

            return ImportedPageSyncProject(
                name = manifest.optString("projectName")
                    .takeIf { it.isNotBlank() }
                    ?: "Imported Page Sync Project",
                audioUri = contentUri(audioFile),
                pageUris = pageFiles.map(::contentUri),
                cuePointsMs = normalizedCues,
                audioDurationMs = audioDurationMs,
            )
        } catch (t: Throwable) {
            projectRoot.deleteRecursively()
            throw t
        }
    }

    private fun extractArchive(sourceUri: Uri, destinationRoot: File) {
        val input = context.contentResolver.openInputStream(sourceUri)
            ?: error("Could not open Page Sync project archive.")
        var entryCount = 0
        var totalBytes = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)

        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount += 1
                require(entryCount <= MAX_ARCHIVE_ENTRIES) {
                    "Project archive contains too many files."
                }
                val output = safeRelativeFile(destinationRoot, entry.name)
                if (entry.isDirectory) {
                    require(output.mkdirs() || output.isDirectory) {
                        "Could not create a project folder."
                    }
                } else {
                    output.parentFile?.let { parent ->
                        require(parent.mkdirs() || parent.isDirectory) {
                            "Could not create a project folder."
                        }
                    }
                    FileOutputStream(output).buffered().use { fileOut ->
                        var entryBytes = 0L
                        while (true) {
                            val read = zip.read(buffer)
                            if (read <= 0) break
                            entryBytes += read
                            totalBytes += read
                            require(entryBytes <= MAX_SINGLE_FILE_BYTES) {
                                "A project file is unexpectedly large."
                            }
                            require(totalBytes <= MAX_ARCHIVE_BYTES) {
                                "Project archive is unexpectedly large."
                            }
                            fileOut.write(buffer, 0, read)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
    }

    private fun verifyChecksumsIfPresent(manifest: JSONObject, root: File) {
        val checksumRoot = manifest.optJSONObject("checksums") ?: return
        if (!checksumRoot.optString("algorithm").equals("SHA-256", ignoreCase = true)) return
        val files = checksumRoot.optJSONObject("files") ?: return
        val keys = files.keys()
        while (keys.hasNext()) {
            val relativePath = keys.next()
            val expected = files.optString(relativePath).lowercase()
            if (expected.isBlank()) continue
            val file = safeRelativeFile(root, relativePath)
            require(file.isFile) { "Checksum target is missing: $relativePath" }
            require(sha256(file) == expected) {
                "Project file failed its integrity check: $relativePath"
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }

    private fun safeRelativeFile(root: File, relativePath: String): File {
        require(relativePath.isNotBlank()) { "Project contains an empty file path." }
        require(!relativePath.startsWith('/') && !relativePath.startsWith('\\')) {
            "Project contains an unsafe file path."
        }
        val canonicalRoot = root.canonicalFile
        val candidate = File(canonicalRoot, relativePath).canonicalFile
        val rootPrefix = canonicalRoot.path + File.separator
        require(candidate.path.startsWith(rootPrefix)) {
            "Project contains an unsafe file path."
        }
        return candidate
    }

    private companion object {
        const val MAX_PAGE_COUNT = 1_000
        const val MAX_ARCHIVE_ENTRIES = 1_100
        const val MAX_SINGLE_FILE_BYTES = 350L * 1024L * 1024L
        const val MAX_ARCHIVE_BYTES = 2_000L * 1024L * 1024L
    }
}
