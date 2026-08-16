package com.monishguy.mifanscloud.ui.auth

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
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
                        // 尝试桌面 UA（部分站点对 WebView UA 渲染白屏——真机实验项）
                        settings.userAgentString = com.monishguy.mifanscloud.data.auth.XiaomiAuthService.UA
                        CookieManager.getInstance().setAcceptCookie(true)
                        webViewClient = object : WebViewClient() {                            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                Log.d(TAG, "onPageStarted: $url")
                                progress = 0
                                error = null
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                progress = 100
                                val raw = CookieManager.getInstance().getCookie(WebLoginFlow.HOME_URL)
                                Log.d(
                                    TAG,
                                    "onPageFinished: $url | title=${view.title} | " +
                                        "contentHeight=${view.contentHeight} | cookieKeys=${cookieKeys(raw)}",
                                )
                                inspectAndDecide()
                            }

                            override fun onReceivedError(
                                view: WebView,
                                request: WebResourceRequest,
                                err: WebResourceError,
                            ) {
                                Log.e(
                                    TAG,
                                    "onReceivedError: ${err.errorCode} ${err.description} " +
                                        "url=${request.url} mainFrame=${request.isForMainFrame}",
                                )
                                if (request.isForMainFrame) {
                                    error = err.description.toString()
                                }
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) {
                                progress = newProgress
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
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            OutlinedButton(onClick = { webView?.reload() }) { Text("刷新页面") }
            Button(onClick = { inspectAndDecide() }) { Text("我已完成登录，继续") }
        }
    }
}

private const val TAG = "MifansWebLogin"

/** 只记录 Cookie 的键名（绝不记录值，避免泄露 passToken/serviceToken）。 */
private fun cookieKeys(raw: String?): String =
    raw?.split(';')
        ?.mapNotNull { part -> part.substringBefore('=').trim().takeIf { it.isNotEmpty() } }
        ?.distinct()
        ?.joinToString(",")
        ?: "null"
