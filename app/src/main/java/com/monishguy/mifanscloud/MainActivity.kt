package com.monishguy.mifanscloud

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.monishguy.mifanscloud.data.gallery.RemoteAlbum
import com.monishguy.mifanscloud.data.gallery.RemoteAsset
import com.monishguy.mifanscloud.data.local.SaveDirStore
import com.monishguy.mifanscloud.ui.auth.AuthUiState
import com.monishguy.mifanscloud.ui.auth.AuthViewModel
import com.monishguy.mifanscloud.ui.auth.LoginScreen
import com.monishguy.mifanscloud.ui.contact.ContactsScreen
import com.monishguy.mifanscloud.ui.contact.ContactsViewModel
import com.monishguy.mifanscloud.ui.gallery.AlbumAssetsScreen
import com.monishguy.mifanscloud.ui.gallery.GallerySectionScreen
import com.monishguy.mifanscloud.ui.gallery.GalleryViewModel
import com.monishguy.mifanscloud.ui.gallery.PhotoViewerScreen
import com.monishguy.mifanscloud.ui.note.NotesScreen
import com.monishguy.mifanscloud.ui.note.NotesViewModel
import com.monishguy.mifanscloud.ui.recording.RecordingsScreen
import com.monishguy.mifanscloud.ui.recording.RecordingsViewModel
import com.monishguy.mifanscloud.ui.settings.SettingsScreen
import com.monishguy.mifanscloud.ui.welcome.Section
import com.monishguy.mifanscloud.ui.welcome.WelcomeScreen
import com.monishguy.mifanscloud.ui.theme.米饭云服务Theme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as MifansCloudApp).container
        enableEdgeToEdge()
        setContent {
            米饭云服务Theme {
                val authViewModel: AuthViewModel = viewModel(factory = AuthViewModel.Factory(container))
                val state by authViewModel.state.collectAsState()
                when (val s = state) {
                    AuthUiState.Loading -> LoadingScreen()
                    is AuthUiState.NotConfigured -> LoginScreen(authViewModel, initialError = null)
                    is AuthUiState.Ready -> MainScaffold(container, authViewModel, s)
                }
            }
        }
    }
}

/** 顶层页面栈：欢迎 / 设置 / 各板块（全屏 + 返回）。 */
private sealed interface Screen {
    data object Welcome : Screen
    data object Settings : Screen
    data object AlbumSection : Screen
    data class AlbumAssets(val album: RemoteAlbum) : Screen

    /** 全屏原图查看器：返回回到 [backTo] 来源页。 */
    data class PhotoView(val asset: RemoteAsset, val backTo: Screen) : Screen

    data object RecordingSection : Screen
    data object ContactSection : Screen
    data object NoteSection : Screen
}

@Composable
private fun MainScaffold(
    container: AppContainer,
    authViewModel: AuthViewModel,
    ready: AuthUiState.Ready,
) {
    val galleryViewModel: GalleryViewModel = viewModel(factory = GalleryViewModel.Factory(container))
    val recordingsViewModel: RecordingsViewModel = viewModel(factory = RecordingsViewModel.Factory(container))
    val contactsViewModel: ContactsViewModel = viewModel(factory = ContactsViewModel.Factory(container))
    val notesViewModel: NotesViewModel = viewModel(factory = NotesViewModel.Factory(container))
    var screen by remember { mutableStateOf<Screen>(Screen.Welcome) }

    // 全屏板块页（无底栏）
    when (val s = screen) {
        is Screen.PhotoView -> {
            BackHandler { screen = s.backTo }
            PhotoViewerScreen(
                viewModel = galleryViewModel,
                asset = s.asset,
                saveDirStore = container.saveDirStore,
                onBack = { screen = s.backTo },
            )
            return
        }
        is Screen.AlbumAssets -> {
            BackHandler { screen = Screen.AlbumSection }
            AlbumAssetsScreen(
                viewModel = galleryViewModel,
                album = s.album,
                saveDirStore = container.saveDirStore,
                onBack = { screen = Screen.AlbumSection },
                onOpenPhoto = { asset -> screen = Screen.PhotoView(asset, backTo = s) },
            )
            return
        }
        Screen.AlbumSection -> {
            BackHandler { screen = Screen.Welcome }
            GallerySectionScreen(
                viewModel = galleryViewModel,
                saveDirStore = container.saveDirStore,
                onBack = { screen = Screen.Welcome },
                onOpenAlbum = { album -> screen = Screen.AlbumAssets(album) },
                onOpenPhoto = { asset -> screen = Screen.PhotoView(asset, backTo = Screen.AlbumSection) },
            )
            return
        }
        Screen.RecordingSection -> {
            BackHandler { screen = Screen.Welcome }
            RecordingsScreen(
                viewModel = recordingsViewModel,
                saveDirStore = container.saveDirStore,
                onBack = { screen = Screen.Welcome },
            )
            return
        }
        Screen.ContactSection -> {
            BackHandler { screen = Screen.Welcome }
            ContactsScreen(
                viewModel = contactsViewModel,
                saveDirStore = container.saveDirStore,
                onBack = { screen = Screen.Welcome },
            )
            return
        }
        Screen.NoteSection -> {
            BackHandler { screen = Screen.Welcome }
            NotesScreen(
                viewModel = notesViewModel,
                saveDirStore = container.saveDirStore,
                onBack = { screen = Screen.Welcome },
            )
            return
        }
        else -> Unit
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = screen == Screen.Welcome,
                    onClick = { screen = Screen.Welcome },
                    icon = { Icon(Icons.Filled.Home, contentDescription = "欢迎") },
                    label = { Text("欢迎") },
                )
                NavigationBarItem(
                    selected = screen == Screen.Settings,
                    onClick = { screen = Screen.Settings },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = "设置") },
                    label = { Text("设置") },
                )
            }
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            when (screen) {
                Screen.Welcome -> WelcomeScreen(
                    onOpenSection = { section ->
                        screen = when (section) {
                            Section.ALBUM -> Screen.AlbumSection
                            Section.RECORDING -> Screen.RecordingSection
                            Section.CONTACT -> Screen.ContactSection
                            Section.NOTE -> Screen.NoteSection
                        }
                    },
                )
                Screen.Settings -> SettingsScreen(
                    viewModel = authViewModel,
                    saveDirStore = container.saveDirStore,
                    state = ready,
                )
                else -> Unit
            }
        }
    }
}

@Composable
private fun LoadingScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}
