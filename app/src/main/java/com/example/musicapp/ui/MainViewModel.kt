package com.example.musicapp.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import com.example.musicapp.data.db.AlbumListItem
import com.example.musicapp.data.db.ArtistListItem
import com.example.musicapp.data.db.ListenMode
import com.example.musicapp.data.db.ModeChangeEntity
import com.example.musicapp.data.db.PlaylistEntity
import com.example.musicapp.data.db.PlaylistItemEntity
import com.example.musicapp.data.db.PlaylistListItem
import com.example.musicapp.data.db.RepeatMode
import com.example.musicapp.data.db.SongEntity
import com.example.musicapp.data.db.SongListItem
import com.example.musicapp.data.db.SongStatsEntity
import com.example.musicapp.data.db.VersionSource
import com.example.musicapp.data.db.VersionType
import com.example.musicapp.data.export.CsvExporter
import com.example.musicapp.data.settings.Settings
import com.example.musicapp.graph
import com.example.musicapp.library.TextFold
import com.example.musicapp.playback.PlayContext
import com.example.musicapp.playback.PlayerConnection
import com.example.musicapp.playback.playContext
import com.example.musicapp.playback.toMediaItem
import com.example.musicapp.ui.theme.Appearance
import com.example.musicapp.ui.theme.ThemeColor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the "Özellikler" dialog shows (docs/kucuk-ozellikler.md, 4.1). */
data class SongProperties(val song: SongEntity, val stats: SongStatsEntity?)

sealed interface ScanState {
    data object Idle : ScanState
    data object Running : ScanState
    data class Done(val added: Int, val updated: Int, val missing: Int, val total: Int) : ScanState
    data class Failed(val message: String) : ScanState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val g = app.graph
    private val dao = g.db.songDao()
    val player = PlayerConnection(app)

    val settings: StateFlow<Settings?> = g.settings.flow.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val songs = dao.observeLibrary().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val artists: StateFlow<List<ArtistListItem>> = g.db.artistDao().observeAll().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val albums: StateFlow<List<AlbumListItem>> = g.db.albumDao().observeAll().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val liked = dao.observeLiked().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val likedCount = dao.observeLikedCount().stateIn(viewModelScope, SharingStarted.Lazily, 0)
    val playlists: StateFlow<List<PlaylistListItem>> =
        g.db.playlistDao().observeLists().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val playCount = g.db.playEventDao().observeCount().stateIn(viewModelScope, SharingStarted.Lazily, 0)

    private val _scan = MutableStateFlow<ScanState>(ScanState.Idle)
    val scan: StateFlow<ScanState> = _scan.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    fun messageShown() { _message.value = null }

    init {
        player.connect()
    }

    override fun onCleared() {
        player.release()
    }

    fun search(query: String): Flow<List<SongListItem>> =
        if (query.isBlank()) flowOf(emptyList()) else dao.search(TextFold.fold(query))

    fun songsOfArtist(id: Long) = dao.observeByArtist(id)
    fun songsOfAlbum(id: Long) = dao.observeByAlbum(id)
    fun songsOfPlaylist(id: Long) = dao.observeByPlaylist(id)
    fun playlist(id: Long) = g.db.playlistDao().observe(id)

    // ---- Playlists ------------------------------------------------------------------------

    /** Creates a playlist; when [firstSong] is given it is added right away. [onCreated] gets the new ID. */
    fun createPlaylist(name: String, firstSong: SongListItem? = null, onCreated: (Long) -> Unit = {}) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val id = g.db.playlistDao().insert(PlaylistEntity(name = clean, createdAt = now, updatedAt = now))
            if (firstSong != null) addToPlaylist(id, firstSong, clean)
            else _message.value = "\"$clean\" oluşturuldu"
            onCreated(id)
        }
    }

    fun playlistSongIds(playlistId: Long) = g.db.playlistDao().observeSongIds(playlistId)

    /** The "Şarkı ekle" picker: tap adds, tap again removes. No snackbar, the ✓ mark is the feedback. */
    fun setInPlaylist(playlistId: Long, songId: String, include: Boolean) {
        viewModelScope.launch {
            val pl = g.db.playlistDao()
            val now = System.currentTimeMillis()
            if (include) {
                if (!pl.contains(playlistId, songId)) {
                    pl.addItem(PlaylistItemEntity(playlistId, songId, pl.maxPosition(playlistId) + 1.0, now))
                }
            } else {
                pl.removeItem(playlistId, songId)
            }
            pl.touch(playlistId, now)
        }
    }

    fun addToPlaylist(playlistId: Long, song: SongListItem, playlistName: String) {
        viewModelScope.launch {
            val pl = g.db.playlistDao()
            if (pl.contains(playlistId, song.id)) {
                _message.value = "Bu şarkı zaten \"$playlistName\" listesinde"
                return@launch
            }
            val now = System.currentTimeMillis()
            pl.addItem(PlaylistItemEntity(playlistId, song.id, pl.maxPosition(playlistId) + 1.0, now))
            pl.touch(playlistId, now)
            _message.value = "\"$playlistName\" listesine eklendi"
        }
    }

    fun removeFromPlaylist(playlistId: Long, songId: String) {
        viewModelScope.launch {
            g.db.playlistDao().removeItem(playlistId, songId)
            g.db.playlistDao().touch(playlistId, System.currentTimeMillis())
            _message.value = "Listeden çıkarıldı"
        }
    }

    fun renamePlaylist(id: Long, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { g.db.playlistDao().rename(id, name.trim(), System.currentTimeMillis()) }
    }

    fun deletePlaylist(id: Long) {
        viewModelScope.launch {
            g.db.playlistDao().delete(id)
            _message.value = "Çalma listesi silindi (şarkılar kütüphanede duruyor)"
        }
    }
    fun currentSong(id: String) = dao.observeListItem(id)

    fun scan() {
        if (_scan.value == ScanState.Running) return
        viewModelScope.launch {
            _scan.value = ScanState.Running
            _scan.value = runCatching {
                val s = g.settings.current()
                val r = g.scanner.scan(s.minDurationSec * 1000L, s.includedFolders)
                g.scores.refreshAll()
                ScanState.Done(r.added, r.updated, r.missing, r.total)
            }.getOrElse { ScanState.Failed(it.message ?: it.javaClass.simpleName) }
        }
    }

    // ---- Playback -------------------------------------------------------------------------

    /**
     * The user tapped [tapped] in a list. With shuffle on, the tapped song plays first and the rest
     * of the list is score-shuffled for the selected home mode; otherwise the list plays in order.
     */
    fun play(list: List<SongListItem>, tapped: SongListItem, contextKey: String) {
        viewModelScope.launch {
            val s = g.settings.current()
            if (s.shuffle) {
                val ctx = PlayContext(contextKey, list.size, s.homeMode)
                val rest = g.queues.shuffled(list.filter { it.id != tapped.id }, s.homeMode)
                val items = listOf(tapped.toMediaItem(ctx, null, manual = true)) +
                    rest.map { it.song.toMediaItem(ctx, it.tag, manual = false) }
                player.setQueue(items, 0)
            } else {
                val ctx = PlayContext(contextKey, list.size, ListenMode.NONE)
                val start = list.indexOfFirst { it.id == tapped.id }.coerceAtLeast(0)
                val ordered = list.drop(start) + list.take(start)
                player.setQueue(ordered.mapIndexed { i, song -> song.toMediaItem(ctx, null, manual = i == 0) }, 0)
            }
            applyRepeat(s.repeat)
        }
    }

    /** "Karıştır ve çal": the whole library (or [list]) score-shuffled for the home mode. */
    fun shufflePlay(list: List<SongListItem>? = null, contextKey: String = "home") {
        viewModelScope.launch {
            val s = g.settings.current()
            val order = g.queues.shuffled(list, s.homeMode)
            if (order.isEmpty()) {
                _message.value = "Kütüphanede şarkı yok"
                return@launch
            }
            val ctx = PlayContext(contextKey, list?.size ?: order.size, s.homeMode)
            player.setQueue(order.map { it.song.toMediaItem(ctx, it.tag, manual = false) }, 0)
            if (!s.shuffle) g.settings.setShuffle(true)
            applyRepeat(s.repeat)
        }
    }

    /** Shuffle button on the now-playing screen: re-sorts everything after the current song. */
    fun toggleShuffle() {
        viewModelScope.launch {
            val s = g.settings.current()
            val on = !s.shuffle
            g.settings.setShuffle(on)
            if (!on) return@launch
            val upcoming = player.upcomingIds().toSet()
            if (upcoming.isEmpty()) return@launch
            val songs = dao.activeListItems().filter { it.id in upcoming }
            val ctx = player.currentItem()?.playContext()?.copy(mode = s.homeMode)
                ?: PlayContext("library", songs.size, s.homeMode)
            val order = g.queues.shuffled(songs, s.homeMode)
            player.replaceUpcoming(order.map { it.song.toMediaItem(ctx, it.tag, manual = false) })
        }
    }

    fun cycleRepeat() {
        viewModelScope.launch {
            val next = when (g.settings.current().repeat) {
                RepeatMode.OFF -> RepeatMode.ALL
                RepeatMode.ALL -> RepeatMode.ONE
                RepeatMode.ONE -> RepeatMode.OFF
            }
            g.settings.setRepeat(next)
            applyRepeat(next)
        }
    }

    private fun applyRepeat(r: RepeatMode) = player.setRepeatMode(
        when (r) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        },
    )

    fun playNext(song: SongListItem) {
        val ctx = player.currentItem()?.playContext() ?: PlayContext("library", 1, ListenMode.NONE)
        player.playNext(song.toMediaItem(ctx, null, manual = false))
        _message.value = "Sıradaki olarak eklendi"
    }

    fun addToQueue(song: SongListItem) {
        val ctx = player.currentItem()?.playContext() ?: PlayContext("library", 1, ListenMode.NONE)
        player.addToQueue(song.toMediaItem(ctx, null, manual = false))
        _message.value = "Kuyruğa eklendi"
    }

    fun setSleepTimer(minutes: Int) {
        player.setSleepTimer(minutes)
        _message.value = if (minutes > 0) "Uyku zamanlayıcı: $minutes dk" else "Uyku zamanlayıcı kapatıldı"
    }

    // ---- Library actions ------------------------------------------------------------------

    fun setHomeMode(mode: ListenMode) {
        viewModelScope.launch {
            if (g.settings.current().homeMode == mode) return@launch
            g.settings.setHomeMode(mode)
            g.db.modeChangeDao().insert(ModeChangeEntity(mode = mode, changedAt = System.currentTimeMillis()))
        }
    }

    fun toggleLike(id: String, liked: Boolean) {
        viewModelScope.launch { dao.setLiked(id, !liked, if (liked) null else System.currentTimeMillis()) }
    }

    /** Hides the song in the app only; the file is never touched. */
    fun removeFromLibrary(id: String) {
        viewModelScope.launch {
            dao.removeFromLibrary(id, System.currentTimeMillis())
            _message.value = "Kütüphaneden kaldırıldı (dosya silinmedi)"
        }
    }

    /** Marks the version (original, cover …) by hand; null clears it. Only the app's database changes. */
    fun setVersion(id: String, type: VersionType?) {
        viewModelScope.launch { dao.setVersion(id, type, type?.let { VersionSource.USER }) }
    }

    suspend fun properties(id: String): SongProperties? {
        val song = dao.getById(id) ?: return null
        return SongProperties(song, g.db.songStatsDao().get(id))
    }

    fun exportCsv(uri: Uri) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val n = runCatching {
                app.contentResolver.openOutputStream(uri)!!.use { CsvExporter(g.db).write(it) }
            }
            _message.value = n.fold({ "$it dinleme dışa aktarıldı" }, { "Dışa aktarılamadı: ${it.message}" })
        }
    }

    fun setShowScores(on: Boolean) = viewModelScope.launch { g.settings.setShowScores(on) }

    /** Files shorter than this are not songs (voice notes, ringtones). 0 = show everything. Rescans. */
    fun setMinDuration(sec: Int) {
        viewModelScope.launch {
            g.settings.setMinDurationSec(sec)
            scan()
        }
    }

    // ---- Music folders (proje.txt line 15: only selected folders, like VLC) ----------------

    suspend fun folders(): List<Pair<String, Int>> =
        g.scanner.folders(g.settings.current().minDurationSec * 1000L)

    /** Selects or clears [folder]; selecting every folder again goes back to "all folders". Rescans. */
    fun setFolderIncluded(folder: String, included: Boolean, allFolders: List<String>) {
        viewModelScope.launch {
            val current = g.settings.current().includedFolders ?: allFolders.toSet()
            // A selected folder already covers its subfolders, so they are dropped from the set.
            val withoutSubfolders = current.filterNot { it != folder && it.startsWith(folder) }.toSet()
            val next = if (included) withoutSubfolders + folder else withoutSubfolders - folder
            val coversAll = allFolders.all { f -> next.any { f.startsWith(it) } }
            g.settings.setIncludedFolders(if (coversAll) null else next)
            scan()
        }
    }

    fun setThemeColor(c: ThemeColor) = viewModelScope.launch { g.settings.setThemeColor(c) }
    fun setAppearance(a: Appearance?) = viewModelScope.launch { g.settings.setAppearance(a) }
}
