package com.example.musicapp.library

import com.example.musicapp.data.db.ArtistRole
import com.example.musicapp.data.db.ParseMethod
import java.util.Locale

/** Tag values as the system reports them; any of them may be missing or junk. */
data class RawTags(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val genre: String? = null,
)

data class ParsedArtist(val name: String, val role: ArtistRole)

data class ResolvedMetadata(
    val title: String,
    /** Empty means "Bilinmeyen sanatçı". */
    val artists: List<ParsedArtist>,
    val album: String?,
    val genres: List<String>,
    val parseMethod: ParseMethod,
)

/**
 * Display title, artists and album for a local file (docs/veritabani.md, section 12).
 * Tags first, file name and folder when a tag is missing or junk. Only reads; never changes the file.
 */
object MetadataResolver {
    private val TR = Locale.forLanguageTag("tr")

    private val PLACEHOLDERS = setOf(
        "unknown", "<unknown>", "unknown artist", "unknown album", "unknown title",
        "bilinmeyen", "bilinmeyen sanatçı", "bilinmeyen albüm", "untitled", "isimsiz",
    )
    private val VARIOUS = setOf("various artists", "various", "va", "çeşitli sanatçılar", "karışık")
    private val TRACK_PLACEHOLDER = Regex("""^(track|parça|audio track|kayıt)\s*\d*$""", RegexOption.IGNORE_CASE)
    private val WHATSAPP = Regex("""^(AUD|PTT)-\d{8}-WA\d+$""", RegexOption.IGNORE_CASE)
    private val SITE = Regex("""(?i)\b(?:www\.)?[a-z0-9-]+\.(?:com|net|org|info|biz|tk|tv|me)(?:\.[a-z]{2})?\b""")
    private val AD_WORDS = Regex("""(?i)\b(?:www\.|mp3\s*indir|indir\b)""")

    private val NOISE_WORDS = listOf(
        "official music video", "official video", "official audio", "official lyric video", "official",
        "lyric video", "lyrics video", "lyrics", "lyric", "audio", "video", "video klip", "klip",
        "şarkı sözleri", "sözleri", "hd", "hq", "4k", "mv", "full hd", "1080p", "720p", "mp3",
    )
    private val NOISE_GROUP = Regex(
        """\s*[(\[]\s*(?:${NOISE_WORDS.joinToString("|") { Regex.escape(it) }})\s*[)\]]""",
        RegexOption.IGNORE_CASE,
    )
    private val BITRATE = Regex("""(?i)\s*[(\[]?\s*\d{2,3}\s*kbps\s*[)\]]?""")
    private val COPY_SUFFIX = Regex("""(?i)(?:\s+-\s+(?:copy|kopya)|\s*\(\d{1,2}\))\s*$""")
    private val LEADING_TRACK = Regex("""^(?:\d{1,3}\s*[.)]\s*|\d{1,3}\s+-\s+|0\d\s+|\d{1,2}-\d{1,3}\s+)""")
    private val SEPARATOR = Regex("""\s+[-–—]\s+""")
    private val FEAT = Regex(
        """\s*(?:[(\[]\s*(?:feat\.?|ft\.?|featuring|with)\s+([^)\]]+)[)\]]|\s(?:feat\.?|ft\.?|featuring)\s+(.+)$)""",
        RegexOption.IGNORE_CASE,
    )
    private val FEAT_SPLIT = Regex("""\s*(?:,|&|\band\b|\bve\b)\s*""", RegexOption.IGNORE_CASE)

    private val GENERIC_FOLDERS = setOf(
        "music", "müzik", "muzik", "download", "downloads", "indirilenler", "audio", "ses", "sounds",
        "whatsapp audio", "telegram audio", "bluetooth", "recordings", "kayıtlar", "media", "documents",
        "storage", "emulated", "0", "sdcard", "songs", "şarkılar", "mp3", "my music", "playlists",
        "ringtones", "notifications", "alarms", "podcasts", "voice recorder", "record", "telegram", "whatsapp",
    )

    /**
     * @param relativePath folder of the file, e.g. "Music/Duman/" (MediaStore RELATIVE_PATH).
     * @param fileName file name with extension.
     */
    fun resolve(tags: RawTags, relativePath: String, fileName: String): ResolvedMetadata {
        val stem = fileName.substringBeforeLast('.', fileName)
        val folder = relativePath.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').trim()

        // A title tag equal to the file name means "no tag" (MediaStore fills TITLE from the name).
        val tagTitle = tags.title?.trim()?.takeUnless { isJunk(it) || it == stem }
        val tagArtist = tags.artist?.trim()?.takeUnless { isJunk(it) || it.lowercase(TR) in VARIOUS }
        val tagAlbum = tags.album?.trim()?.takeUnless { isJunk(it) || it == folder && folder.lowercase(TR) in GENERIC_FOLDERS }

        val fromName = parseFileName(stem)
        val fromFolder = parseFolder(folder)

        val title = clean(tagTitle ?: fromName.title).ifBlank { stem }
        val artistRaw = tagArtist ?: fromName.artist ?: fromFolder.artist
        val album = tagAlbum ?: fromFolder.album

        val (mainTitle, featFromTitle) = extractFeat(title)
        val (mainArtist, featFromArtist) = artistRaw?.let { extractFeat(it) } ?: (null to emptyList())

        val artists = buildList {
            mainArtist?.takeIf { it.isNotBlank() }?.let { add(ParsedArtist(it, ArtistRole.MAIN)) }
            (featFromArtist + featFromTitle).distinctBy { ArtistNames.normalize(it) }
                .filter { f -> none { ArtistNames.normalize(it.name) == ArtistNames.normalize(f) } }
                .forEach { add(ParsedArtist(it, ArtistRole.FEAT)) }
        }

        val genres = tags.genre?.split(';', '/', ',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() && !isJunk(it) && !it.all(Char::isDigit) }
            .orEmpty()

        return ResolvedMetadata(
            title = mainTitle.ifBlank { title },
            artists = artists,
            album = album,
            genres = genres,
            parseMethod = if (tagTitle != null && tagArtist != null) ParseMethod.FILE_TAGS else ParseMethod.FILENAME,
        )
    }

    fun isJunk(value: String): Boolean {
        val v = value.trim()
        if (v.isEmpty()) return true
        val lower = v.lowercase(TR)
        return lower in PLACEHOLDERS || TRACK_PLACEHOLDER.matches(v) || WHATSAPP.matches(v) ||
            SITE.containsMatchIn(v) || AD_WORDS.containsMatchIn(v)
    }

    private data class NameParts(val artist: String?, val title: String)

    private fun parseFileName(stem: String): NameParts {
        var s = stem
        if (' ' !in s && '_' in s) s = s.replace('_', ' ')
        s = s.replace(Regex("""\s+"""), " ").trim()
        s = clean(s)
        s = s.replaceFirst(LEADING_TRACK, "").trim()
        val parts = s.split(SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }
        return if (parts.size >= 2) NameParts(parts[0], parts.drop(1).joinToString(" - "))
        else NameParts(null, parts.firstOrNull() ?: s)
    }

    private data class FolderParts(val artist: String?, val album: String?)

    private fun parseFolder(folder: String): FolderParts {
        if (folder.isBlank() || folder.lowercase(TR) in GENERIC_FOLDERS || isJunk(folder)) return FolderParts(null, null)
        val parts = folder.split(SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }
        return if (parts.size >= 2) FolderParts(parts[0], parts.drop(1).joinToString(" - "))
        else FolderParts(folder, null)
    }

    /** Removes noise (official video, HD, bitrate, sites, copy suffixes); keeps Remix, Live, Cover, etc. */
    private fun clean(value: String): String {
        var s = value
        s = SITE.replace(s, " ")
        s = NOISE_GROUP.replace(s, "")
        s = BITRATE.replace(s, "")
        s = COPY_SUFFIX.replace(s, "")
        s = s.replace(Regex("""\s+"""), " ").trim()
        // Separators left dangling after removing a site name: "- Duman - Bu Akşam".
        s = s.replace(Regex("""^[-–—\s]+|[-–—\s]+$"""), "")
        return s
    }

    /** Splits "X feat. Y" / "X (ft. Y & Z)" into the main part and featured artists. */
    private fun extractFeat(value: String): Pair<String, List<String>> {
        val match = FEAT.find(value) ?: return value.trim() to emptyList()
        val names = (match.groupValues[1].ifEmpty { match.groupValues[2] })
            .split(FEAT_SPLIT).map { it.trim() }.filter { it.isNotEmpty() }
        val rest = value.removeRange(match.range).replace(Regex("""\s+"""), " ").trim()
        return rest to names
    }
}

object ArtistNames {
    private val TR = Locale.forLanguageTag("tr")
    private val SUFFIX = Regex("""(?i)\s*(?:-\s*topic|vevo|official)\s*$""")

    /** Key that makes "Duman - Topic", "DUMAN VEVO" and "duman" the same artist (docs/veritabani.md). */
    fun normalize(name: String): String =
        SUFFIX.replace(name.trim(), "").replace(Regex("""\s+"""), " ").trim().lowercase(TR)
}
