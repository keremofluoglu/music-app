package com.example.musicapp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.musicapp.data.db.SongListItem
import com.example.musicapp.ui.theme.AppTheme
import kotlinx.coroutines.flow.Flow

/** Where the picker takes songs from: the library, another list, an artist or an album. */
private data class Source(val key: String, val title: String)

/**
 * "Şarkı ekle" for a user playlist. First the sources (Tüm şarkılar, Beğenilenler, other lists,
 * artists, albums) or a search; then songs, where a tap adds the song and a second tap removes it.
 */
@Composable
fun AddSongsScreen(playlistId: Long, vm: MainViewModel, onDone: () -> Unit) {
    val playlist by remember(playlistId) { vm.playlist(playlistId) }.collectAsState(initial = null)
    val inList by remember(playlistId) { vm.playlistSongIds(playlistId) }.collectAsState(initial = emptyList())
    val inSet = remember(inList) { inList.toHashSet() }
    var sourceKey by rememberSaveable { mutableStateOf<String?>(null) }
    var sourceTitle by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }

    BackHandler(enabled = sourceKey != null) { sourceKey = null }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (sourceKey != null) sourceKey = null else onDone() }) { Icon(AppIcons.Back, "Geri") }
            Column(Modifier.weight(1f)) {
                Text(
                    if (sourceKey == null) "Şarkı ekle" else sourceTitle,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${playlist?.name.orEmpty()} · ${inList.size} şarkı",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTheme.colors.muted,
                    maxLines = 1,
                )
            }
            Button(onClick = onDone) { Text("Bitti", fontWeight = FontWeight.Bold) }
        }

        if (sourceKey == null) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Şarkı, sanatçı veya albüm ara") },
                leadingIcon = { Icon(AppIcons.Search, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        val key = sourceKey
        when {
            key != null -> {
                val flow = remember(key) { songsOf(key, vm) }
                val songs by flow.collectAsState(initial = emptyList())
                ToggleList(songs, inSet) { song, add -> vm.setInPlaylist(playlistId, song.id, add) }
            }
            query.isNotBlank() -> {
                val results by remember(query) { vm.search(query) }.collectAsState(initial = emptyList())
                if (results.isEmpty()) EmptyState("Sonuç yok", "\"$query\" için bir şey bulunamadı.")
                ToggleList(results, inSet) { song, add -> vm.setInPlaylist(playlistId, song.id, add) }
            }
            else -> SourceList(vm, excludePlaylist = playlistId) { s ->
                sourceKey = s.key
                sourceTitle = s.title
            }
        }
    }
}

private fun songsOf(key: String, vm: MainViewModel): Flow<List<SongListItem>> {
    val (kind, id) = key.split(':', limit = 2).let { it[0] to it.getOrNull(1)?.toLongOrNull() }
    return when (kind) {
        "liked" -> vm.liked
        "pl" -> vm.songsOfPlaylist(id ?: 0)
        "artist" -> vm.songsOfArtist(id ?: 0)
        "album" -> vm.songsOfAlbum(id ?: 0)
        else -> vm.songs
    }
}

@Composable
private fun SourceList(vm: MainViewModel, excludePlaylist: Long, onPick: (Source) -> Unit) {
    val songs by vm.songs.collectAsState()
    val likedCount by vm.likedCount.collectAsState()
    val playlists by vm.playlists.collectAsState()
    val artists by vm.artists.collectAsState()
    val albums by vm.albums.collectAsState()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item { SourceRow("Tüm şarkılar", "${songs.size} şarkı", AppIcons.Library) { onPick(Source("all", "Tüm şarkılar")) } }
        item { SourceRow("Beğenilenler", "$likedCount şarkı", AppIcons.Favorite) { onPick(Source("liked", "Beğenilenler")) } }
        val others = playlists.filter { it.id != excludePlaylist }
        if (others.isNotEmpty()) item { SectionTitle("Çalma listeleri") }
        items(others, key = { "pl${it.id}" }) { p ->
            SourceRow(p.name, "${p.songCount} şarkı", AppIcons.Queue) { onPick(Source("pl:${p.id}", p.name)) }
        }
        if (artists.isNotEmpty()) item { SectionTitle("Sanatçılar") }
        items(artists, key = { "ar${it.id}" }) { a ->
            SourceRow(a.name, "${a.songCount} şarkı", null) { onPick(Source("artist:${a.id}", a.name)) }
        }
        if (albums.isNotEmpty()) item { SectionTitle("Albümler") }
        items(albums, key = { "al${it.id}" }) { a ->
            SourceRow(a.title, "${a.artistName ?: "Bilinmeyen sanatçı"} · ${a.songCount} şarkı", null) {
                onPick(Source("album:${a.id}", a.title))
            }
        }
    }
}

@Composable
private fun SourceRow(title: String, sub: String, icon: androidx.compose.ui.graphics.vector.ImageVector?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 60.dp).padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) {
            Box(
                Modifier.size(48.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = AppTheme.colors.primaryText) }
        } else {
            CoverPlaceholder(title, 48.dp)
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.muted)
        }
    }
}

/** Tap toggles membership: ✓ = in the list, + = not in the list. */
@Composable
private fun ToggleList(songs: List<SongListItem>, inList: Set<String>, onToggle: (SongListItem, Boolean) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        items(songs, key = { it.id }) { song ->
            val added = song.id in inList
            Row(
                Modifier.fillMaxWidth().clickable { onToggle(song, !added) }.heightIn(min = 64.dp)
                    .padding(start = 20.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CoverPlaceholder(song.title, 48.dp)
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        VersionDot(song.versionType)
                        Text(
                            song.title,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (added) AppTheme.colors.primaryText else MaterialTheme.colorScheme.onBackground,
                        )
                    }
                    Text(song.artistNames ?: "Bilinmeyen sanatçı", style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.muted, maxLines = 1)
                }
                Box(
                    Modifier.size(32.dp).clip(CircleShape)
                        .then(
                            if (added) Modifier.background(MaterialTheme.colorScheme.primary)
                            else Modifier.border(2.dp, AppTheme.colors.line, CircleShape),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (added) AppIcons.Check else AppIcons.Add,
                        contentDescription = if (added) "Listede; çıkarmak için dokun" else "Listeye ekle",
                        tint = if (added) MaterialTheme.colorScheme.onPrimary else AppTheme.colors.muted,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}
