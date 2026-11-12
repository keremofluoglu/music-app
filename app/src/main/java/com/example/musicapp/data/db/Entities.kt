package com.example.musicapp.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// Tables described in docs/veritabani.md. Times are UTC epoch milliseconds.
// Enums are stored by name (e.g. "SKIPPED").

enum class SongSource { YOUTUBE, LOCAL }
enum class ParseMethod { YT_METADATA, TOPIC_CHANNEL, REGEX, FILE_TAGS, FILENAME, USER }

/** UI label only; not used by the scoring algorithm. */
enum class VersionType { ORIGINAL, COVER, REMIX, LIVE, ACOUSTIC, INSTRUMENTAL }

/** USER: set by hand. AUTO: reserved for a future detector; must never overwrite USER. */
enum class VersionSource { AUTO, USER }
enum class ArtistRole { MAIN, FEAT }
enum class GenreSource { FILE_TAGS, MUSICBRAINZ, LASTFM, AUDIO_MODEL, USER }
enum class EndReason { COMPLETED, SKIPPED, STOPPED, SLEEP_TIMER, APP_CLOSED, ERROR }
enum class InitiatedBy { MANUAL, AUTO }
enum class ListenMode { NEW, FAV, NONE }
enum class QueueTag { NOVEL, FAMILIAR }
enum class RepeatMode { OFF, ALL, ONE }
enum class EventSource { APP, TAKEOUT_IMPORT }
enum class LyricsSource { EMBEDDED, LRC_FILE, LRCLIB, USER }
enum class LyricsStatus { FOUND, INSTRUMENTAL, NOT_FOUND, ERROR }
enum class DownloadStatus { QUEUED, DOWNLOADING, DONE, FAILED, CANCELLED }
enum class ParseMatchType { CHANNEL, TITLE_REGEX }

/** One file = one song. YouTube songs use the 11-character video ID, local files "local:<hash>". */
@Entity(
    tableName = "songs",
    foreignKeys = [
        ForeignKey(
            entity = AlbumEntity::class,
            parentColumns = ["id"],
            childColumns = ["album_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["file_path"], unique = true),
        Index(value = ["source"]),
        Index(value = ["content_hash"]),
        Index(value = ["album_id"]),
        Index(value = ["media_id"]),
    ],
)
data class SongEntity(
    @PrimaryKey val id: String,
    val source: SongSource,
    val title: String,
    @ColumnInfo(name = "album_id") val albumId: Long? = null,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    @ColumnInfo(name = "file_path") val filePath: String,
    @ColumnInfo(name = "file_size") val fileSize: Long,
    val codec: String? = null,
    @ColumnInfo(name = "bitrate_kbps") val bitrateKbps: Int? = null,
    @ColumnInfo(name = "content_hash") val contentHash: String,
    @ColumnInfo(name = "cover_path") val coverPath: String? = null,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    val liked: Boolean = false,
    @ColumnInfo(name = "liked_at") val likedAt: Long? = null,
    @ColumnInfo(name = "is_missing") val isMissing: Boolean = false,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
    @ColumnInfo(name = "yt_title_raw") val ytTitleRaw: String? = null,
    @ColumnInfo(name = "yt_channel") val ytChannel: String? = null,
    @ColumnInfo(name = "parse_method") val parseMethod: ParseMethod,
    @ColumnInfo(name = "musicbrainz_recording_id") val musicbrainzRecordingId: String? = null,
    @ColumnInfo(name = "user_edited") val userEdited: Boolean = false,
    /** Null = unknown. Shown as a badge in the UI only. */
    @ColumnInfo(name = "version_type") val versionType: VersionType? = null,
    @ColumnInfo(name = "version_source") val versionSource: VersionSource? = null,
    /** MediaStore row of a local file; playback URI is built from it. May change after a rescan. */
    @ColumnInfo(name = "media_id") val mediaId: Long? = null,
    /** File modification time seen at the last scan; unchanged files are not hashed again. */
    @ColumnInfo(name = "file_modified_at") val fileModifiedAt: Long? = null,
    /** Title + artists, lowercased and folded (ş→s, ı→i …) for accent-insensitive search. */
    @ColumnInfo(name = "search_text") val searchText: String = "",
)

@Entity(
    tableName = "artists",
    indices = [Index(value = ["normalized_name"], unique = true)],
)
data class ArtistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "normalized_name") val normalizedName: String,
    @ColumnInfo(name = "musicbrainz_artist_id") val musicbrainzArtistId: String? = null,
)

@Entity(
    tableName = "song_artists",
    primaryKeys = ["song_id", "artist_id"],
    foreignKeys = [
        ForeignKey(SongEntity::class, ["id"], ["song_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(ArtistEntity::class, ["id"], ["artist_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["artist_id"])],
)
data class SongArtistEntity(
    @ColumnInfo(name = "song_id") val songId: String,
    @ColumnInfo(name = "artist_id") val artistId: Long,
    val role: ArtistRole,
    val position: Int,
)

@Entity(
    tableName = "albums",
    foreignKeys = [
        ForeignKey(ArtistEntity::class, ["id"], ["album_artist_id"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index(value = ["album_artist_id"])],
)
data class AlbumEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    @ColumnInfo(name = "album_artist_id") val albumArtistId: Long? = null,
    @ColumnInfo(name = "musicbrainz_release_id") val musicbrainzReleaseId: String? = null,
    val year: Int? = null,
)

@Entity(tableName = "genres", indices = [Index(value = ["name"], unique = true)])
data class GenreEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
)

@Entity(
    tableName = "song_genres",
    primaryKeys = ["song_id", "genre_id", "source"],
    foreignKeys = [
        ForeignKey(SongEntity::class, ["id"], ["song_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(GenreEntity::class, ["id"], ["genre_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["genre_id"])],
)
data class SongGenreEntity(
    @ColumnInfo(name = "song_id") val songId: String,
    @ColumnInfo(name = "genre_id") val genreId: Long,
    val source: GenreSource,
    val confidence: Float,
)

/** A new session starts after a 30-minute gap between plays. */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "ended_at") val endedAt: Long? = null,
)

/**
 * Every play attempt, including a 2-second skip. The source of truth for all scores;
 * rows are never deleted (songs are soft-deleted, so there is no cascade).
 */
@Entity(
    tableName = "play_events",
    foreignKeys = [
        ForeignKey(SongEntity::class, ["id"], ["song_id"]),
        ForeignKey(SessionEntity::class, ["id"], ["session_id"]),
    ],
    indices = [
        Index(value = ["song_id", "started_at"]),
        Index(value = ["started_at"]),
        Index(value = ["session_id"]),
    ],
)
data class PlayEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "song_id") val songId: String,
    @ColumnInfo(name = "session_id") val sessionId: Long,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "tz_offset_min") val tzOffsetMin: Int,
    @ColumnInfo(name = "listened_ms") val listenedMs: Long,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    @ColumnInfo(name = "end_reason") val endReason: EndReason,
    @ColumnInfo(name = "initiated_by") val initiatedBy: InitiatedBy,
    @ColumnInfo(name = "mode_at_play") val modeAtPlay: ListenMode,
    @ColumnInfo(name = "queue_tag") val queueTag: QueueTag? = null,
    @ColumnInfo(name = "queue_context") val queueContext: String,
    @ColumnInfo(name = "context_size") val contextSize: Int,
    @ColumnInfo(name = "repeat_mode") val repeatMode: RepeatMode,
    val source: EventSource = EventSource.APP,
)

@Entity(tableName = "mode_changes", indices = [Index(value = ["changed_at"])])
data class ModeChangeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mode: ListenMode,
    @ColumnInfo(name = "changed_at") val changedAt: Long,
)

/** Current scores and their components; rebuilt from play_events when params_version changes. */
@Entity(
    tableName = "song_stats",
    foreignKeys = [
        ForeignKey(SongEntity::class, ["id"], ["song_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index(value = ["discovery_score"]),
        Index(value = ["repeat_score"]),
    ],
)
data class SongStatsEntity(
    @PrimaryKey @ColumnInfo(name = "song_id") val songId: String,
    @ColumnInfo(name = "play_count") val playCount: Int = 0,
    @ColumnInfo(name = "qualified_listen_count") val qualifiedListenCount: Int = 0,
    @ColumnInfo(name = "skip_count") val skipCount: Int = 0,
    @ColumnInfo(name = "manual_play_count") val manualPlayCount: Int = 0,
    @ColumnInfo(name = "first_played_at") val firstPlayedAt: Long? = null,
    @ColumnInfo(name = "last_played_at") val lastPlayedAt: Long? = null,
    @ColumnInfo(name = "discovery_score") val discoveryScore: Double = 0.0,
    @ColumnInfo(name = "repeat_score") val repeatScore: Double = 0.0,
    @ColumnInfo(name = "low_play") val lowPlay: Double = 1.0,
    @ColumnInfo(name = "genre_affinity") val genreAffinity: Double = 0.0,
    @ColumnInfo(name = "wait_bonus") val waitBonus: Double = 0.0,
    val activation: Double? = null,
    val penalty: Double = 0.0,
    @ColumnInfo(name = "beta_a") val betaA: Double = 0.0,
    @ColumnInfo(name = "beta_b") val betaB: Double = 0.0,
    @ColumnInfo(name = "evidence_updated_at") val evidenceUpdatedAt: Long,
    @ColumnInfo(name = "computed_at") val computedAt: Long,
    @ColumnInfo(name = "params_version") val paramsVersion: Int,
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "cover_path") val coverPath: String? = null,
)

@Entity(
    tableName = "playlist_items",
    primaryKeys = ["playlist_id", "song_id"],
    foreignKeys = [
        ForeignKey(PlaylistEntity::class, ["id"], ["playlist_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(SongEntity::class, ["id"], ["song_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["song_id"])],
)
data class PlaylistItemEntity(
    @ColumnInfo(name = "playlist_id") val playlistId: Long,
    @ColumnInfo(name = "song_id") val songId: String,
    /** Fractional so a song can be inserted between two others without renumbering. */
    val position: Double,
    @ColumnInfo(name = "added_at") val addedAt: Long,
)

@Entity(
    tableName = "lyrics",
    foreignKeys = [
        ForeignKey(SongEntity::class, ["id"], ["song_id"], onDelete = ForeignKey.CASCADE),
    ],
)
data class LyricsEntity(
    @PrimaryKey @ColumnInfo(name = "song_id") val songId: String,
    /** Static lyrics. LRC timestamps are stripped before saving. */
    @ColumnInfo(name = "plain_text") val plainText: String? = null,
    /** Null when nothing was found (NOT_FOUND / ERROR rows). */
    val source: LyricsSource? = null,
    @ColumnInfo(name = "source_ref") val sourceRef: String? = null,
    val status: LyricsStatus,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long,
    @ColumnInfo(name = "retry_after") val retryAfter: Long? = null,
)

@Entity(tableName = "downloads", indices = [Index(value = ["status"])])
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "youtube_id") val youtubeId: String,
    val status: DownloadStatus,
    val progress: Float = 0f,
    val attempts: Int = 0,
    @ColumnInfo(name = "error_message") val errorMessage: String? = null,
    @ColumnInfo(name = "requested_at") val requestedAt: Long,
    @ColumnInfo(name = "finished_at") val finishedAt: Long? = null,
)

/** A user's manual correction saved as a rule for later downloads from the same channel or title pattern. */
@Entity(tableName = "parse_rules")
data class ParseRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "match_type") val matchType: ParseMatchType,
    val pattern: String,
    @ColumnInfo(name = "artist_override") val artistOverride: String? = null,
    @ColumnInfo(name = "title_override") val titleOverride: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
