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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.lifecycle.viewmodel.compose.viewModel
import com.monishguy.mifanscloud.data.gallery.RemoteAlbum
import com.monishguy.mifanscloud.ui.auth.AuthUiState
import com.monishguy.mifanscloud.ui.auth.AuthViewModel
import com.monishguy.mifanscloud.ui.auth.LoginScreen
import com.monishguy.mifanscloud.ui.gallery.AlbumAssetsScreen
import com.monishguy.mifanscloud.ui.gallery.AlbumsScreen
import com.monishguy.mifanscloud.ui.gallery.GalleryViewModel
import com.monishguy.mifanscloud.ui.home.HomeScreen
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

/** 顶层页面栈：主页 / 相册列表 / 相册内资产。 */
private sealed interface Screen {
    data object Home : Screen
    data object Albums : Screen
    data class AlbumAssets(val album: RemoteAlbum) : Screen
}

@Composable
private fun MainScaffold(
    container: AppContainer,
    authViewModel: AuthViewModel,
    ready: AuthUiState.Ready,
) {
    val galleryViewModel: GalleryViewModel = viewModel(factory = GalleryViewModel.Factory(container))
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }

    val assets = screen as? Screen.AlbumAssets
    if (assets != null) {
        BackHandler { screen = Screen.Albums }
        AlbumAssetsScreen(
            viewModel = galleryViewModel,
            album = assets.album,
            onBack = { screen = Screen.Albums },
        )
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = screen == Screen.Home,
                    onClick = { screen = Screen.Home },
                    icon = { Icon(Icons.Filled.Home, contentDescription = "主页") },
                    label = { Text("主页") },
                )
                NavigationBarItem(
                    selected = screen == Screen.Albums,
                    onClick = { screen = Screen.Albums },
                    icon = { Icon(Icons.Filled.List, contentDescription = "相册") },
                    label = { Text("相册") },
                )
            }
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            when (screen) {
                Screen.Home -> HomeScreen(authViewModel, ready)
                Screen.Albums -> AlbumsScreen(
                    viewModel = galleryViewModel,
                    onOpenAlbum = { album -> screen = Screen.AlbumAssets(album) },
                )
                is Screen.AlbumAssets -> Unit // 上面已单独处理
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
