package com.example.musicapp.library

import java.security.MessageDigest

/**
 * Content hash of a local file: size + first 64 KB + last 64 KB (docs/veritabani.md, section 2).
 * The song ID is derived once from it and then frozen.
 */
object ContentHasher {
    const val CHUNK = 64 * 1024

    fun hash(size: Long, head: ByteArray, tail: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(size.toString().toByteArray())
        md.update(head)
        md.update(tail)
        return md.digest().take(8).joinToString("") { "%02x".format(it) }
    }

    fun localId(hash: String): String = "local:$hash"
}
