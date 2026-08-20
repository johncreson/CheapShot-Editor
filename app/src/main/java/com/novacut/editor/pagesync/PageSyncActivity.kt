package com.novacut.editor.pagesync

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.novacut.editor.engine.FFmpegEngine
import com.novacut.editor.ui.theme.ClearCutAccents
import com.novacut.editor.ui.theme.ClearCutTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.max

@AndroidEntryPoint
class PageSyncActivity : ComponentActivity() {
    @Inject lateinit var ffmpegEngine: FFmpegEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ClearCutTheme {
                PageSyncScreen(
                    ffmpegEngine = ffmpegEngine,
                    onExit = { finish() },
                )
            }
        }
    }
}

@Composable
private fun PageSyncScreen(
    ffmpegEngine: FFmpegEngine,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val player = remember { ExoPlayer.Builder(context).build() }
    val exporter = remember(context, ffmpegEngine) { PageSyncExporter(context, ffmpegEngine) }

    var audioUri by remember { mutableStateOf<Uri?>(null) }
    var pages by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var cuePoints by remember { mutableStateOf<List<Long>>(emptyList()) }
    var currentPageIndex by remember { mutableIntStateOf(0) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var syncArmed by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableFloatStateOf(0f) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    var lastExportUri by remember { mutableStateOf<Uri?>(null) }

    // Pre-Q the public Movies collection needs WRITE_EXTERNAL_STORAGE
    // (manifest caps it at maxSdkVersion 28); Q+ uses MediaStore and needs
    // no permission.
    var hasLegacyStoragePermission by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val legacyStoragePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasLegacyStoragePermission = granted
        exportMessage = if (granted) {
            "Storage permission granted — tap Export again."
        } else {
            "Storage permission is required to save to Movies/CheapShot."
        }
    }

    fun resetTiming() {
        cuePoints = if (pages.isEmpty()) emptyList() else listOf(0L)
        currentPageIndex = 0
        syncArmed = false
        durationMs = 0L
        player.pause()
        player.seekTo(0L)
    }

    fun persistReadPermission(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            persistReadPermission(uri)
            audioUri = uri
            resetTiming()
        }
    }
    val pagesPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach(::persistReadPermission)
            pages = uris.sortedWith(pageUriComparator(context))
            cuePoints = listOf(0L)
            currentPageIndex = 0
            syncArmed = false
        }
    }
    val insertPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && pages.isNotEmpty()) {
            persistReadPermission(uri)
            val insertAt = (currentPageIndex + 1).coerceAtMost(pages.size)
            pages = pages.toMutableList().apply { add(insertAt, uri) }
            cuePoints = PageSyncTimeline.redoFrom(cuePoints, currentPageIndex, pages.size)
            syncArmed = true
            exportMessage = "Page inserted. Timing after this page is ready to redo."
        }
    }
    val replacePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && currentPageIndex in pages.indices) {
            persistReadPermission(uri)
            pages = pages.toMutableList().apply { this[currentPageIndex] = uri }
            exportMessage = "Page replaced. Existing timing was kept."
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) {
                isPlaying = value
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED) {
                    durationMs = player.duration.takeUnless { it == C.TIME_UNSET }?.coerceAtLeast(0L) ?: durationMs
                }
                if (playbackState == Player.STATE_ENDED) {
                    isPlaying = false
                    if (cuePoints.size == pages.size) syncArmed = false
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(audioUri) {
        val uri = audioUri ?: return@LaunchedEffect
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
    }

    LaunchedEffect(player, syncArmed, cuePoints, pages.size) {
        while (isActive) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            val playerDuration = player.duration
            if (playerDuration != C.TIME_UNSET && playerDuration > 0L) durationMs = playerDuration
            if (!syncArmed && pages.isNotEmpty() && cuePoints.isNotEmpty()) {
                currentPageIndex = PageSyncTimeline.pageIndexAt(positionMs, cuePoints, pages.size)
            }
            delay(50L)
        }
    }

    fun startFreshSync() {
        if (audioUri == null || pages.isEmpty()) return
        cuePoints = listOf(0L)
        currentPageIndex = 0
        syncArmed = true
        exportMessage = null
        player.seekTo(0L)
        player.play()
    }

    fun tapNextPage() {
        if (!syncArmed || pages.isEmpty()) return
        if (currentPageIndex >= pages.lastIndex) {
            syncArmed = false
            exportMessage = if (cuePoints.size >= pages.size) {
                "Sync captured. The last page will hold to the end of the soundtrack."
            } else {
                "Sync stopped with pages missing cues — redo from the unfinished page."
            }
            return
        }
        cuePoints = PageSyncTimeline.captureNextCue(
            cues = cuePoints,
            currentPageIndex = currentPageIndex,
            positionMs = player.currentPosition,
            pageCount = pages.size,
            totalDurationMs = durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE,
        )
        currentPageIndex += 1
    }

    fun seekBackThreeSeconds() {
        player.seekTo(max(0L, player.currentPosition - 3_000L))
    }

    fun previousPage() {
        if (pages.isEmpty()) return
        val target = (currentPageIndex - 1).coerceAtLeast(0)
        currentPageIndex = target
        val seek = cuePoints.getOrNull(target) ?: 0L
        player.seekTo(seek)
    }

    fun nudgeCurrentCue(deltaMs: Long) {
        if (currentPageIndex <= 0 || currentPageIndex >= cuePoints.size) return
        cuePoints = PageSyncTimeline.nudgeCue(
            cues = cuePoints,
            pageIndex = currentPageIndex,
            deltaMs = deltaMs,
            pageCount = pages.size,
            totalDurationMs = durationMs.coerceAtLeast(cuePoints.lastOrNull() ?: 0L),
        )
        cuePoints.getOrNull(currentPageIndex)?.let(player::seekTo)
    }

    fun redoFromHere() {
        if (pages.isEmpty() || cuePoints.isEmpty()) return
        val index = currentPageIndex.coerceAtMost(cuePoints.lastIndex)
        cuePoints = PageSyncTimeline.redoFrom(cuePoints, index, pages.size)
        currentPageIndex = index
        syncArmed = true
        val cue = cuePoints.getOrNull(index) ?: 0L
        player.seekTo((cue - 2_000L).coerceAtLeast(0L))
        exportMessage = "Redo armed from page ${index + 1}. Play, then tap NEXT PAGE at each change."
    }

    fun deleteCurrentPage() {
        if (currentPageIndex !in pages.indices) return
        val oldCount = pages.size
        pages = pages.toMutableList().apply { removeAt(currentPageIndex) }
        cuePoints = PageSyncTimeline.deleteCue(cuePoints, currentPageIndex, oldCount)
        currentPageIndex = currentPageIndex.coerceAtMost((pages.size - 1).coerceAtLeast(0))
        syncArmed = pages.isNotEmpty() && cuePoints.size < pages.size
    }

    fun moveCurrentPage(delta: Int) {
        if (currentPageIndex !in pages.indices) return
        val target = (currentPageIndex + delta).coerceIn(0, pages.lastIndex)
        if (target == currentPageIndex) return
        pages = pages.toMutableList().apply {
            val item = removeAt(currentPageIndex)
            add(target, item)
        }
        currentPageIndex = target
    }

    fun exportVideo() {
        val audio = audioUri ?: return
        if (pages.isEmpty()) return
        if (cuePoints.size != pages.size) {
            exportMessage = "Timing is incomplete. Finish the tap-through or redo from the unfinished page."
            return
        }
        if (durationMs <= 0L) {
            exportMessage = "The soundtrack duration is not available yet."
            return
        }
        if (!hasLegacyStoragePermission) {
            legacyStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        exporting = true
        exportProgress = 0f
        exportMessage = "Rendering vertical MP4…"
        lastExportUri = null
        scope.launch {
            val result = exporter.export(
                audioUri = audio,
                pageUris = pages,
                cuePointsMs = cuePoints,
                audioDurationMs = durationMs,
                onProgress = { progress ->
                    scope.launch(Dispatchers.Main) { exportProgress = progress }
                },
            )
            exporting = false
            if (result.error != null) {
                exportMessage = result.error
            } else {
                lastExportUri = result.publishedUri
                exportMessage = result.publishedUri?.let { "Saved to Movies/CheapShot." }
                    ?: result.file?.let { "Saved to ${it.absolutePath}" }
                    ?: "Export complete."
            }
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding(),
        topBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onExit) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("PAGE SYNC", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            if (syncArmed) "MANUAL CAPTURE" else "REVIEW / FIX",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (syncArmed) ClearCutAccents.Peach else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (exporting) {
                        CircularProgressIndicator(
                            progress = { exportProgress.coerceIn(0f, 1f) },
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 3.dp,
                        )
                    } else {
                        IconButton(onClick = ::exportVideo, enabled = audioUri != null && pages.isNotEmpty()) {
                            Icon(Icons.Default.VideoFile, contentDescription = "Export MP4")
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { audioPicker.launch(arrayOf("audio/*")) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.LibraryMusic, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (audioUri == null) "Soundtrack" else "Change audio", maxLines = 1)
                }
                OutlinedButton(
                    onClick = { pagesPicker.launch(arrayOf("image/*")) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Image, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (pages.isEmpty()) "Pages" else "${pages.size} pages", maxLines = 1)
                }
            }

            if (audioUri == null || pages.isEmpty()) {
                EmptyPageSyncCard(hasAudio = audioUri != null, pageCount = pages.size)
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Page ${currentPageIndex + 1} / ${pages.size}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${formatTime(positionMs)} / ${formatTime(durationMs)}",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.Black),
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        pages.getOrNull(currentPageIndex)?.let { uri ->
                            AsyncImage(
                                model = ImageRequest.Builder(context).data(uri).build(),
                                contentDescription = "Page ${currentPageIndex + 1}",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit,
                            )
                        }
                    }
                }

                if (syncArmed) {
                    Button(
                        onClick = ::tapNextPage,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(72.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ClearCutAccents.Sky),
                    ) {
                        Text(
                            if (currentPageIndex < pages.lastIndex) "NEXT PAGE  ▶" else "FINISH SYNC",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                } else {
                    Button(
                        onClick = ::startFreshSync,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (cuePoints.size == pages.size) "SYNC AGAIN FROM START" else "START MANUAL SYNC")
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ControlButton(Icons.Default.SkipPrevious, "Previous page", ::previousPage)
                    ControlButton(Icons.Default.FastRewind, "Back 3 seconds", ::seekBackThreeSeconds)
                    ControlButton(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (isPlaying) "Pause" else "Play",
                    ) {
                        if (player.isPlaying) player.pause() else player.play()
                    }
                    ControlButton(Icons.Default.Refresh, "Redo from here", ::redoFromHere)
                }

                if (currentPageIndex > 0 && currentPageIndex < cuePoints.size) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { nudgeCurrentCue(-500L) }) { Text("−0.5s") }
                        TextButton(onClick = { nudgeCurrentCue(-100L) }) { Text("−0.1s") }
                        Surface(shape = RoundedCornerShape(8.dp), tonalElevation = 2.dp) {
                            Text(
                                "Cue ${formatTime(cuePoints[currentPageIndex])}",
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                        TextButton(onClick = { nudgeCurrentCue(100L) }) { Text("+0.1s") }
                        TextButton(onClick = { nudgeCurrentCue(500L) }) { Text("+0.5s") }
                    }
                }

                HorizontalDivider()

                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    itemsIndexed(pages, key = { index, uri -> "$index:$uri" }) { index, uri ->
                        PageThumbnail(
                            uri = uri,
                            pageNumber = index + 1,
                            selected = index == currentPageIndex,
                            onClick = {
                                // While a sync is armed, jumping past the
                                // captured range would file the next tap's cue
                                // against the wrong page — clamp instead.
                                currentPageIndex = if (syncArmed) {
                                    index.coerceAtMost((cuePoints.size - 1).coerceAtLeast(0))
                                } else {
                                    index
                                }
                                cuePoints.getOrNull(currentPageIndex)?.let(player::seekTo)
                            },
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { insertPicker.launch(arrayOf("image/*")) }) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Text("Insert")
                    }
                    TextButton(onClick = { replacePicker.launch(arrayOf("image/*")) }) {
                        Icon(Icons.Default.SwapHoriz, contentDescription = null)
                        Text("Replace")
                    }
                    IconButton(onClick = { moveCurrentPage(-1) }, enabled = currentPageIndex > 0) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Move page left")
                    }
                    IconButton(onClick = { moveCurrentPage(1) }, enabled = currentPageIndex < pages.lastIndex) {
                        Icon(Icons.Default.ArrowForward, contentDescription = "Move page right")
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = ::deleteCurrentPage, enabled = pages.isNotEmpty()) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete page")
                    }
                }

                exportMessage?.let { message ->
                    Text(
                        message,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (!exporting && cuePoints.size == pages.size) {
                    Button(onClick = ::exportVideo, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Save, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("EXPORT VERTICAL MP4")
                    }
                }

                lastExportUri?.let { uri ->
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW)
                                        .setDataAndType(uri, "video/mp4")
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.VideoFile, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("WATCH EXPORTED VIDEO")
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyPageSyncCard(hasAudio: Boolean, pageCount: Int) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Manual picture-book timing", style = MaterialTheme.typography.titleLarge)
            Text(
                "Choose one soundtrack and the ordered page images. Then press Start Manual Sync and tap NEXT PAGE while the narration plays. No waveform required.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Soundtrack: ${if (hasAudio) "ready" else "needed"}   •   Pages: $pageCount",
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun ControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick) {
            Icon(icon, contentDescription = label)
        }
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
private fun PageThumbnail(
    uri: Uri,
    pageNumber: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .width(66.dp)
            .clip(RoundedCornerShape(8.dp))
            .then(
                if (selected) Modifier.border(2.dp, ClearCutAccents.Sky, RoundedCornerShape(8.dp))
                else Modifier,
            )
            .clickable(onClick = onClick)
            .padding(3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context).data(uri).build(),
            contentDescription = "Page $pageNumber",
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(9f / 16f)
                .clip(RoundedCornerShape(5.dp))
                .background(androidx.compose.ui.graphics.Color.Black),
            contentScale = ContentScale.Crop,
        )
        Text("$pageNumber", style = MaterialTheme.typography.labelSmall)
    }
}

private fun pageUriComparator(context: Context): Comparator<Uri> = Comparator { left, right ->
    val leftName = displayName(context, left)
    val rightName = displayName(context, right)
    val leftNumber = Regex("\\d+").find(leftName)?.value?.toLongOrNull()
    val rightNumber = Regex("\\d+").find(rightName)?.value?.toLongOrNull()
    when {
        leftNumber != null && rightNumber != null && leftNumber != rightNumber -> leftNumber.compareTo(rightNumber)
        else -> leftName.compareTo(rightName, ignoreCase = true)
    }
}

private fun displayName(context: Context, uri: Uri): String {
    return runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()
}

private fun formatTime(ms: Long): String {
    val safe = ms.coerceAtLeast(0L)
    val minutes = safe / 60_000L
    val seconds = (safe % 60_000L) / 1_000L
    val tenths = (safe % 1_000L) / 100L
    return "%d:%02d.%d".format(minutes, seconds, tenths)
}
