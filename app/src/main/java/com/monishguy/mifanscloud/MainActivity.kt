package com.monishguy.mifanscloud

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.monishguy.mifanscloud.ui.auth.AuthUiState
import com.monishguy.mifanscloud.ui.auth.AuthViewModel
import com.monishguy.mifanscloud.ui.auth.LoginScreen
import com.monishguy.mifanscloud.ui.home.HomeScreen
import com.monishguy.mifanscloud.ui.theme.米饭云服务Theme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as MifansCloudApp).container
        enableEdgeToEdge()
        setContent {
            米饭云服务Theme {
                val viewModel: AuthViewModel = viewModel(factory = AuthViewModel.Factory(container))
                val state by viewModel.state.collectAsState()
                when (val s = state) {
                    AuthUiState.Loading -> LoadingScreen()
                    is AuthUiState.NotConfigured -> LoginScreen(viewModel, initialError = null)
                    is AuthUiState.Ready -> HomeScreen(viewModel, s)
                }
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
