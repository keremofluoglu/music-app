package com.example.musicapp.data.export

import com.example.musicapp.data.db.MusicDatabase
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Listening history as CSV for tuning the algorithm (docs/veritabani.md, section 10.1). */
class CsvExporter(private val db: MusicDatabase) {

    suspend fun write(out: OutputStream): Int {
        val events = db.playEventDao().allOrdered()
        val songs = db.songDao().allListItems().associateBy { it.id }
        val writer = out.bufferedWriter(Charsets.UTF_8)
        writer.write("﻿") // Excel opens UTF-8 correctly with a BOM
        writer.write(
            listOf(
                "zaman", "sarki_id", "baslik", "sanatci", "album", "dosya", "sure_sn", "dinlenen_sn", "oran",
                "bitis", "baslatan", "mod", "baglam", "baglam_boyutu", "tekrar", "kuyruk_etiketi",
            ).joinToString(",") + "\n",
        )
        for (e in events) {
            val s = songs[e.songId]
            val time = Instant.ofEpochMilli(e.startedAt).atOffset(ZoneOffset.ofTotalSeconds(e.tzOffsetMin * 60))
            val ratio = if (e.durationMs > 0) e.listenedMs.toDouble() / e.durationMs else 0.0
            val row = listOf(
                DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(time),
                e.songId,
                s?.title.orEmpty(),
                s?.artistNames.orEmpty(),
                s?.albumTitle.orEmpty(),
                s?.filePath.orEmpty(),
                "%.1f".format(java.util.Locale.ROOT, e.durationMs / 1000.0),
                "%.1f".format(java.util.Locale.ROOT, e.listenedMs / 1000.0),
                "%.3f".format(java.util.Locale.ROOT, ratio),
                e.endReason.name,
                e.initiatedBy.name,
                e.modeAtPlay.name,
                e.queueContext,
                e.contextSize.toString(),
                e.repeatMode.name,
                e.queueTag?.name.orEmpty(),
            )
            writer.write(row.joinToString(",") { quote(it) } + "\n")
        }
        writer.flush()
        return events.size
    }

    private fun quote(v: String): String =
        if (v.any { it == ',' || it == '"' || it == '\n' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
}
