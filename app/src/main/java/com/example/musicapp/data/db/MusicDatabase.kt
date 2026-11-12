package com.example.musicapp.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        SongEntity::class,
        ArtistEntity::class,
        SongArtistEntity::class,
        AlbumEntity::class,
        GenreEntity::class,
        SongGenreEntity::class,
        SessionEntity::class,
        PlayEventEntity::class,
        ModeChangeEntity::class,
        SongStatsEntity::class,
        PlaylistEntity::class,
        PlaylistItemEntity::class,
        LyricsEntity::class,
        DownloadEntity::class,
        ParseRuleEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class MusicDatabase : RoomDatabase() {
    // No destructive fallback: the listening history must survive every update. Once a build is in
    // real use, every schema change needs a Migration (docs/veritabani.md, point 15).
    abstract fun songDao(): SongDao
    abstract fun artistDao(): ArtistDao
    abstract fun albumDao(): AlbumDao
    abstract fun genreDao(): GenreDao
    abstract fun playEventDao(): PlayEventDao
    abstract fun songStatsDao(): SongStatsDao
    abstract fun sessionDao(): SessionDao
    abstract fun modeChangeDao(): ModeChangeDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun lyricsDao(): LyricsDao
    abstract fun downloadDao(): DownloadDao

    companion object {
        @Volatile private var instance: MusicDatabase? = null

        fun get(context: Context): MusicDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MusicDatabase::class.java,
                    "music.db",
                ).build().also { instance = it }
            }
    }
}
