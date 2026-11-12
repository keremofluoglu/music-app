package com.example.musicapp.lyrics

import java.nio.charset.Charset

/**
 * Reads unsynchronised lyrics (the ID3v2 "USLT" frame) from the start of an MP3 file.
 * Read-only; the file is never changed.
 */
object Id3Lyrics {
    /** Bytes needed to know the tag size. */
    const val HEADER = 10

    /** Total tag length (header + body) or 0 when the data does not start with an ID3v2 tag. */
    fun tagLength(header: ByteArray): Int {
        if (header.size < HEADER || header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) return 0
        return HEADER + synchsafe(header, 6)
    }

    /** Lyrics text from a complete tag (starting with "ID3"), or null. */
    fun parse(tag: ByteArray): String? {
        if (tagLength(tag) == 0) return null
        val major = tag[3].toInt()
        if (major !in 3..4) return null
        val flags = tag[5].toInt()
        val end = minOf(tag.size, tagLength(tag))
        var pos = HEADER
        if (flags and 0x40 != 0) { // extended header
            val extSize = if (major == 4) synchsafe(tag, pos) else int32(tag, pos) + 4
            pos += extSize
        }
        while (pos + 10 <= end) {
            val id = String(tag, pos, 4, Charsets.ISO_8859_1)
            if (id[0] == '\u0000') break
            val size = if (major == 4) synchsafe(tag, pos + 4) else int32(tag, pos + 4)
            val body = pos + 10
            if (size <= 0 || body + size > end) break
            if (id == "USLT") decodeUslt(tag, body, size)?.let { return it }
            pos = body + size
        }
        return null
    }

    private fun decodeUslt(b: ByteArray, start: Int, size: Int): String? {
        if (size < 5) return null
        val encoding = b[start].toInt()
        var p = start + 4 // encoding + 3-byte language
        val end = start + size
        val wide = encoding == 1 || encoding == 2
        // Skip the content descriptor (null-terminated, 2-byte null for UTF-16).
        if (wide) {
            while (p + 1 < end && !(b[p].toInt() == 0 && b[p + 1].toInt() == 0)) p += 2
            p += 2
        } else {
            while (p < end && b[p].toInt() != 0) p++
            p += 1
        }
        if (p >= end) return null
        val charset: Charset = when (encoding) {
            0 -> Charsets.ISO_8859_1
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        return String(b, p, end - p, charset).trimEnd('\u0000').trim().ifEmpty { null }
    }

    private fun synchsafe(b: ByteArray, i: Int): Int =
        ((b[i].toInt() and 0x7F) shl 21) or ((b[i + 1].toInt() and 0x7F) shl 14) or
            ((b[i + 2].toInt() and 0x7F) shl 7) or (b[i + 3].toInt() and 0x7F)

    private fun int32(b: ByteArray, i: Int): Int =
        ((b[i].toInt() and 0xFF) shl 24) or ((b[i + 1].toInt() and 0xFF) shl 16) or
            ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
}

/** Turns LRC text ("[01:23.45] line") into plain static lyrics. */
object LrcText {
    private val TIME = Regex("""^(\s*\[\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?])+\s*""")
    private val META = Regex("""^\s*\[[a-zA-Z]+:.*]\s*$""")

    fun toPlain(lrc: String): String =
        lrc.lines()
            .filterNot { META.matches(it) }
            .map { TIME.replace(it, "") }
            .joinToString("\n")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
}
