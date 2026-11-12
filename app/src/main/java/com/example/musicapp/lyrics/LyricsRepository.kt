package com.example.musicapp.lyrics

import android.content.Context
import com.example.musicapp.data.db.LyricsEntity
import com.example.musicapp.data.db.LyricsSource
import com.example.musicapp.data.db.LyricsStatus
import com.example.musicapp.data.db.MusicDatabase
import com.example.musicapp.data.settings.AppSettings
import com.example.musicapp.library.LibraryScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.math.abs

@Serializable
private data class LrclibRecord(
    val id: Long = 0,
    val duration: Double? = null,
    val instrumental: Boolean = false,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
)

/**
 * Finds static lyrics when the lyrics screen opens (docs/veritabani.md, section 13):
 * 1. lyrics embedded in the file, 2. LRCLIB. Results, including "not found", are cached in `lyrics`.
 */
class LyricsRepository(
    private val context: Context,
    private val db: MusicDatabase,
    private val settings: AppSettings,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    /** Cached result, or a fresh lookup when there is none / it may be retried. [force] ignores the 30-day wait. */
    suspend fun lyricsFor(songId: String, force: Boolean = false): LyricsEntity = withContext(Dispatchers.IO) {
        val cached = db.lyricsDao().get(songId)
        val now = System.currentTimeMillis()
        if (cached != null && !force) {
            val settled = cached.status == LyricsStatus.FOUND || cached.status == LyricsStatus.INSTRUMENTAL
            val waiting = cached.status == LyricsStatus.NOT_FOUND && (cached.retryAfter ?: 0) > now
            if (settled || waiting || cached.source == LyricsSource.USER) return@withContext cached
        }
        val result = lookup(songId, now)
        db.lyricsDao().upsert(result)
        result
    }

    /** The user typed or pasted lyrics; automatic lookups never overwrite them. */
    suspend fun saveUserLyrics(songId: String, text: String) = withContext(Dispatchers.IO) {
        db.lyricsDao().upsert(
            LyricsEntity(
                songId = songId,
                plainText = text.trim(),
                source = LyricsSource.USER,
                status = LyricsStatus.FOUND,
                fetchedAt = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun lookup(songId: String, now: Long): LyricsEntity {
        val song = db.songDao().getById(songId)
        embedded(song?.mediaId)?.let {
            return LyricsEntity(songId, LrcText.toPlain(it), LyricsSource.EMBEDDED, null, LyricsStatus.FOUND, now)
        }
        if (!settings.current().lyricsOnline || song == null) {
            return LyricsEntity(songId, null, null, null, LyricsStatus.NOT_FOUND, now, retryAfter = now + RETRY_MS)
        }
        val item = db.songDao().listItemById(songId)
        val artist = item?.artistNames?.substringBefore(", ")?.trim().orEmpty()
        val album = item?.albumTitle
        val durationSec = song.durationMs / 1000.0
        return try {
            val record = fetch(song.title, artist, album, durationSec)
                ?: song.title.replace(Regex("""\s*[(\[][^)\]]*[)\]]\s*"""), " ").trim()
                    .takeIf { it != song.title && it.isNotEmpty() }
                    ?.let { fetch(it, artist, null, durationSec) }
            when {
                record == null -> LyricsEntity(songId, null, null, null, LyricsStatus.NOT_FOUND, now, retryAfter = now + RETRY_MS)
                record.instrumental -> LyricsEntity(songId, null, LyricsSource.LRCLIB, record.id.toString(), LyricsStatus.INSTRUMENTAL, now)
                else -> {
                    val text = record.plainLyrics?.takeIf { it.isNotBlank() } ?: record.syncedLyrics?.let(LrcText::toPlain)
                    if (text.isNullOrBlank()) LyricsEntity(songId, null, null, null, LyricsStatus.NOT_FOUND, now, retryAfter = now + RETRY_MS)
                    else LyricsEntity(songId, text.trim(), LyricsSource.LRCLIB, record.id.toString(), LyricsStatus.FOUND, now)
                }
            }
        } catch (e: java.io.IOException) {
            LyricsEntity(songId, null, null, null, LyricsStatus.ERROR, now)
        }
    }

    /** `/api/get` first (exact), then `/api/search` with a ±3 s duration check. */
    private fun fetch(title: String, artist: String, album: String?, durationSec: Double): LrclibRecord? {
        if (artist.isNotEmpty()) {
            val url = "$BASE/get".toHttpUrl().newBuilder()
                .addQueryParameter("track_name", title)
                .addQueryParameter("artist_name", artist)
                .apply { if (!album.isNullOrBlank()) addQueryParameter("album_name", album) }
                .addQueryParameter("duration", durationSec.toInt().toString())
                .build()
            get(url.toString())?.let { return json.decodeFromString(LrclibRecord.serializer(), it) }
        }
        val search = "$BASE/search".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", title)
            .apply { if (artist.isNotEmpty()) addQueryParameter("artist_name", artist) }
            .build()
        val body = get(search.toString()) ?: return null
        return json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(LrclibRecord.serializer()), body)
            .firstOrNull { r -> r.duration != null && abs(r.duration - durationSec) <= 3.0 }
    }

    /** Body of a 200 response, null for 404; other failures throw IOException. */
    private fun get(url: String): String? {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        http.newCall(request).execute().use { resp ->
            if (resp.code == 404) return null
            if (!resp.isSuccessful) throw java.io.IOException("LRCLIB ${resp.code}")
            return resp.body.string()
        }
    }

    private fun embedded(mediaId: Long?): String? {
        if (mediaId == null) return null
        return runCatching {
            context.contentResolver.openInputStream(LibraryScanner.contentUri(mediaId))?.use { input ->
                val header = ByteArray(Id3Lyrics.HEADER)
                if (input.readNBytesCompat(header) < header.size) return null
                val length = Id3Lyrics.tagLength(header)
                if (length == 0 || length > MAX_TAG) return null
                val rest = ByteArray(length - header.size)
                input.readNBytesCompat(rest)
                Id3Lyrics.parse(header + rest)
            }
        }.getOrNull()
    }

    private fun java.io.InputStream.readNBytesCompat(buf: ByteArray): Int {
        var read = 0
        while (read < buf.size) {
            val n = read(buf, read, buf.size - read)
            if (n < 0) break
            read += n
        }
        return read
    }

    companion object {
        private const val BASE = "https://lrclib.net/api"
        // LRCLIB asks clients to identify themselves; the app name is not final yet.
        private const val USER_AGENT = "MusicApp/1.0 (Android; kisisel muzik calar)"
        private const val RETRY_MS = 30L * 24 * 3600 * 1000
        private const val MAX_TAG = 8 * 1024 * 1024
    }
}
