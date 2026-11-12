package com.example.musicapp.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.example.musicapp.data.db.InitiatedBy
import com.example.musicapp.data.db.ListenMode
import com.example.musicapp.data.db.QueueTag
import com.example.musicapp.data.db.SongListItem
import com.example.musicapp.library.LibraryScanner

/** Extras every queued item carries so the recorder knows the context of the play. */
object ItemExtras {
    const val CONTEXT = "ctx"
    const val CONTEXT_SIZE = "ctx_size"
    const val MODE = "mode"
    const val TAG = "tag"
    const val MANUAL = "manual"
    const val DURATION = "duration"
}

fun SongListItem.toMediaItem(context: PlayContext, tag: QueueTag?, manual: Boolean): MediaItem {
    val extras = Bundle().apply {
        putString(ItemExtras.CONTEXT, context.key)
        putInt(ItemExtras.CONTEXT_SIZE, context.size)
        putString(ItemExtras.MODE, context.mode.name)
        tag?.let { putString(ItemExtras.TAG, it.name) }
        putBoolean(ItemExtras.MANUAL, manual)
        putLong(ItemExtras.DURATION, durationMs)
    }
    val uri = mediaId?.let { LibraryScanner.contentUri(it) }
    return MediaItem.Builder()
        .setMediaId(id)
        .setUri(uri)
        // The URI is dropped when an item travels from controller to session; this copy survives.
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artistNames ?: "Bilinmeyen sanatçı")
                .setAlbumTitle(albumTitle)
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setExtras(extras)
                .build(),
        )
        .build()
}

internal val MediaItem.extras: Bundle? get() = mediaMetadata.extras

internal fun MediaItem.playContext(): PlayContext = PlayContext(
    key = extras?.getString(ItemExtras.CONTEXT) ?: "library",
    size = extras?.getInt(ItemExtras.CONTEXT_SIZE) ?: 1,
    mode = extras?.getString(ItemExtras.MODE)?.let { runCatching { ListenMode.valueOf(it) }.getOrNull() } ?: ListenMode.NONE,
)

internal fun MediaItem.queueTag(): QueueTag? =
    extras?.getString(ItemExtras.TAG)?.let { runCatching { QueueTag.valueOf(it) }.getOrNull() }

internal fun MediaItem.isManualStart(): Boolean = extras?.getBoolean(ItemExtras.MANUAL) == true

internal fun MediaItem.knownDurationMs(): Long = extras?.getLong(ItemExtras.DURATION) ?: 0L

internal fun initiated(manual: Boolean) = if (manual) InitiatedBy.MANUAL else InitiatedBy.AUTO
