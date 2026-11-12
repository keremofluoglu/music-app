package com.example.musicapp.library

import java.text.Normalizer
import java.util.Locale

/** Accent- and case-insensitive search key: "Şarkı Sözü" and "sarki sozu" fold to the same text. */
object TextFold {
    private val TR = Locale.forLanguageTag("tr")
    private val MARKS = Regex("""\p{Mn}+""")

    fun fold(text: String): String {
        val lower = text.lowercase(TR)
            .replace('ı', 'i')
            .replace('ş', 's')
            .replace('ğ', 'g')
            .replace('ç', 'c')
            .replace('ö', 'o')
            .replace('ü', 'u')
        return MARKS.replace(Normalizer.normalize(lower, Normalizer.Form.NFD), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}
