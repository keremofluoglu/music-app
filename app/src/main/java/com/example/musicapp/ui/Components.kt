package com.example.musicapp.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.musicapp.data.db.ListenMode
import com.example.musicapp.data.db.SongListItem
import com.example.musicapp.data.db.VersionType
import com.example.musicapp.playback.PlayerConnection
import com.example.musicapp.playback.PlayerUiState
import com.example.musicapp.ui.theme.AppTheme
import kotlinx.coroutines.delay
import java.util.Locale

private val COVER_COLORS = listOf(
    0xFFC2412F, 0xFFD2463A, 0xFFB9800F, 0xFFA63D8F, 0xFF1F7A6A, 0xFF8E3B7E, 0xFF7C8CFF, 0xFF3FD0A4,
).map { Color(it) }

/** Placeholder cover: first letter on a color derived from the name (docs/kucuk-ozellikler.md §6). */
@Composable
fun CoverPlaceholder(name: String, size: Dp, round: Boolean = false) {
    val color = COVER_COLORS[(name.hashCode() and Int.MAX_VALUE) % COVER_COLORS.size]
    Box(
        Modifier.size(size).clip(if (round) CircleShape else RoundedCornerShape(8.dp)).background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().firstOrNull()?.uppercase(Locale.forLanguageTag("tr")) ?: "♪",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.38f).sp,
        )
    }
}

/** Text badge for the rarer versions; original and cover are shown as the colored dot instead. */
fun versionLabel(v: VersionType?): String? = when (v) {
    null, VersionType.ORIGINAL, VersionType.COVER -> null
    VersionType.REMIX -> "Remix"
    VersionType.LIVE -> "Canlı"
    VersionType.ACOUSTIC -> "Akustik"
    VersionType.INSTRUMENTAL -> "Enstrümantal"
}

data class MenuAction(val label: String, val onClick: () -> Unit)

/** A song row; long press or ⋮ opens a small dropdown menu next to the song. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongRow(
    song: SongListItem,
    isCurrent: Boolean,
    onClick: () -> Unit,
    menu: () -> List<MenuAction>,
    trailing: String? = null,
    /** Shows both scores flush right instead of the ⋮ button (long press still opens the menu). */
    showScores: Boolean = false,
    /** Home mode in use; its score is highlighted. */
    activeMode: ListenMode = ListenMode.NEW,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .combinedClickable(onClick = onClick, onLongClick = { expanded = true })
            .padding(start = 20.dp, end = if (showScores) 16.dp else 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CoverPlaceholder(song.title, 52.dp)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                VersionDot(song.versionType)
                Text(
                    song.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Bold,
                    color = if (isCurrent) AppTheme.colors.primaryText else MaterialTheme.colorScheme.onBackground,
                )
            }
            Text(
                song.artistNames ?: "Bilinmeyen sanatçı",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.colors.muted,
            )
        }
        versionLabel(song.versionType)?.let { Badge(it) }
        trailing?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = AppTheme.colors.muted) }
        Box {
            if (showScores) {
                ScorePair(song.discoveryScore, song.repeatScore, activeMode)
            } else {
                IconButton(onClick = { expanded = true }) {
                    Icon(AppIcons.More, contentDescription = "Seçenekler", tint = AppTheme.colors.muted)
                }
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                for (action in menu()) {
                    DropdownMenuItem(
                        text = { Text(action.label) },
                        onClick = {
                            expanded = false
                            action.onClick()
                        },
                    )
                }
            }
        }
    }
}

/** Original vs cover at a glance (proje.txt line 43). Unmarked songs have no dot. */
val ORIGINAL_DOT = Color(0xFF3FD0A4)
val COVER_DOT = Color(0xFFF2994A)

@Composable
fun VersionDot(type: VersionType?) {
    val color = when (type) {
        VersionType.ORIGINAL -> ORIGINAL_DOT
        VersionType.COVER -> COVER_DOT
        else -> return
    }
    Box(
        Modifier
            .padding(end = 6.dp)
            .size(8.dp)
            .clip(CircleShape)
            .background(color)
            .semantics { contentDescription = if (type == VersionType.COVER) "Cover" else "Orijinal" },
    )
}

/** Picks the version label of a song by hand. */
@Composable
fun VersionDialog(current: VersionType?, onDismiss: () -> Unit, onPick: (VersionType?) -> Unit) {
    val options = listOf(
        null to "Bilinmiyor",
        VersionType.ORIGINAL to "Orijinal",
        VersionType.COVER to "Cover",
        VersionType.REMIX to "Remix",
        VersionType.LIVE to "Canlı",
        VersionType.ACOUSTIC to "Akustik",
        VersionType.INSTRUMENTAL to "Enstrümantal",
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sürüm türü") },
        text = {
            Column {
                for ((type, label) in options) {
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(type) }.heightIn(min = 44.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.RadioButton(selected = type == current, onClick = { onPick(type) })
                        Text(label, Modifier.weight(1f))
                        VersionDot(type)
                    }
                }
                Text(
                    "Yalnızca uygulamada görünür; dosyaya yazılmaz. Puanlamayı etkilemez.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTheme.colors.muted,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } },
    )
}

/** ✦ discovery and ♥ repeat score, stacked; icons match the two mode cards on the home screen. */
@Composable
fun ScorePair(discovery: Double?, repeat: Double?, activeMode: ListenMode) {
    val tr = Locale.forLanguageTag("tr")
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ScoreLine(AppIcons.Sparkle, "Keşif puanı", discovery?.let { "%.2f".format(tr, it) } ?: "–", activeMode == ListenMode.NEW)
        ScoreLine(AppIcons.Favorite, "Tekrarlama puanı", repeat?.let { "%.2f".format(tr, it) } ?: "–", activeMode == ListenMode.FAV)
    }
}

/** The score of the home mode in use is drawn in the theme color; the other stays grey. */
@Composable
private fun ScoreLine(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, active: Boolean) {
    val color = if (active) AppTheme.colors.primaryText else AppTheme.colors.muted
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            value,
            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
            fontWeight = if (active) FontWeight.ExtraBold else FontWeight.SemiBold,
            color = color,
        )
        Icon(icon, contentDescription = label, modifier = Modifier.size(12.dp), tint = color)
    }
}

@Composable
fun Badge(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(AppTheme.colors.selected)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.ExtraBold,
        modifier = modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

@Composable
fun EmptyState(title: String, body: String, action: (@Composable () -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(body, color = AppTheme.colors.muted, style = MaterialTheme.typography.bodyMedium)
        action?.invoke()
    }
}

@Composable
fun MiniPlayer(state: PlayerUiState, connection: PlayerConnection, onOpen: () -> Unit) {
    if (state.currentId == null) return
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(state.currentId, state.isPlaying, state.durationMs) {
        while (true) {
            progress = if (state.durationMs > 0) connection.positionMs().toFloat() / state.durationMs else 0f
            if (!state.isPlaying) break
            delay(500)
        }
    }
    // Swipe up on the bar opens the now-playing screen (an alternative to tapping).
    val open by rememberUpdatedState(onOpen)
    var dragY by remember { mutableFloatStateOf(0f) }
    val lift by animateFloatAsState(dragY, label = "miniLift")
    Column(
        Modifier
            .padding(horizontal = 8.dp)
            .graphicsLayer { translationY = lift }
            .pointerInput(Unit) {
                val threshold = 48.dp.toPx()
                val maxLift = 24.dp.toPx()
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = {
                        if (total < -threshold) open()
                        dragY = 0f
                    },
                    onDragCancel = { dragY = 0f },
                ) { change, dy ->
                    change.consume()
                    total += dy
                    dragY = total.coerceIn(-maxLift, 0f)
                }
            },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(onClick = onOpen)
                .padding(start = 8.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CoverPlaceholder(state.title, 42.dp)
            Column(Modifier.weight(1f)) {
                Text(state.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                Text(state.artist, maxLines = 1, style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.muted)
            }
            IconButton(onClick = connection::togglePlay) {
                Icon(
                    if (state.isPlaying) AppIcons.Pause else AppIcons.Play,
                    contentDescription = if (state.isPlaying) "Duraklat" else "Çal",
                )
            }
        }
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(3.dp),
            color = AppTheme.colors.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
    }
}

/** Picks a playlist for [song]; also lets the user create a new one with the song in it. */
@Composable
fun AddToPlaylistDialog(song: SongListItem, vm: MainViewModel, onDismiss: () -> Unit) {
    val playlists by vm.playlists.collectAsState()
    var creating by remember { mutableStateOf(false) }
    if (creating) {
        NameDialog("Yeni çalma listesi", "", "Oluştur", onDismiss = onDismiss) { name ->
            vm.createPlaylist(name, firstSong = song)
            onDismiss()
        }
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Çalma listesine ekle") },
        text = {
            Column {
                Row(
                    Modifier.fillMaxWidth().clickable { creating = true }.heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(AppIcons.Add, null, tint = AppTheme.colors.primaryText)
                    Spacer(Modifier.width(12.dp))
                    Text("Yeni çalma listesi", color = AppTheme.colors.primaryText, fontWeight = FontWeight.Bold)
                }
                for (p in playlists) {
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.addToPlaylist(p.id, song, p.name); onDismiss() }.heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(p.name, Modifier.weight(1f))
                        Text("${p.songCount}", color = AppTheme.colors.muted, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } },
    )
}

/** Single text field dialog (new playlist, rename). */
@Composable
fun NameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, placeholder = { Text("Liste adı") })
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } },
    )
}

/** File size, both scores, real file name and location; read-only. */
@Composable
fun PropertiesDialog(songId: String, vm: MainViewModel, onDismiss: () -> Unit) {
    var props by remember { mutableStateOf<SongProperties?>(null) }
    LaunchedEffect(songId) { props = vm.properties(songId) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Kapat") } },
        title = { Text("Özellikler") },
        text = {
            val p = props
            if (p == null) {
                Text("Yükleniyor…")
            } else {
                val path = p.song.filePath
                val fileName = path.substringAfterLast('/')
                val folder = path.substringBeforeLast('/', "").ifEmpty { "/" } + "/"
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Property("Dosya boyutu", formatSize(p.song.fileSize))
                    Property("Keşif puanı (Yeni bir şeyler dene)", "%.2f".format(Locale.forLanguageTag("tr"), p.stats?.discoveryScore ?: 0.0))
                    Property("Tekrarlama puanı (Son favoriler)", "%.2f".format(Locale.forLanguageTag("tr"), p.stats?.repeatScore ?: 0.0))
                    Property("Dosyanın asıl adı", fileName)
                    Property("Dosyanın konumu", folder + if (p.song.isMissing) "  ·  Dosya bulunamadı" else "")
                }
            }
        },
    )
}

@Composable
private fun Property(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = AppTheme.colors.muted)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

fun formatSize(bytes: Long): String =
    if (bytes >= 1_000_000) "%.1f MB".format(Locale.forLanguageTag("tr"), bytes / 1_000_000.0)
    else "%d KB".format(bytes / 1_000)

fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}
