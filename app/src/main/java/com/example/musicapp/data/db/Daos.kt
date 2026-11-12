package com.example.musicapp.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** A song row for lists, with its artists and album already joined. */
data class SongListItem(
    val id: String,
    val title: String,
    @ColumnInfo(name = "artist_names") val artistNames: String?,
    @ColumnInfo(name = "album_title") val albumTitle: String?,
    @ColumnInfo(name = "album_id") val albumId: Long?,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    @ColumnInfo(name = "media_id") val mediaId: Long?,
    @ColumnInfo(name = "file_path") val filePath: String,
    val liked: Boolean,
    @ColumnInfo(name = "version_type") val versionType: VersionType?,
    /** From song_stats; null before the first score computation. */
    @ColumnInfo(name = "discovery_score") val discoveryScore: Double?,
    @ColumnInfo(name = "repeat_score") val repeatScore: Double?,
)

data class ArtistListItem(
    val id: Long,
    val name: String,
    @ColumnInfo(name = "song_count") val songCount: Int,
)

data class PlaylistListItem(
    val id: Long,
    val name: String,
    @ColumnInfo(name = "song_count") val songCount: Int,
)

data class AlbumListItem(
    val id: Long,
    val title: String,
    @ColumnInfo(name = "artist_name") val artistName: String?,
    @ColumnInfo(name = "song_count") val songCount: Int,
)

/** What the score engine needs to know about every song. */
data class SongScoringRow(
    val id: String,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    val active: Boolean,
)

data class SongArtistRow(
    @ColumnInfo(name = "song_id") val songId: String,
    @ColumnInfo(name = "artist_id") val artistId: Long,
)

data class SongGenreRow(
    @ColumnInfo(name = "song_id") val songId: String,
    @ColumnInfo(name = "genre_id") val genreId: Long,
)

private const val SONG_LIST_SELECT = """
    SELECT s.id, s.title, s.duration_ms, s.media_id, s.file_path, s.liked, s.version_type, s.album_id,
           al.title AS album_title,
           st.discovery_score AS discovery_score, st.repeat_score AS repeat_score,
           (SELECT GROUP_CONCAT(a.name, ', ') FROM
               (SELECT a2.name FROM song_artists sa JOIN artists a2 ON a2.id = sa.artist_id
                WHERE sa.song_id = s.id ORDER BY sa.position) a) AS artist_names
    FROM songs s LEFT JOIN albums al ON al.id = s.album_id
    LEFT JOIN song_stats st ON st.song_id = s.id
"""
private const val ACTIVE = "s.deleted_at IS NULL AND s.is_missing = 0"

@Dao
interface SongDao {
    @Upsert
    suspend fun upsert(song: SongEntity)

    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun getById(id: String): SongEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM songs WHERE id = :id)")
    suspend fun exists(id: String): Boolean

    @Query("SELECT * FROM songs WHERE content_hash = :hash LIMIT 1")
    suspend fun findByContentHash(hash: String): SongEntity?

    @Query("SELECT * FROM songs WHERE source = 'LOCAL'")
    suspend fun allLocal(): List<SongEntity>

    @Query("SELECT id, added_at, (deleted_at IS NULL AND is_missing = 0) AS active FROM songs")
    suspend fun allForScoring(): List<SongScoringRow>

    @Query("$SONG_LIST_SELECT WHERE $ACTIVE ORDER BY s.title COLLATE NOCASE")
    fun observeLibrary(): Flow<List<SongListItem>>

    @Query("$SONG_LIST_SELECT WHERE $ACTIVE AND s.search_text LIKE '%' || :folded || '%' ORDER BY s.title COLLATE NOCASE LIMIT 200")
    fun search(folded: String): Flow<List<SongListItem>>

    @Query("$SONG_LIST_SELECT WHERE $ACTIVE AND s.id IN (SELECT song_id FROM song_artists WHERE artist_id = :artistId) ORDER BY s.title COLLATE NOCASE")
    fun observeByArtist(artistId: Long): Flow<List<SongListItem>>

    @Query("$SONG_LIST_SELECT WHERE $ACTIVE AND s.album_id = :albumId ORDER BY s.title COLLATE NOCASE")
    fun observeByAlbum(albumId: Long): Flow<List<SongListItem>>

    @Query("$SONG_LIST_SELECT WHERE $ACTIVE AND s.liked = 1 ORDER BY s.liked_at DESC")
    fun observeLiked(): Flow<List<SongListItem>>

    @Query("SELECT COUNT(*) FROM songs s WHERE $ACTIVE AND s.liked = 1")
    fun observeLikedCount(): Flow<Int>

    @Query(
        "$SONG_LIST_SELECT JOIN playlist_items pi ON pi.song_id = s.id " +
            "WHERE $ACTIVE AND pi.playlist_id = :playlistId ORDER BY pi.position"
    )
    fun observeByPlaylist(playlistId: Long): Flow<List<SongListItem>>

    /** Small sets only (at most 500 IDs). */
    @Query("$SONG_LIST_SELECT WHERE s.id IN (:ids)")
    suspend fun listItems(ids: List<String>): List<SongListItem>

    @Query("$SONG_LIST_SELECT WHERE $ACTIVE")
    suspend fun activeListItems(): List<SongListItem>

    /** Including removed and missing songs (history export). */
    @Query(SONG_LIST_SELECT)
    suspend fun allListItems(): List<SongListItem>

    @Query("$SONG_LIST_SELECT WHERE s.id = :id")
    fun observeListItem(id: String): Flow<SongListItem?>

    @Query("$SONG_LIST_SELECT WHERE s.id = :id")
    suspend fun listItemById(id: String): SongListItem?

    @Query("UPDATE songs SET liked = :liked, liked_at = :likedAt WHERE id = :id")
    suspend fun setLiked(id: String, liked: Boolean, likedAt: Long?)

    /** Callers pass at most 500 IDs (old SQLite versions allow 999 variables). */
    @Query("UPDATE songs SET is_missing = 1 WHERE id IN (:ids)")
    suspend fun markMissing(ids: List<String>)

    @Query("UPDATE songs SET deleted_at = :at WHERE id = :id")
    suspend fun removeFromLibrary(id: String, at: Long)

    /** UI label only; set by hand. Never written to the file. */
    @Query("UPDATE songs SET version_type = :type, version_source = :source WHERE id = :id")
    suspend fun setVersion(id: String, type: VersionType?, source: VersionSource?)

    @Query("SELECT COUNT(*) FROM songs WHERE deleted_at IS NULL AND is_missing = 0")
    fun observeCount(): Flow<Int>
}

@Dao
interface ArtistDao {
    @Query("SELECT * FROM artists WHERE normalized_name = :normalized")
    suspend fun findByNormalized(normalized: String): ArtistEntity?

    @Insert
    suspend fun insert(artist: ArtistEntity): Long

    @Query("SELECT * FROM artists WHERE id = :id")
    suspend fun get(id: Long): ArtistEntity?

    @Query(
        """
        SELECT a.id, a.name, COUNT(s.id) AS song_count FROM artists a
        JOIN song_artists sa ON sa.artist_id = a.id
        JOIN songs s ON s.id = sa.song_id AND s.deleted_at IS NULL AND s.is_missing = 0
        GROUP BY a.id ORDER BY a.name COLLATE NOCASE
        """
    )
    fun observeAll(): Flow<List<ArtistListItem>>

    @Query("SELECT song_id, artist_id FROM song_artists")
    suspend fun allSongArtists(): List<SongArtistRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun link(link: SongArtistEntity)

    @Query("DELETE FROM song_artists WHERE song_id = :songId")
    suspend fun unlinkSong(songId: String)
}

@Dao
interface AlbumDao {
    @Query("SELECT * FROM albums WHERE title = :title AND album_artist_id IS :artistId LIMIT 1")
    suspend fun find(title: String, artistId: Long?): AlbumEntity?

    @Insert
    suspend fun insert(album: AlbumEntity): Long

    @Query("SELECT * FROM albums WHERE id = :id")
    suspend fun get(id: Long): AlbumEntity?

    @Query(
        """
        SELECT al.id, al.title, a.name AS artist_name, COUNT(s.id) AS song_count FROM albums al
        LEFT JOIN artists a ON a.id = al.album_artist_id
        JOIN songs s ON s.album_id = al.id AND s.deleted_at IS NULL AND s.is_missing = 0
        GROUP BY al.id ORDER BY al.title COLLATE NOCASE
        """
    )
    fun observeAll(): Flow<List<AlbumListItem>>
}

@Dao
interface GenreDao {
    @Query("SELECT * FROM genres WHERE name = :name")
    suspend fun find(name: String): GenreEntity?

    @Insert
    suspend fun insert(genre: GenreEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun link(link: SongGenreEntity)

    @Query("DELETE FROM song_genres WHERE song_id = :songId AND source = :source")
    suspend fun unlinkSong(songId: String, source: GenreSource)

    @Query("SELECT song_id, genre_id FROM song_genres")
    suspend fun allSongGenres(): List<SongGenreRow>
}

@Dao
interface PlayEventDao {
    @Insert
    suspend fun insert(event: PlayEventEntity): Long

    @Query("SELECT * FROM play_events ORDER BY started_at, id")
    suspend fun allOrdered(): List<PlayEventEntity>

    @Query("SELECT * FROM play_events ORDER BY started_at DESC LIMIT 1")
    suspend fun latest(): PlayEventEntity?

    @Query("SELECT COUNT(*) FROM play_events")
    fun observeCount(): Flow<Int>

    @Query(
        """
        SELECT * FROM play_events
        WHERE song_id = :songId AND listened_ms >= 0.8 * duration_ms
        ORDER BY started_at DESC
        LIMIT :limit
        """
    )
    suspend fun qualifiedListens(songId: String, limit: Int = 200): List<PlayEventEntity>
}

@Dao
interface SongStatsDao {
    @Upsert
    suspend fun upsertAll(stats: List<SongStatsEntity>)

    @Query("SELECT * FROM song_stats WHERE song_id = :songId")
    suspend fun get(songId: String): SongStatsEntity?

    @Query("SELECT * FROM song_stats WHERE song_id = :songId")
    fun observe(songId: String): Flow<SongStatsEntity?>

    @Query("SELECT * FROM song_stats")
    suspend fun getAll(): List<SongStatsEntity>
}

@Dao
interface SessionDao {
    @Insert
    suspend fun insert(session: SessionEntity): Long

    @Query("SELECT * FROM sessions ORDER BY started_at DESC LIMIT 1")
    suspend fun latest(): SessionEntity?

    @Query("UPDATE sessions SET ended_at = :endedAt WHERE id = :id")
    suspend fun end(id: Long, endedAt: Long)
}

@Dao
interface ModeChangeDao {
    @Insert
    suspend fun insert(change: ModeChangeEntity)
}

@Dao
interface PlaylistDao {
    @Insert
    suspend fun insert(playlist: PlaylistEntity): Long

    @Query(
        """
        SELECT p.id, p.name, COUNT(s.id) AS song_count FROM playlists p
        LEFT JOIN playlist_items pi ON pi.playlist_id = p.id
        LEFT JOIN songs s ON s.id = pi.song_id AND s.deleted_at IS NULL AND s.is_missing = 0
        GROUP BY p.id ORDER BY p.created_at
        """
    )
    fun observeLists(): Flow<List<PlaylistListItem>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun observe(id: Long): Flow<PlaylistEntity?>

    @Query("UPDATE playlists SET name = :name, updated_at = :at WHERE id = :id")
    suspend fun rename(id: Long, name: String, at: Long)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE playlists SET updated_at = :at WHERE id = :id")
    suspend fun touch(id: Long, at: Long)

    @Query("SELECT COALESCE(MAX(position), 0) FROM playlist_items WHERE playlist_id = :playlistId")
    suspend fun maxPosition(playlistId: Long): Double

    @Query("SELECT EXISTS(SELECT 1 FROM playlist_items WHERE playlist_id = :playlistId AND song_id = :songId)")
    suspend fun contains(playlistId: Long, songId: String): Boolean

    /** Songs already in the playlist; drives the ✓ marks in the "Şarkı ekle" picker. */
    @Query("SELECT song_id FROM playlist_items WHERE playlist_id = :playlistId")
    fun observeSongIds(playlistId: Long): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addItem(item: PlaylistItemEntity)

    @Query("DELETE FROM playlist_items WHERE playlist_id = :playlistId AND song_id = :songId")
    suspend fun removeItem(playlistId: Long, songId: String)
}

@Dao
interface LyricsDao {
    @Upsert
    suspend fun upsert(lyrics: LyricsEntity)

    @Query("SELECT * FROM lyrics WHERE song_id = :songId")
    suspend fun get(songId: String): LyricsEntity?
}

@Dao
interface DownloadDao {
    @Insert
    suspend fun insert(download: DownloadEntity): Long

    @Query("SELECT * FROM downloads WHERE status IN ('QUEUED', 'DOWNLOADING') ORDER BY requested_at")
    suspend fun pending(): List<DownloadEntity>
}
