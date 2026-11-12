package com.example.musicapp.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.musicapp.data.db.ListenMode
import com.example.musicapp.data.db.SongListItem
import com.example.musicapp.ui.theme.Appearance
import com.example.musicapp.ui.theme.AppTheme
import com.example.musicapp.ui.theme.ThemeColor
import com.example.musicapp.ui.theme.themePalette
import kotlinx.coroutines.flow.Flow
import java.util.Calendar

/** Shared by every list: tap plays, long press or ⋮ opens the menu. */
class SongListActions(
    val vm: MainViewModel,
    val goArtist: (Long) -> Unit,
    val goAlbum: (Long) -> Unit,
    val goPlaylist: (Long) -> Unit,
    /** Opens the "Şarkı ekle" picker of a user playlist. */
    val addSongs: (Long) -> Unit,
)

/** IDs of the built-in playlists; user playlists have positive IDs. */
object BuiltInPlaylist {
    const val ALL = -1L
    const val LIKED = -2L
}

@Composable
fun SongListBody(
    songs: List<SongListItem>,
    contextKey: String,
    actions: SongListActions,
    header: @Composable (() -> Unit)? = null,
    /** Set inside a user playlist, so the menu can offer "Bu listeden çıkar". */
    playlistId: Long? = null,
    contentPadding: PaddingValues = PaddingValues(bottom = 16.dp),
) {
    val vm = actions.vm
    val playerState by vm.player.state.collectAsState()
    val settings by vm.settings.collectAsState()
    val showScores = settings?.showScores ?: true
    var propsFor by remember { mutableStateOf<String?>(null) }
    var addFor by remember { mutableStateOf<SongListItem?>(null) }
    var versionFor by remember { mutableStateOf<SongListItem?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        header?.let { item { it() } }
        items(songs, key = { it.id }) { song ->
            SongRow(
                song = song,
                isCurrent = song.id == playerState.currentId,
                onClick = { vm.play(songs, song, contextKey) },
                showScores = showScores,
                activeMode = settings?.homeMode ?: ListenMode.NEW,
                menu = {
                    buildList {
                        add(MenuAction("Sıradaki olarak çal") { vm.playNext(song) })
                        add(MenuAction("Kuyruğa ekle") { vm.addToQueue(song) })
                        add(MenuAction("Çalma listesine ekle") { addFor = song })
                        add(MenuAction(if (song.liked) "Beğeniyi kaldır" else "Beğen") { vm.toggleLike(song.id, song.liked) })
                        song.albumId?.let { id -> add(MenuAction("Albüme git") { actions.goAlbum(id) }) }
                        playlistId?.let { id -> add(MenuAction("Bu listeden çıkar") { vm.removeFromPlaylist(id, song.id) }) }
                        add(MenuAction("Sürüm türü (orijinal / cover)…") { versionFor = song })
                        add(MenuAction("Özellikler") { propsFor = song.id })
                        add(MenuAction("Kütüphaneden kaldır") { vm.removeFromLibrary(song.id) })
                    }
                },
            )
        }
    }
    propsFor?.let { PropertiesDialog(it, vm) { propsFor = null } }
    addFor?.let { AddToPlaylistDialog(it, vm) { addFor = null } }
    versionFor?.let { song ->
        VersionDialog(song.versionType, onDismiss = { versionFor = null }) { type ->
            vm.setVersion(song.id, type)
            versionFor = null
        }
    }
}

// ---- Home -------------------------------------------------------------------------------

@Composable
fun HomeScreen(vm: MainViewModel, actions: SongListActions, onSettings: () -> Unit) {
    val settings by vm.settings.collectAsState()
    val mode = settings?.homeMode ?: ListenMode.NEW
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 16.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("PLAK", color = AppTheme.colors.primaryText, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                        Text(greeting(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                    }
                    RoundIcon(AppIcons.Settings, "Ayarlar", onSettings)
                }
                Text("BUGÜN NE DİNLEMEK İSTERSİN?", style = MaterialTheme.typography.labelMedium, color = AppTheme.colors.muted)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ModeCard(
                        Modifier.weight(1f), AppIcons.Sparkle, "Yeni bir şeyler dene", "Az ve uzun süredir dinlemediklerin",
                        selected = mode == ListenMode.NEW,
                    ) { vm.setHomeMode(ListenMode.NEW) }
                    ModeCard(
                        Modifier.weight(1f), AppIcons.Favorite, "Son favoriler", "Son zamanlarda sık dinlediklerin",
                        selected = mode == ListenMode.FAV,
                    ) { vm.setHomeMode(ListenMode.FAV) }
                }
                Button(onClick = { vm.shufflePlay() }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Icon(AppIcons.Play, null, Modifier.size(20.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Karıştır ve çal", fontWeight = FontWeight.ExtraBold)
                }
                Text("Çalma listelerin", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
        item { PlaylistGrid(vm, actions) }
    }
}

/** "Tüm şarkılar", "Beğenilenler", the user's playlists and a card to create a new one. */
@Composable
fun PlaylistGrid(vm: MainViewModel, actions: SongListActions) {
    val songs by vm.songs.collectAsState()
    val likedCount by vm.likedCount.collectAsState()
    val playlists by vm.playlists.collectAsState()
    var creating by remember { mutableStateOf(false) }
    val cards = buildList<@Composable (Modifier) -> Unit> {
        add { m -> PlaylistCard(m, "Tüm şarkılar", "${songs.size} şarkı", AppIcons.Library, AppTheme.colors.primary) { actions.goPlaylist(BuiltInPlaylist.ALL) } }
        add { m -> PlaylistCard(m, "Beğenilenler", "$likedCount şarkı", AppIcons.Favorite, Color(0xFFD2463A)) { actions.goPlaylist(BuiltInPlaylist.LIKED) } }
        for (p in playlists) {
            add { m -> PlaylistCard(m, p.name, "${p.songCount} şarkı", AppIcons.Queue, playlistColor(p.name)) { actions.goPlaylist(p.id) } }
        }
        add { m -> NewPlaylistCard(m) { creating = true } }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (pair in cards.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair[0](Modifier.weight(1f))
                if (pair.size > 1) pair[1](Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
            }
        }
    }
    if (creating) {
        NameDialog("Yeni çalma listesi", "", "Oluştur", onDismiss = { creating = false }) {
            // Straight into the new (empty) list, where "Şarkı ekle" is waiting.
            vm.createPlaylist(it) { id -> actions.goPlaylist(id) }
            creating = false
        }
    }
}

private fun playlistColor(name: String): Color {
    val colors = listOf(0xFF7C8CFF, 0xFF3FD0A4, 0xFFF2C94C, 0xFFA63D8F, 0xFF1F7A6A, 0xFFB9800F).map { Color(it) }
    return colors[(name.hashCode() and Int.MAX_VALUE) % colors.size]
}

@Composable
private fun PlaylistCard(
    modifier: Modifier,
    name: String,
    sub: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .heightIn(min = 60.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.layout.Box(
            Modifier.size(60.dp).background(color),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = Color(0xFF0E0F12)) }
        Column(Modifier.padding(horizontal = 10.dp)) {
            Text(name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(sub, style = MaterialTheme.typography.labelSmall, color = AppTheme.colors.muted)
        }
    }
}

@Composable
private fun NewPlaylistCard(modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, AppTheme.colors.line, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .heightIn(min = 60.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppIcons.Add, null, tint = AppTheme.colors.primaryText)
        Spacer(Modifier.size(8.dp))
        Text("Yeni liste", fontWeight = FontWeight.Bold, color = AppTheme.colors.primaryText, maxLines = 1)
    }
}

/** A playlist page: built-in ("Tüm şarkılar", "Beğenilenler") or the user's own. */
@Composable
fun PlaylistScreen(id: Long, vm: MainViewModel, actions: SongListActions, onBack: () -> Unit) {
    val userList by remember(id) {
        if (id > 0) vm.playlist(id) else kotlinx.coroutines.flow.flowOf(null)
    }.collectAsState(initial = null)
    val songsFlow = remember(id) {
        when (id) {
            BuiltInPlaylist.ALL -> vm.songs
            BuiltInPlaylist.LIKED -> vm.liked
            else -> vm.songsOfPlaylist(id)
        }
    }
    val songs by songsFlow.collectAsState(initial = emptyList())
    val title = when (id) {
        BuiltInPlaylist.ALL -> "Tüm şarkılar"
        BuiltInPlaylist.LIKED -> "Beğenilenler"
        else -> userList?.name ?: ""
    }
    val contextKey = when (id) {
        BuiltInPlaylist.ALL -> "library"
        BuiltInPlaylist.LIKED -> "liked"
        else -> "playlist:$id"
    }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    SongListBody(
        songs, contextKey, actions, playlistId = id.takeIf { it > 0 },
        header = {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(AppIcons.Back, "Geri") }
                    Spacer(Modifier.weight(1f))
                    if (id > 0) {
                        androidx.compose.foundation.layout.Box {
                            IconButton(onClick = { menu = true }) { Icon(AppIcons.More, "Liste seçenekleri") }
                            androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                androidx.compose.material3.DropdownMenuItem(text = { Text("Yeniden adlandır") }, onClick = { menu = false; renaming = true })
                                androidx.compose.material3.DropdownMenuItem(text = { Text("Listeyi sil") }, onClick = { menu = false; confirmDelete = true })
                            }
                        }
                    }
                }
                Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.ExtraBold)
                Text("${songs.size} şarkı", color = AppTheme.colors.muted)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { vm.shufflePlay(songs, contextKey) }, enabled = songs.isNotEmpty()) {
                        Icon(AppIcons.Shuffle, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Karıştır", fontWeight = FontWeight.Bold)
                    }
                    if (id > 0) {
                        OutlinedButton(onClick = { actions.addSongs(id) }) {
                            Icon(AppIcons.Add, null, Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("Şarkı ekle", fontWeight = FontWeight.Bold)
                        }
                    }
                }
                if (songs.isEmpty()) {
                    EmptyState(
                        "Liste boş",
                        if (id == BuiltInPlaylist.LIKED) "Bir şarkıyı beğenmek için kalbe dokun."
                        else "\"Şarkı ekle\"ye dokun, sonra eklemek istediğin şarkılara dokun.",
                    )
                }
            }
        },
    )

    if (renaming) {
        NameDialog("Yeniden adlandır", title, "Kaydet", onDismiss = { renaming = false }) {
            vm.renamePlaylist(id, it)
            renaming = false
        }
    }
    if (confirmDelete) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("\"$title\" silinsin mi?") },
            text = { Text("Yalnızca liste silinir; şarkılar kütüphanede kalır.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { confirmDelete = false; vm.deletePlaylist(id); onBack() }) { Text("Sil") }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmDelete = false }) { Text("Vazgeç") } },
        )
    }
}

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Günaydın"
    in 12..17 -> "İyi günler"
    else -> "İyi akşamlar"
}

@Composable
private fun RoundIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
    ) { Icon(icon, label) }
}

@Composable
private fun ModeCard(
    modifier: Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = modifier.heightIn(min = 112.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) AppTheme.colors.selected else MaterialTheme.colorScheme.surfaceVariant,
        ),
        border = BorderStroke(2.dp, if (selected) AppTheme.colors.primary else MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null)
            Text(title, fontWeight = FontWeight.ExtraBold)
            Text(body, style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.muted)
        }
    }
}

// ---- Library ----------------------------------------------------------------------------

private enum class LibraryTab(val label: String) {
    PLAYLISTS("Çalma listeleri"), SONGS("Şarkılar"), ARTISTS("Sanatçılar"), ALBUMS("Albümler")
}

@Composable
fun LibraryScreen(vm: MainViewModel, actions: SongListActions) {
    var tab by rememberSaveable { mutableStateOf(LibraryTab.PLAYLISTS) }
    Column(Modifier.fillMaxSize()) {
        Text(
            "Kütüphane",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(start = 20.dp, top = 24.dp, bottom = 8.dp),
        )
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(LibraryTab.entries) { t ->
                FilterChip(selected = t == tab, onClick = { tab = t }, label = { Text(t.label, fontWeight = FontWeight.Bold) })
            }
        }
        when (tab) {
            LibraryTab.SONGS -> {
                val songs by vm.songs.collectAsState()
                if (songs.isEmpty()) EmptyState("Telefonda müzik bulunamadı", "Ayarlar'dan müzik klasörünü yeniden tarayabilirsin.")
                SongListBody(songs, "library", actions)
            }
            LibraryTab.PLAYLISTS -> {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp)) {
                    item { PlaylistGrid(vm, actions) }
                }
            }
            LibraryTab.ARTISTS -> {
                val artists by vm.artists.collectAsState()
                LazyColumn(Modifier.fillMaxSize()) {
                    items(artists, key = { it.id }) { a ->
                        GroupRow(a.name, "Sanatçı · ${a.songCount} şarkı", round = true) { actions.goArtist(a.id) }
                    }
                }
            }
            LibraryTab.ALBUMS -> {
                val albums by vm.albums.collectAsState()
                LazyColumn(Modifier.fillMaxSize()) {
                    items(albums, key = { it.id }) { a ->
                        GroupRow(a.title, "${a.artistName ?: "Bilinmeyen sanatçı"} · ${a.songCount} şarkı", round = false) { actions.goAlbum(a.id) }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupRow(title: String, sub: String, round: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 6.dp).heightIn(min = 64.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CoverPlaceholder(title, 56.dp, round)
        Column {
            Text(title, fontWeight = FontWeight.Bold)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.muted)
        }
    }
}

/** Artist and album pages: a header with "Karıştır" and the song list. */
@Composable
fun GroupSongsScreen(
    title: String,
    subtitle: String,
    songsFlow: Flow<List<SongListItem>>,
    contextKey: String,
    actions: SongListActions,
    onBack: () -> Unit,
) {
    val songs by songsFlow.collectAsState(initial = emptyList())
    SongListBody(
        songs, contextKey, actions,
        header = {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                IconButton(onClick = onBack) { Icon(AppIcons.Back, "Geri") }
                Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.ExtraBold)
                Text("$subtitle · ${songs.size} şarkı", color = AppTheme.colors.muted)
                Button(onClick = { actions.vm.shufflePlay(songs, contextKey) }, enabled = songs.isNotEmpty()) {
                    Icon(AppIcons.Shuffle, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Karıştır", fontWeight = FontWeight.Bold)
                }
            }
        },
    )
}

// ---- Search -----------------------------------------------------------------------------

@Composable
fun SearchScreen(vm: MainViewModel, actions: SongListActions) {
    var query by rememberSaveable { mutableStateOf("") }
    val results by remember(query) { vm.search(query) }.collectAsState(initial = emptyList())
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Şarkı, sanatçı veya albüm") },
            leadingIcon = { Icon(AppIcons.Search, null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
        )
        if (query.isNotBlank() && results.isEmpty()) EmptyState("Sonuç yok", "\"$query\" için bir şey bulunamadı.")
        SongListBody(results, "search", actions)
    }
}

// ---- Settings ---------------------------------------------------------------------------

@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit, onExport: () -> Unit) {
    val settings by vm.settings.collectAsState()
    val scan by vm.scan.collectAsState()
    val plays by vm.playCount.collectAsState()
    val songs by vm.songs.collectAsState()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(AppIcons.Back, "Geri") }
                Text("Ayarlar", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
            }
        }
        item { Text("Tema rengi", fontWeight = FontWeight.Bold) }
        item {
            val dark = (settings?.appearance ?: Appearance.DARK) == Appearance.DARK
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(ThemeColor.entries) { c ->
                    val p = themePalette(c, if (dark) Appearance.DARK else Appearance.LIGHT)
                    val selected = settings?.themeColor == c
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { vm.setThemeColor(c) }) {
                        androidx.compose.foundation.layout.Box(
                            Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).background(Color(p.primary))
                                .then(if (selected) Modifier.padding(0.dp) else Modifier),
                        )
                        Text(
                            c.displayName,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Normal,
                            color = if (selected) AppTheme.colors.primaryText else AppTheme.colors.muted,
                        )
                    }
                }
            }
        }
        item { Text("Görünüm", fontWeight = FontWeight.Bold) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(settings?.appearance == null, { vm.setAppearance(null) }, { Text("Sistem") })
                FilterChip(settings?.appearance == Appearance.DARK, { vm.setAppearance(Appearance.DARK) }, { Text("Koyu") })
                FilterChip(settings?.appearance == Appearance.LIGHT, { vm.setAppearance(Appearance.LIGHT) }, { Text("Aydınlık") })
            }
        }
        item { Text("Müzik klasörleri", fontWeight = FontWeight.Bold) }
        item { FolderPicker(vm, settings?.includedFolders, scan) }
        item { Text("Kütüphane", fontWeight = FontWeight.Bold) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val minSec = settings?.minDurationSec ?: 30
                Text(
                    if (minSec > 0) "${songs.size} şarkı · $minSec sn'den kısa dosyalar gizli" else "${songs.size} şarkı · bütün dosyalar gösteriliyor",
                    color = AppTheme.colors.muted,
                )
                Text("Kısa dosyaları gizle", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((sec, label) in listOf(0 to "Kapalı", 15 to "15 sn", 30 to "30 sn", 60 to "60 sn")) {
                        FilterChip(selected = minSec == sec, onClick = { vm.setMinDuration(sec) }, label = { Text(label) })
                    }
                }
                OutlinedButton(onClick = vm::scan, enabled = scan != ScanState.Running) {
                    Text(if (scan == ScanState.Running) "Taranıyor…" else "Müzik klasörünü yeniden tara")
                }
                when (val s = scan) {
                    is ScanState.Done -> Text("${s.total} şarkı · ${s.added} yeni · ${s.updated} güncellendi · ${s.missing} bulunamadı", color = AppTheme.colors.muted)
                    is ScanState.Failed -> Text("Tarama başarısız: ${s.message}", color = MaterialTheme.colorScheme.error)
                    else -> {}
                }
            }
        }
        item { Text("Geliştirici", fontWeight = FontWeight.Bold) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Puanları göster")
                    Text(
                        "Şarkı listelerinde sağda ✦ keşif ve ♥ tekrarlama puanı. Kapalıyken yerine ⋮ menü düğmesi çıkar.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTheme.colors.muted,
                    )
                }
                androidx.compose.material3.Switch(checked = settings?.showScores ?: true, onCheckedChange = { vm.setShowScores(it) })
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("$plays dinleme kaydı", color = AppTheme.colors.muted)
                OutlinedButton(onClick = onExport) { Text("Dinleme geçmişini dışa aktar (CSV)") }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/**
 * Only songs in the checked folders are in the library (proje.txt line 15, like VLC).
 * A checked folder includes its subfolders; those are shown checked and greyed out.
 */
@Composable
private fun FolderPicker(vm: MainViewModel, included: Set<String>?, scan: ScanState) {
    var folders by remember { mutableStateOf<List<Pair<String, Int>>?>(null) }
    androidx.compose.runtime.LaunchedEffect(scan) { folders = vm.folders() }
    val list = folders
    if (list == null) {
        Text("Klasörler okunuyor…", color = AppTheme.colors.muted)
        return
    }
    if (list.isEmpty()) {
        Text("Telefonda müzik içeren klasör bulunamadı.", color = AppTheme.colors.muted)
        return
    }
    val all = list.map { it.first }
    val effective = included ?: all.toSet()
    Column {
        Text(
            if (included == null) "Bütün klasörler dahil. Bir klasörün işaretini kaldırınca içindeki şarkılar kütüphaneden çıkar; dinleme geçmişi silinmez."
            else "Yalnızca işaretli klasörlerdeki şarkılar kütüphanede.",
            style = MaterialTheme.typography.bodySmall,
            color = AppTheme.colors.muted,
        )
        for ((path, count) in list) {
            val coveredByParent = effective.any { it != path && path.startsWith(it) }
            val checked = coveredByParent || path in effective
            val depth = path.trimEnd('/').count { it == '/' }
            Row(
                Modifier.fillMaxWidth().padding(start = (depth * 16).dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.Checkbox(
                    checked = checked,
                    enabled = !coveredByParent,
                    onCheckedChange = { vm.setFolderIncluded(path, it, all) },
                )
                Text(path.trimEnd('/'), Modifier.weight(1f), color = if (coveredByParent) AppTheme.colors.muted else MaterialTheme.colorScheme.onBackground)
                Text("$count", style = MaterialTheme.typography.labelMedium, color = AppTheme.colors.muted)
            }
        }
    }
}

// ---- Permission -------------------------------------------------------------------------

@Composable
fun PermissionScreen(onRequest: () -> Unit, onOpenSettings: () -> Unit, denied: Boolean) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(AppIcons.Library, null, Modifier.size(56.dp), tint = AppTheme.colors.primary)
        Text("Müziklerine erişmek için izin gerekiyor", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "Uygulama telefondaki müzik dosyalarını yalnızca okur. Dosyaların adı, yeri veya içeriği değiştirilmez.",
            color = AppTheme.colors.muted,
        )
        Button(onClick = onRequest) { Text("İzin ver") }
        if (denied) OutlinedButton(onClick = onOpenSettings) { Text("Uygulama ayarlarını aç") }
    }
}
