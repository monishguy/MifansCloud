package com.monishguy.mifanscloud.ui.auth

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
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
import com.monishguy.mifanscloud.data.auth.XiaomiAuthService

/**
 * WebView 内嵌登录：
 * - 默认直接加载**小米云登录链**（主页右上角「登录」的真实跳转目标），
 *   登录成功后小米自动 302 回 i.mi.com 并种下 serviceToken；
 * - 开启第三方 Cookie（登录链跨域 account.xiaomi.com → i.mi.com，
 *   Android 12+ WebView 默认关闭会导致凭证丢失）+ 桌面宽视口；
 * - 自动检测登录态并按 [WebLoginFlow] 决策提取 Cookie；
 * - 兜底：「打开云服务主页」「复制当前 Cookie」（可去手动粘贴页）「系统浏览器打开」。
 *
 * 安全：界面销毁即清除 WebView 全部 Cookie；凭证只经内存传递，加密持久化。
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
    var hint by remember { mutableStateOf<String?>(null) }
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
                Text("浏览器登录小米云", style = MaterialTheme.typography.titleLarge)
                OutlinedButton(onClick = onClose) { Text("返回手动输入") }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "已直接打开小米登录链。建议用「账号密码 / 手机验证码」登录（微信扫码可能不支持）；" +
                    "登录成功后会自动跳回小米云并提取凭证。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            hint?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            }
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
                        // 桌面 UA + 宽视口（防白屏/移动版布局错乱）
                        settings.userAgentString = XiaomiAuthService.UA
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        val cookieManager = CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)
                        // Android 12+ 默认拒绝第三方 Cookie：登录链跨域必须开启
                        cookieManager.setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
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
                                if (url == WebLoginFlow.HOME_URL && !hasCredentialCookie(raw)) {
                                    hint = "已回到云服务主页，但未检测到登录凭证；若已登录可点「复制当前 Cookie」手动粘贴。"
                                }
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
                        // 直接进登录链：登录成功自动跳回 i.mi.com
                        loadUrl(WebLoginFlow.LOGIN_URL)
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

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = { webView?.loadUrl(WebLoginFlow.HOME_URL) }, modifier = Modifier.weight(1f)) {
                    Text("打开云服务主页")
                }
                OutlinedButton(onClick = { webView?.reload() }, modifier = Modifier.weight(1f)) {
                    Text("刷新")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        val raw = CookieManager.getInstance().getCookie(WebLoginFlow.HOME_URL)
                        if (raw.isNullOrBlank()) {
                            hint = "当前没有 i.mi.com 的 Cookie（尚未登录成功）"
                        } else {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("i.mi.com Cookie", raw))
                            hint = "已复制当前 Cookie，可返回「手动输入」页粘贴"
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("复制 Cookie") }
                Button(onClick = { inspectAndDecide() }, modifier = Modifier.weight(1f)) {
                    Text("我已完成登录，继续")
                }
            }
            OutlinedButton(
                onClick = {
                    // 系统浏览器登录：Android 沙箱限制拿不到浏览器 Cookie，
                    // 仅作辅助——登录完成后需在浏览器里复制 Cookie 回 App 手动粘贴。
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(WebLoginFlow.LOGIN_URL)))
                    }
                    hint = "已在系统浏览器打开登录页（注意：App 无法读取浏览器 Cookie，登录后请复制 Cookie 回 App 粘贴）"
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("用系统浏览器打开（仅辅助，Cookie 需手动复制）") }
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

/** 是否已含凭证 Cookie（passToken / serviceToken）。 */
private fun hasCredentialCookie(raw: String?): Boolean {
    val keys = cookieKeys(raw)
    return "passToken" in keys || "serviceToken" in keys
}
