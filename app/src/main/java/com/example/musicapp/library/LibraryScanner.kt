package com.example.musicapp.library

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.room.withTransaction
import com.example.musicapp.data.db.AlbumEntity
import com.example.musicapp.data.db.ArtistEntity
import com.example.musicapp.data.db.ArtistRole
import com.example.musicapp.data.db.GenreEntity
import com.example.musicapp.data.db.GenreSource
import com.example.musicapp.data.db.MusicDatabase
import com.example.musicapp.data.db.SongArtistEntity
import com.example.musicapp.data.db.SongEntity
import com.example.musicapp.data.db.SongGenreEntity
import com.example.musicapp.data.db.SongSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.nio.ByteBuffer

/**
 * Finds the audio files on the device and keeps the songs table in sync (docs/veritabani.md, section 2).
 * Read-only towards the files: nothing is renamed, moved, deleted or retagged.
 */
class LibraryScanner(private val context: Context, private val db: MusicDatabase) {

    data class Result(val added: Int, val updated: Int, val missing: Int, val total: Int)

    private data class MediaRow(
        val mediaId: Long,
        val uri: Uri,
        val filePath: String,
        val relativePath: String,
        val fileName: String,
        val size: Long,
        val durationMs: Long,
        val modifiedAt: Long,
        val mime: String?,
        val bitrateKbps: Int?,
        val tags: RawTags,
    )

    /** Folders that contain audio, with file counts (for the folder picker in Settings). */
    suspend fun folders(minDurationMs: Long = 30_000): List<Pair<String, Int>> = withContext(Dispatchers.IO) {
        queryMediaStore(minDurationMs).groupingBy { it.relativePath }.eachCount().toList().sortedBy { it.first.lowercase() }
    }

    /**
     * @param includedFolders only files under these folders (prefix match, so subfolders count) are in
     *   the library, like VLC's folder selection; null = every folder. Files outside are marked missing,
     *   so their history comes back if the folder is selected again.
     */
    suspend fun scan(minDurationMs: Long = 30_000, includedFolders: Set<String>? = null): Result = withContext(Dispatchers.IO) {
        val rows = queryMediaStore(minDurationMs).filter { row ->
            includedFolders == null || includedFolders.any { row.relativePath.startsWith(it) }
        }
        val existing = db.songDao().allLocal()
        val byPath = existing.associateBy { it.filePath }.toMutableMap()
        val byHash = existing.groupBy { it.contentHash }.toMutableMap()
        val seen = HashSet<String>()
        var added = 0
        var updated = 0
        val now = System.currentTimeMillis()

        for (batch in rows.chunked(100)) {
            db.withTransaction {
                for (row in batch) {
                    val known = byPath[row.filePath]
                    // Unchanged file: no need to read it again.
                    if (known != null && known.fileSize == row.size && known.fileModifiedAt == row.modifiedAt) {
                        if (known.mediaId != row.mediaId || known.isMissing) {
                            db.songDao().upsert(known.copy(mediaId = row.mediaId, isMissing = false))
                        }
                        seen += known.id
                        continue
                    }
                    val hash = runCatching { hashOf(row) }.getOrNull() ?: continue
                    val match = known
                        ?: byHash[hash]?.firstOrNull { it.id !in seen }
                        ?: existing.firstOrNull {
                            it.id !in seen && it.isMissing && it.fileSize == row.size && it.durationMs == row.durationMs
                        }
                    val meta = MetadataResolver.resolve(row.tags, row.relativePath, row.fileName)
                    if (match != null) {
                        var song = match.copy(
                            filePath = row.filePath, fileSize = row.size, fileModifiedAt = row.modifiedAt,
                            contentHash = hash, mediaId = row.mediaId, isMissing = false,
                            durationMs = row.durationMs, codec = codecOf(row), bitrateKbps = row.bitrateKbps,
                        )
                        if (!match.userEdited) song = applyMetadata(song, meta)
                        db.songDao().upsert(song)
                        seen += song.id
                        updated++
                    } else {
                        var id = ContentHasher.localId(hash)
                        var n = 2
                        while (id in seen || db.songDao().exists(id)) id = ContentHasher.localId(hash) + "-" + n++
                        val song = applyMetadata(
                            SongEntity(
                                id = id, source = SongSource.LOCAL, title = meta.title, durationMs = row.durationMs,
                                filePath = row.filePath, fileSize = row.size, codec = codecOf(row),
                                bitrateKbps = row.bitrateKbps, contentHash = hash, addedAt = now,
                                parseMethod = meta.parseMethod, mediaId = row.mediaId, fileModifiedAt = row.modifiedAt,
                            ),
                            meta,
                        )
                        db.songDao().upsert(song)
                        byPath[song.filePath] = song
                        byHash.getOrPut(hash) { emptyList() }.let { byHash[hash] = it + song }
                        seen += id
                        added++
                    }
                }
            }
        }
        val gone = existing.filter { it.id !in seen && !it.isMissing }.map { it.id }
        gone.chunked(500).forEach { db.songDao().markMissing(it) }
        Result(added, updated, missing = gone.size, total = seen.size)
    }

    /** Writes title, artists, album and genres derived from tags/file name. Only the database changes. */
    private suspend fun applyMetadata(song: SongEntity, meta: ResolvedMetadata): SongEntity {
        val artistIds = meta.artists.map { artistId(it.name) }
        val mainArtist = meta.artists.indexOfFirst { it.role == ArtistRole.MAIN }.takeIf { it >= 0 }?.let { artistIds[it] }
        val albumId = meta.album?.let { title ->
            db.albumDao().find(title, mainArtist)?.id ?: db.albumDao().insert(AlbumEntity(title = title, albumArtistId = mainArtist))
        }
        val updated = song.copy(
            title = meta.title,
            albumId = albumId,
            parseMethod = meta.parseMethod,
            searchText = TextFold.fold(meta.title + " " + meta.artists.joinToString(" ") { it.name } + " " + (meta.album ?: "")),
        )
        db.songDao().upsert(updated)
        db.artistDao().unlinkSong(song.id)
        meta.artists.forEachIndexed { i, a ->
            db.artistDao().link(SongArtistEntity(song.id, artistIds[i], a.role, i))
        }
        db.genreDao().unlinkSong(song.id, GenreSource.FILE_TAGS)
        for (name in meta.genres) {
            val gid = db.genreDao().find(name)?.id ?: db.genreDao().insert(GenreEntity(name = name))
            db.genreDao().link(SongGenreEntity(song.id, gid, GenreSource.FILE_TAGS, 0.8f))
        }
        return updated
    }

    private suspend fun artistId(name: String): Long {
        val normalized = ArtistNames.normalize(name)
        return db.artistDao().findByNormalized(normalized)?.id
            ?: db.artistDao().insert(ArtistEntity(name = name.trim(), normalizedName = normalized))
    }

    private fun codecOf(row: MediaRow): String? =
        row.mime?.substringAfter('/')?.removePrefix("x-") ?: row.fileName.substringAfterLast('.', "").ifEmpty { null }

    private fun hashOf(row: MediaRow): String {
        context.contentResolver.openFileDescriptor(row.uri, "r")!!.use { pfd ->
            FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                val size = ch.size()
                val head = ByteBuffer.allocate(minOf(ContentHasher.CHUNK.toLong(), size).toInt())
                ch.read(head, 0)
                val tailLen = minOf(ContentHasher.CHUNK.toLong(), size).toInt()
                val tail = ByteBuffer.allocate(tailLen)
                ch.read(tail, size - tailLen)
                return ContentHasher.hash(size, head.array(), tail.array())
            }
        }
    }

    private fun queryMediaStore(minDurationMs: Long): List<MediaRow> {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val cols = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.DISPLAY_NAME)
            add(MediaStore.Audio.Media.SIZE)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.DATE_MODIFIED)
            add(MediaStore.Audio.Media.MIME_TYPE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(MediaStore.Audio.Media.RELATIVE_PATH)
            else @Suppress("DEPRECATION") add(MediaStore.Audio.Media.DATA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(MediaStore.Audio.Media.GENRE)
                add(MediaStore.Audio.Media.BITRATE)
            }
        }.toTypedArray()
        val selection = "${MediaStore.Audio.Media.DURATION} >= ? AND ${MediaStore.Audio.Media.IS_RINGTONE} = 0 " +
            "AND ${MediaStore.Audio.Media.IS_NOTIFICATION} = 0 AND ${MediaStore.Audio.Media.IS_ALARM} = 0"
        val out = ArrayList<MediaRow>()
        context.contentResolver.query(collection, cols, selection, arrayOf(minDurationMs.toString()), null)?.use { c ->
            fun str(col: String) = c.getColumnIndex(col).takeIf { it >= 0 }?.let { c.getString(it) }
            fun long(col: String) = c.getColumnIndex(col).takeIf { it >= 0 }?.let { c.getLong(it) }
            while (c.moveToNext()) {
                val id = long(MediaStore.Audio.Media._ID) ?: continue
                val name = str(MediaStore.Audio.Media.DISPLAY_NAME) ?: continue
                val relative = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    str(MediaStore.Audio.Media.RELATIVE_PATH).orEmpty()
                } else {
                    @Suppress("DEPRECATION")
                    str(MediaStore.Audio.Media.DATA).orEmpty().substringBeforeLast('/', "")
                        .replace(Regex("""^/storage/[^/]+/(?:0/)?"""), "") + "/"
                }
                val bitrate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    long(MediaStore.Audio.Media.BITRATE)?.let { (it / 1000).toInt() }?.takeIf { it > 0 }
                } else null
                val genre = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) str(MediaStore.Audio.Media.GENRE) else null
                out += MediaRow(
                    mediaId = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    filePath = relative.trimStart('/') + name,
                    relativePath = relative,
                    fileName = name,
                    size = long(MediaStore.Audio.Media.SIZE) ?: 0,
                    durationMs = long(MediaStore.Audio.Media.DURATION) ?: 0,
                    modifiedAt = (long(MediaStore.Audio.Media.DATE_MODIFIED) ?: 0) * 1000,
                    mime = str(MediaStore.Audio.Media.MIME_TYPE),
                    bitrateKbps = bitrate,
                    tags = RawTags(
                        title = str(MediaStore.Audio.Media.TITLE),
                        artist = str(MediaStore.Audio.Media.ARTIST),
                        album = str(MediaStore.Audio.Media.ALBUM),
                        genre = genre,
                    ),
                )
            }
        }
        return out
    }

    companion object {
        fun contentUri(mediaId: Long): Uri =
            ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaId)
    }
}
