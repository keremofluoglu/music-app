package com.example.musicapp.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.musicapp.data.db.ListenMode
import com.example.musicapp.data.db.RepeatMode
import com.example.musicapp.ui.theme.Appearance
import com.example.musicapp.ui.theme.ThemeColor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/** Everything that is a preference rather than history (docs/veritabani.md, section 9). */
data class Settings(
    val themeColor: ThemeColor = ThemeColor.LAVENDER,
    /** Null = follow the system. */
    val appearance: Appearance? = null,
    val homeMode: ListenMode = ListenMode.NEW,
    val shuffle: Boolean = true,
    val repeat: RepeatMode = RepeatMode.ALL,
    val minDurationSec: Int = 30,
    val lyricsOnline: Boolean = true,
    /** Both scores at the right edge of every song row (for tuning; may be hidden for Play users). */
    val showScores: Boolean = true,
    /** Music folders to include (relative paths, e.g. "Music/"); subfolders count too. Null = every folder. */
    val includedFolders: Set<String>? = null,
)

class AppSettings(private val context: Context) {
    private object Keys {
        val theme = stringPreferencesKey("theme_color")
        val appearance = stringPreferencesKey("appearance")
        val homeMode = stringPreferencesKey("home_mode")
        val shuffle = booleanPreferencesKey("shuffle")
        val repeat = stringPreferencesKey("repeat")
        val minDuration = intPreferencesKey("min_duration_sec")
        val lyricsOnline = booleanPreferencesKey("lyrics_online")
        val openListen = stringPreferencesKey("open_listen")
        val showScores = booleanPreferencesKey("show_scores")
        val folders = stringSetPreferencesKey("included_folders")
    }

    val flow: Flow<Settings> = context.dataStore.data.map { it.toSettings() }

    suspend fun current(): Settings = flow.first()

    private fun Preferences.toSettings() = Settings(
        themeColor = this[Keys.theme]?.let { runCatching { ThemeColor.valueOf(it) }.getOrNull() } ?: ThemeColor.LAVENDER,
        appearance = this[Keys.appearance]?.let { runCatching { Appearance.valueOf(it) }.getOrNull() },
        homeMode = this[Keys.homeMode]?.let { runCatching { ListenMode.valueOf(it) }.getOrNull() } ?: ListenMode.NEW,
        shuffle = this[Keys.shuffle] ?: true,
        repeat = this[Keys.repeat]?.let { runCatching { RepeatMode.valueOf(it) }.getOrNull() } ?: RepeatMode.ALL,
        minDurationSec = this[Keys.minDuration] ?: 30,
        lyricsOnline = this[Keys.lyricsOnline] ?: true,
        showScores = this[Keys.showScores] ?: true,
        includedFolders = this[Keys.folders],
    )

    suspend fun setThemeColor(c: ThemeColor) = context.dataStore.edit { it[Keys.theme] = c.name }
    suspend fun setAppearance(a: Appearance?) = context.dataStore.edit {
        if (a == null) it.remove(Keys.appearance) else it[Keys.appearance] = a.name
    }
    suspend fun setHomeMode(m: ListenMode) = context.dataStore.edit { it[Keys.homeMode] = m.name }
    suspend fun setShuffle(on: Boolean) = context.dataStore.edit { it[Keys.shuffle] = on }
    suspend fun setRepeat(r: RepeatMode) = context.dataStore.edit { it[Keys.repeat] = r.name }
    suspend fun setLyricsOnline(on: Boolean) = context.dataStore.edit { it[Keys.lyricsOnline] = on }
    suspend fun setShowScores(on: Boolean) = context.dataStore.edit { it[Keys.showScores] = on }
    suspend fun setMinDurationSec(sec: Int) = context.dataStore.edit { it[Keys.minDuration] = sec }
    suspend fun setIncludedFolders(folders: Set<String>?) = context.dataStore.edit {
        if (folders == null) it.remove(Keys.folders) else it[Keys.folders] = folders
    }

    /** The play in progress, saved every few seconds so a killed process still records it (docs/algoritma-kodlama.md 7.3). */
    suspend fun saveOpenListen(json: String?) = context.dataStore.edit {
        if (json == null) it.remove(Keys.openListen) else it[Keys.openListen] = json
    }
    suspend fun openListen(): String? = context.dataStore.data.first()[Keys.openListen]
}
