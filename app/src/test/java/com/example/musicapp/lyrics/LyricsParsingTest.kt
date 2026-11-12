package com.example.musicapp.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

class LyricsParsingTest {

    private fun synchsafe(n: Int) = byteArrayOf(
        ((n shr 21) and 0x7F).toByte(), ((n shr 14) and 0x7F).toByte(), ((n shr 7) and 0x7F).toByte(), (n and 0x7F).toByte(),
    )

    private fun frame(id: String, body: ByteArray, major: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(id.toByteArray(Charsets.ISO_8859_1))
        if (major == 4) out.write(synchsafe(body.size))
        else out.write(byteArrayOf((body.size shr 24).toByte(), (body.size shr 16).toByte(), (body.size shr 8).toByte(), body.size.toByte()))
        out.write(byteArrayOf(0, 0))
        out.write(body)
        return out.toByteArray()
    }

    private fun tag(major: Int, vararg frames: ByteArray): ByteArray {
        val body = frames.fold(ByteArray(0)) { acc, f -> acc + f } + ByteArray(20) // padding
        return "ID3".toByteArray() + byteArrayOf(major.toByte(), 0, 0) + synchsafe(body.size) + body
    }

    @Test
    fun utf8LyricsInId3v24() {
        val text = "Bu akşam ölürüm\nBeni kimse durduramaz"
        val uslt = byteArrayOf(3) + "tur".toByteArray() + byteArrayOf(0) + text.toByteArray(Charsets.UTF_8)
        val t = tag(4, frame("TIT2", byteArrayOf(3) + "Bu Akşam".toByteArray(), 4), frame("USLT", uslt, 4))
        assertEquals(t.size, Id3Lyrics.tagLength(t.copyOf(10)))
        assertEquals(text, Id3Lyrics.parse(t))
    }

    @Test
    fun utf16LyricsWithDescriptorInId3v23() {
        val text = "Şarkı sözü"
        val uslt = byteArrayOf(1) + "tur".toByteArray() +
            "açıklama".toByteArray(Charsets.UTF_16) + byteArrayOf(0, 0) + text.toByteArray(Charsets.UTF_16)
        assertEquals(text, Id3Lyrics.parse(tag(3, frame("USLT", uslt, 3))))
    }

    @Test
    fun noTagOrNoLyrics() {
        assertNull(Id3Lyrics.parse(ByteArray(64)))
        assertEquals(0, Id3Lyrics.tagLength(ByteArray(10)))
        assertNull(Id3Lyrics.parse(tag(4, frame("TIT2", byteArrayOf(3) + "x".toByteArray(), 4))))
    }

    @Test
    fun lrcTimestampsAreStripped() {
        val lrc = "[ar:Duman]\n[ti:Bu Akşam]\n[00:12.34]Birinci satır\n[00:15.00][01:20.00]Nakarat\n\n[00:20.5]Son"
        assertEquals("Birinci satır\nNakarat\n\nSon", LrcText.toPlain(lrc))
    }
}
