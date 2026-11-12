package com.example.musicapp.library

import com.example.musicapp.data.db.ArtistRole
import com.example.musicapp.data.db.ParseMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataResolverTest {

    private fun fromPath(path: String, tags: RawTags = RawTags()): ResolvedMetadata {
        val folder = path.substringBeforeLast('/', "")
        val name = path.substringAfterLast('/')
        return MetadataResolver.resolve(tags, if (folder.isEmpty()) "Music/" else "Music/$folder/", name)
    }

    private fun assertParsed(path: String, title: String, vararg artists: String, album: String? = null) {
        val r = fromPath(path)
        assertEquals("title of $path", title, r.title)
        assertEquals("artists of $path", artists.toList(), r.artists.map { it.name })
        if (album != null) assertEquals("album of $path", album, r.album)
    }

    // The table in docs/veritabani.md, section 12.3.
    @Test fun plain() = assertParsed("Duman - Bu Akşam.mp3", "Bu Akşam", "Duman")
    @Test fun noiseAndTrackNumber() = assertParsed("03. Duman - Bu Akşam (Official Video) [HD].mp3", "Bu Akşam", "Duman")
    @Test fun underscores() = assertParsed("Duman_-_Bu_Aksam.mp3", "Bu Aksam", "Duman")
    @Test fun versionWordsAreKept() = assertParsed("Ezhel - Felaket (Remix).mp3", "Felaket (Remix)", "Ezhel")
    @Test fun artistFromFolder() = assertParsed("Duman/Bu Akşam.mp3", "Bu Akşam", "Duman")
    @Test fun artistAndAlbumFromFolder() =
        assertParsed("Duman - Belki Alışman Lazım/05 Bu Akşam.mp3", "Bu Akşam", "Duman", album = "Belki Alışman Lazım")
    @Test fun genericFolderGivesUnknownArtist() = assertParsed("Download/Bu Akşam.mp3", "Bu Akşam")
    @Test fun siteNameIsRemoved() = assertParsed("www.mp3indir.com - Duman - Bu Akşam.mp3", "Bu Akşam", "Duman")
    @Test fun reversedOrderIsTakenLiterally() = assertParsed("Bu Akşam - Duman (1).mp3", "Duman", "Bu Akşam")

    @Test
    fun featuredArtistFromTitle() {
        val r = fromPath("Ceza - Neyim Var Ki ft. Sagopa Kajmer.mp3")
        assertEquals("Neyim Var Ki", r.title)
        assertEquals(listOf(ParsedArtist("Ceza", ArtistRole.MAIN), ParsedArtist("Sagopa Kajmer", ArtistRole.FEAT)), r.artists)
    }

    @Test
    fun ampersandIsNotSplitInMainArtist() =
        assertParsed("Simon & Garfunkel - The Boxer.mp3", "The Boxer", "Simon & Garfunkel")

    @Test
    fun numberInArtistNameIsKept() = assertParsed("50 Cent - In da Club.mp3", "In da Club", "50 Cent")

    @Test
    fun goodTagsWin() {
        val r = fromPath(
            "track01.mp3",
            RawTags(title = "Bu Akşam", artist = "Duman", album = "Belki Alışman Lazım", genre = "Rock"),
        )
        assertEquals("Bu Akşam", r.title)
        assertEquals(listOf("Duman"), r.artists.map { it.name })
        assertEquals("Belki Alışman Lazım", r.album)
        assertEquals(listOf("Rock"), r.genres)
        assertEquals(ParseMethod.FILE_TAGS, r.parseMethod)
    }

    @Test
    fun junkArtistTagFallsBackToFileName() {
        for (junk in listOf("<unknown>", "Unknown Artist", "www.mp3indirdur.com", "Bilinmeyen sanatçı", "")) {
            val r = fromPath("Duman - Bu Akşam.mp3", RawTags(title = "Bu Akşam", artist = junk))
            assertEquals("junk '$junk'", listOf("Duman"), r.artists.map { it.name })
            assertEquals(ParseMethod.FILENAME, r.parseMethod)
        }
    }

    @Test
    fun titleTagEqualToFileNameIsTreatedAsMissing() {
        // MediaStore fills TITLE with the file name when the file has no tag.
        val r = fromPath("Duman - Bu Akşam.mp3", RawTags(title = "Duman - Bu Akşam", artist = "<unknown>"))
        assertEquals("Bu Akşam", r.title)
        assertEquals(listOf("Duman"), r.artists.map { it.name })
    }

    @Test
    fun featInArtistTag() {
        val r = fromPath("x.mp3", RawTags(title = "Neyim Var Ki", artist = "Ceza feat. Sagopa Kajmer"))
        assertEquals(listOf("Ceza", "Sagopa Kajmer"), r.artists.map { it.name })
        assertEquals(ArtistRole.FEAT, r.artists[1].role)
    }

    @Test
    fun variousArtistsIsNotTheSongArtist() {
        val r = fromPath("Duman - Bu Akşam.mp3", RawTags(title = "Bu Akşam", artist = "Various Artists"))
        assertEquals(listOf("Duman"), r.artists.map { it.name })
    }

    @Test
    fun whatsAppVoiceNoteIsJunk() {
        assertTrue(MetadataResolver.isJunk("AUD-20241103-WA0012"))
        assertTrue(MetadataResolver.isJunk("Track 01"))
    }

    @Test
    fun noAlbumWhenUnknown() = assertNull(fromPath("Duman - Bu Akşam.mp3").album)

    @Test
    fun artistNormalization() {
        assertEquals(ArtistNames.normalize("Duman"), ArtistNames.normalize("Duman - Topic"))
        assertEquals(ArtistNames.normalize("duman"), ArtistNames.normalize("DUMAN VEVO"))
        assertEquals("ılık", ArtistNames.normalize("ILIK"))
    }

    @Test
    fun hashIsStable() {
        val h1 = ContentHasher.hash(10, byteArrayOf(1, 2), byteArrayOf(3))
        val h2 = ContentHasher.hash(10, byteArrayOf(1, 2), byteArrayOf(3))
        assertEquals(h1, h2)
        assertEquals(16, h1.length)
        assertTrue(ContentHasher.hash(11, byteArrayOf(1, 2), byteArrayOf(3)) != h1)
    }
}
