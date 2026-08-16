package com.monishguy.mifanscloud.ui.auth

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * WebView 内嵌登录：加载 i.mi.com，用户完成登录（含设备验证），
 * 自动检测登录态并按 [WebLoginFlow] 决策跳转相册页 / 提取 Cookie，
 * 提取后交给 [AuthViewModel.saveFromCookie] 走已验证的换取链。
 *
 * 安全：界面销毁（含提取成功后跳转）即清除 WebView 全部 Cookie，
 * 凭证只经内存传递，由 CredentialStore 加密持久化。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewLoginScreen(
    viewModel: AuthViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var progress by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var galleryVisited by remember { mutableStateOf(false) }
    var extracted by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    BackHandler {
        if (webView?.canGoBack() == true) webView?.goBack() else onClose()
    }

    fun inspectAndDecide() {
        if (extracted) return
        val raw = CookieManager.getInstance().getCookie(WebLoginFlow.HOME_URL)
        when (val decision = WebLoginFlow.decide(raw, galleryVisited)) {
            WebLoginFlow.Decision.KeepWaiting -> Unit
            WebLoginFlow.Decision.NavigateToGallery -> {
                galleryVisited = true
                webView?.loadUrl(WebLoginFlow.GALLERY_URL)
            }
            is WebLoginFlow.Decision.Extracted -> {
                extracted = true
                viewModel.saveFromCookie(decision.rawCookie)
            }
        }
    }

    // 提取成功后自动跳转主页，此处随之销毁；无论如何都清除 WebView 会话。
    DisposableEffect(Unit) {
        onDispose {
            CookieManager.getInstance().removeAllCookies(null)
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("在浏览器中登录小米云", style = MaterialTheme.typography.titleLarge)
                OutlinedButton(onClick = onClose) { Text("返回手动输入") }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "登录后如出现「手机验证」请勾选信任此设备；系统会自动跳转相册页完成验证并提取凭证。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            if (progress < 100) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        webView = this
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        CookieManager.getInstance().setAcceptCookie(true)
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String?) {
                                progress = 100
                                inspectAndDecide()
                            }

                            @Deprecated("Deprecated in API level 24")
                            override fun onReceivedError(
                                view: WebView,
                                errorCode: Int,
                                description: String,
                                failingUrl: String?,
                            ) {
                                error = description
                            }
                        }
                        loadUrl(WebLoginFlow.HOME_URL)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (progress < 100) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            error?.let { message ->
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("页面加载失败：$message", color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { webView?.reload(); error = null }) { Text("重试") }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            Button(onClick = { inspectAndDecide() }) { Text("我已完成登录，继续") }
        }
    }
}
