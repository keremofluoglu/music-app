package com.example.musicapp.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.musicapp.data.db.ListenMode
import com.example.musicapp.data.db.RepeatMode
import com.example.musicapp.ui.theme.AppTheme
import kotlinx.coroutines.delay

@Composable
fun NowPlayingScreen(
    vm: MainViewModel,
    onClose: () -> Unit,
    onArtist: (() -> Unit)?,
    onSleep: () -> Unit,
    onQueue: () -> Unit,
) {
    val state by vm.player.state.collectAsState()
    val settings by vm.settings.collectAsState()
    val song by remember(state.currentId) {
        state.currentId?.let { vm.currentSong(it) } ?: kotlinx.coroutines.flow.flowOf(null)
    }.collectAsState(initial = null)
    var position by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    var showProps by remember { mutableStateOf(false) }

    LaunchedEffect(state.currentId, state.isPlaying) {
        while (true) {
            position = vm.player.positionMs().toFloat()
            if (!state.isPlaying) break
            delay(500)
        }
    }

    // Swipe down anywhere closes the screen (an alternative to the ⌄ button). The screen follows
    // the finger; released early it springs back.
    val close by rememberUpdatedState(onClose)
    var swipeY by remember { mutableFloatStateOf(0f) }
    var swiping by remember { mutableStateOf(false) }
    var swipeClosing by remember { mutableStateOf(false) }
    val shownY by animateFloatAsState(
        targetValue = if (swiping || swipeClosing) swipeY else 0f,
        animationSpec = if (swiping) snap() else spring(),
        label = "nowPlayingSwipe",
    )

    Column(
        Modifier
            .fillMaxSize()
            .graphicsLayer { translationY = shownY }
            .pointerInput(Unit) {
                val threshold = 120.dp.toPx()
                detectVerticalDragGestures(
                    onDragStart = { swiping = true; swipeY = 0f },
                    onDragEnd = {
                        swiping = false
                        if (swipeY > threshold) {
                            swipeClosing = true
                            close()
                        }
                    },
                    onDragCancel = { swiping = false },
                ) { change, dy ->
                    change.consume()
                    swipeY = (swipeY + dy).coerceAtLeast(0f)
                }
            }
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(AppIcons.Down, "Kapat") }
            Column(Modifier.weight(1f).clickable(onClick = onQueue), horizontalAlignment = Alignment.CenterHorizontally) {
                val modeLabel = when {
                    settings?.shuffle != true -> "SIRAYLA"
                    settings?.homeMode == ListenMode.FAV -> "SON FAVORİLER MODUNDAN"
                    else -> "YENİ BİR ŞEYLER DENE MODUNDAN"
                }
                Text(modeLabel, style = MaterialTheme.typography.labelSmall, color = AppTheme.colors.muted, fontWeight = FontWeight.Bold)
                Text("Kuyruk · ${state.queueSize} şarkı", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }
            IconButton(onClick = { showProps = true }, enabled = state.currentId != null) { Icon(AppIcons.More, "Özellikler") }
        }

        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
            CoverPlaceholder(state.title.ifEmpty { "♪" }, 300.dp)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(state.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    state.artist,
                    color = AppTheme.colors.muted,
                    fontWeight = FontWeight.SemiBold,
                    modifier = if (onArtist != null) Modifier.clickable(onClick = onArtist) else Modifier,
                )
            }
            val liked = song?.liked == true
            IconButton(onClick = { song?.let { vm.toggleLike(it.id, it.liked) } }, enabled = song != null) {
                Icon(
                    if (liked) AppIcons.Favorite else AppIcons.FavoriteBorder,
                    if (liked) "Beğeniyi kaldır" else "Beğen",
                    tint = if (liked) AppTheme.colors.primary else MaterialTheme.colorScheme.onBackground,
                )
            }
        }

        Column {
            val duration = state.durationMs.toFloat().coerceAtLeast(1f)
            Slider(
                value = (dragging ?: position).coerceIn(0f, duration),
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { vm.player.seekTo(it.toLong()); position = it }
                    dragging = null
                },
                valueRange = 0f..duration,
                colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.onBackground, activeTrackColor = AppTheme.colors.primary),
            )
            Row {
                Text(formatTime((dragging ?: position).toLong()), style = MaterialTheme.typography.labelMedium, color = AppTheme.colors.muted)
                Spacer(Modifier.weight(1f))
                Text(formatTime(state.durationMs), style = MaterialTheme.typography.labelMedium, color = AppTheme.colors.muted)
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::toggleShuffle) {
                Icon(
                    AppIcons.Shuffle,
                    if (settings?.shuffle == true) "Karıştırma açık" else "Karıştırma kapalı",
                    tint = if (settings?.shuffle == true) AppTheme.colors.primary else AppTheme.colors.muted,
                )
            }
            IconButton(onClick = vm.player::previous, modifier = Modifier.size(56.dp)) { Icon(AppIcons.Previous, "Önceki", Modifier.size(32.dp)) }
            FilledIconButton(onClick = vm.player::togglePlay, modifier = Modifier.size(72.dp), shape = CircleShape) {
                Icon(if (state.isPlaying) AppIcons.Pause else AppIcons.Play, if (state.isPlaying) "Duraklat" else "Çal", Modifier.size(34.dp))
            }
            IconButton(onClick = vm.player::next, modifier = Modifier.size(56.dp)) { Icon(AppIcons.Next, "Sonraki", Modifier.size(32.dp)) }
            val repeat = settings?.repeat ?: RepeatMode.OFF
            IconButton(onClick = vm::cycleRepeat) {
                Icon(
                    if (repeat == RepeatMode.ONE) AppIcons.RepeatOne else AppIcons.Repeat,
                    when (repeat) {
                        RepeatMode.OFF -> "Tekrar kapalı"
                        RepeatMode.ALL -> "Tüm listeyi tekrarla"
                        RepeatMode.ONE -> "Aynı şarkıyı tekrarla"
                    },
                    tint = if (repeat == RepeatMode.OFF) AppTheme.colors.muted else AppTheme.colors.primary,
                )
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onQueue, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) {
                Icon(AppIcons.Queue, null, Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("Sıradakiler", maxLines = 1)
            }
            OutlinedButton(onClick = onSleep, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) {
                Icon(AppIcons.Moon, null, Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("Uyku zamanlayıcı", maxLines = 1)
            }
        }
    }

    if (showProps) state.currentId?.let { PropertiesDialog(it, vm) { showProps = false } }
}

/** The play queue: the current song, then what comes next. Tap to jump, × to remove. */
@Composable
fun QueueScreen(vm: MainViewModel, onBack: () -> Unit) {
    val state by vm.player.state.collectAsState()
    val current = state.index
    val upcoming = state.queue.filter { it.index > current }
    val now = state.queue.getOrNull(current)
    // Scores come from the library rows (song_stats), so they update live after every play.
    val library by vm.songs.collectAsState()
    val byId = remember(library) { library.associateBy { it.id } }
    val settings by vm.settings.collectAsState()
    val showScores = settings?.showScores ?: true
    val mode = settings?.homeMode ?: ListenMode.NEW
    @Composable
    fun ScoredItem(row: com.example.musicapp.playback.QueueRow, isCurrent: Boolean, onClick: () -> Unit, onRemove: (() -> Unit)?) {
        val song = byId[row.songId]
        QueueItem(row, isCurrent, onClick, onRemove, scores = if (showScores && song != null) Triple(song.discoveryScore, song.repeatScore, mode) else null)
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(AppIcons.Down, "Kapat") }
                Text("Kuyruk", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
            }
        }
        if (now == null) {
            item { EmptyState("Kuyruk boş", "Bir şarkıya veya \"Karıştır ve çal\"a dokun.") }
        } else {
            item { QueueHeader("Şimdi çalıyor") }
            item { ScoredItem(now, isCurrent = true, onClick = {}, onRemove = null) }
            item { QueueHeader("Sıradakiler · ${upcoming.size} şarkı") }
            items(upcoming, key = { "${it.index}-${it.songId}" }) { row ->
                ScoredItem(row, isCurrent = false, onClick = { vm.player.playAt(row.index) }, onRemove = { vm.player.removeAt(row.index) })
            }
        }
    }
}

@Composable
private fun QueueHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = AppTheme.colors.muted,
        modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun QueueItem(
    row: com.example.musicapp.playback.QueueRow,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onRemove: (() -> Unit)?,
    /** Discovery, repeat and the active home mode; null hides them. */
    scores: Triple<Double?, Double?, ListenMode>? = null,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CoverPlaceholder(row.title, 44.dp)
        Column(Modifier.weight(1f)) {
            Text(
                row.title,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isCurrent) AppTheme.colors.primaryText else MaterialTheme.colorScheme.onBackground,
            )
            Text(row.artist, style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.muted, maxLines = 1)
        }
        scores?.let { (d, r, mode) -> ScorePair(d, r, mode) }
        if (onRemove != null) {
            IconButton(onClick = onRemove) { Icon(AppIcons.Close, "Kuyruktan çıkar", tint = AppTheme.colors.muted) }
        }
    }
}

@Composable
fun SleepTimerDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Uyku zamanlayıcı") },
        text = {
            Column {
                for (m in listOf(15, 30, 45, 60, 90)) {
                    TextButton(onClick = { vm.setSleepTimer(m); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                        Text("$m dakika", modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.setSleepTimer(0); onDismiss() }) { Text("Kapat") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } },
    )
}
