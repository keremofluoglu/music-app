package com.example.musicapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.compose.runtime.DisposableEffect
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.musicapp.ui.AddSongsScreen
import com.example.musicapp.ui.AppIcons
import com.example.musicapp.ui.GroupSongsScreen
import com.example.musicapp.ui.HomeScreen
import com.example.musicapp.ui.LibraryScreen
import com.example.musicapp.ui.MainViewModel
import com.example.musicapp.ui.MiniPlayer
import com.example.musicapp.ui.NowPlayingScreen
import com.example.musicapp.ui.PermissionScreen
import com.example.musicapp.ui.PlaylistScreen
import com.example.musicapp.ui.QueueScreen
import com.example.musicapp.ui.SearchScreen
import com.example.musicapp.ui.SettingsScreen
import com.example.musicapp.ui.SleepTimerDialog
import com.example.musicapp.ui.SongListActions
import com.example.musicapp.ui.theme.Appearance
import com.example.musicapp.ui.theme.MusicAppTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by vm.settings.collectAsState()
            val s = settings
            val appearance = s?.appearance ?: if (isSystemInDarkTheme()) Appearance.DARK else Appearance.LIGHT
            // Status bar icons follow the app's own appearance, not only the system's.
            DisposableEffect(appearance) {
                val style = if (appearance == Appearance.DARK) SystemBarStyle.dark(Color.TRANSPARENT)
                else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            MusicAppTheme(themeColor = s?.themeColor ?: com.example.musicapp.ui.theme.ThemeColor.LAVENDER, appearance = appearance) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    PermissionGate(vm) { AppRoot(vm) }
                }
            }
        }
    }
}

private val audioPermission =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

@Composable
private fun PermissionGate(vm: MainViewModel, content: @Composable () -> Unit) {
    val context = LocalContext.current
    fun granted() = ContextCompat.checkSelfPermission(context, audioPermission) == PackageManager.PERMISSION_GRANTED
    var hasPermission by remember { mutableStateOf(granted()) }
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        hasPermission = result[audioPermission] == true || granted()
        denied = !hasPermission
    }
    val permissions = buildList {
        add(audioPermission)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    LaunchedEffect(Unit) { if (!hasPermission) launcher.launch(permissions) }
    LaunchedEffect(hasPermission) { if (hasPermission) vm.scan() }

    if (hasPermission) content() else PermissionScreen(
        onRequest = { launcher.launch(permissions) },
        onOpenSettings = {
            context.startActivity(
                Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
            )
        },
        denied = denied,
    )
}

private object Routes {
    const val HOME = "home"
    const val SEARCH = "search"
    const val LIBRARY = "library"
    const val SETTINGS = "settings"
    const val NOW = "now"
    const val ARTIST = "artist/{id}"
    const val ALBUM = "album/{id}"
    const val PLAYLIST = "playlist/{id}"
    const val ADD_SONGS = "playlist/{id}/add"
    const val QUEUE = "queue"
}

@Composable
private fun AppRoot(vm: MainViewModel) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val playerState by vm.player.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val message by vm.message.collectAsState()
    var sleepDialog by remember { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let(vm::exportCsv)
    }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.messageShown()
        }
    }

    val actions = remember(nav) {
        SongListActions(
            vm,
            goArtist = { nav.navigate("artist/$it") },
            goAlbum = { nav.navigate("album/$it") },
            goPlaylist = { nav.navigate("playlist/$it") },
            addSongs = { nav.navigate("playlist/$it/add") },
        )
    }
    val showChrome = route != Routes.NOW && route != Routes.QUEUE

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (showChrome) Column {
                MiniPlayer(playerState, vm.player) { nav.navigate(Routes.NOW) }
                BottomNav(nav, route)
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.HOME) {
                HomeScreen(vm, actions, onSettings = { nav.navigate(Routes.SETTINGS) })
            }
            composable(Routes.SEARCH) { SearchScreen(vm, actions) }
            composable(Routes.LIBRARY) { LibraryScreen(vm, actions) }
            composable(Routes.SETTINGS) {
                SettingsScreen(vm, onBack = { nav.popBackStack() }, onExport = { exportLauncher.launch("dinleme-gecmisi.csv") })
            }
            composable(
                Routes.NOW,
                // Slides up from the mini player and back down, matching the swipe gestures.
                enterTransition = { slideInVertically(initialOffsetY = { it }) },
                popExitTransition = { slideOutVertically(targetOffsetY = { it }) },
            ) {
                NowPlayingScreen(
                    vm,
                    onClose = { nav.popBackStack() },
                    onArtist = null,
                    onSleep = { sleepDialog = true },
                    onQueue = { nav.navigate(Routes.QUEUE) },
                )
            }
            composable(Routes.QUEUE) { QueueScreen(vm) { nav.popBackStack() } }
            composable(Routes.PLAYLIST, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                PlaylistScreen(entry.arguments?.getLong("id") ?: 0, vm, actions) { nav.popBackStack() }
            }
            composable(Routes.ADD_SONGS, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                AddSongsScreen(entry.arguments?.getLong("id") ?: 0, vm) { nav.popBackStack() }
            }
            composable(Routes.ARTIST, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                val id = entry.arguments?.getLong("id") ?: 0
                val artist = vm.artists.collectAsState().value.firstOrNull { it.id == id }
                GroupSongsScreen(artist?.name ?: "Sanatçı", "Sanatçı", remember(id) { vm.songsOfArtist(id) }, "artist:$id", actions) { nav.popBackStack() }
            }
            composable(Routes.ALBUM, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                val id = entry.arguments?.getLong("id") ?: 0
                val album = vm.albums.collectAsState().value.firstOrNull { it.id == id }
                GroupSongsScreen(album?.title ?: "Albüm", album?.artistName ?: "Albüm", remember(id) { vm.songsOfAlbum(id) }, "album:$id", actions) { nav.popBackStack() }
            }
        }
    }
    if (sleepDialog) SleepTimerDialog(vm) { sleepDialog = false }
}

@Composable
private fun BottomNav(nav: NavHostController, route: String?) {
    NavigationBar {
        listOf(
            Triple(Routes.HOME, "Ana sayfa", AppIcons.Home),
            Triple(Routes.SEARCH, "Ara", AppIcons.Search),
            Triple(Routes.LIBRARY, "Kütüphane", AppIcons.Library),
        ).forEach { (r, label, icon) ->
            NavigationBarItem(
                selected = route == r,
                onClick = {
                    if (r == Routes.HOME) {
                        // "Ana sayfa" always means the home screen itself, whatever is open on top of it.
                        nav.popBackStack(Routes.HOME, inclusive = false)
                    } else {
                        nav.navigate(r) {
                            popUpTo(Routes.HOME) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                icon = { Icon(icon, null) },
                label = { Text(label) },
            )
        }
    }
}
