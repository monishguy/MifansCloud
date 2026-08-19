package com.monishguy.mifanscloud.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.monishguy.mifanscloud.AppContainer
import com.monishguy.mifanscloud.data.auth.CookieParser
import com.monishguy.mifanscloud.data.auth.CredentialStore
import com.monishguy.mifanscloud.data.auth.SessionToken
import com.monishguy.mifanscloud.data.auth.XiaomiAuthService
import com.monishguy.mifanscloud.data.auth.XiaomiCredential
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 认证模块的 UI 状态。 */
sealed interface AuthUiState {
    data object Loading : AuthUiState

    /** 未配置凭证，或配置后验证失败（error 非空）。 */
    data class NotConfigured(val error: String?) : AuthUiState

    /** 凭证有效，serviceToken 会话可用。 */
    data class Ready(
        val credential: XiaomiCredential,
        val tokenObtainedAt: Long?,
    ) : AuthUiState
}

/**
 * 认证闭环 ViewModel：凭证输入 → 安全存储 → serviceToken 验证 → 状态流转。
 * 数据源 seam：注入 [CredentialStore] 与 [XiaomiAuthService]，测试可替换为
 * 内存 fake + MockWebServer。
 */
class AuthViewModel(
    private val store: CredentialStore,
    private val authService: XiaomiAuthService,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** 清除凭证时通知各板块缓存失效（AppContainer 装配）。 */
    private val onCacheInvalidate: () -> Unit = {},
) : ViewModel() {

    private val _state = MutableStateFlow<AuthUiState>(AuthUiState.Loading)
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    init {
        checkStoredCredentials()
    }

    /** 启动时校验已存储的凭证（保持存储不变，失败仅提示）。
     *  仅当尚无用户操作（仍为 Loading）时接管状态，避免覆盖用户的输入结果。 */
    fun checkStoredCredentials() {
        viewModelScope.launch {
            if (_state.value !is AuthUiState.Loading) return@launch
            val stored = store.load()
            if (stored == null) {
                _state.value = AuthUiState.NotConfigured(null)
            } else {
                _state.value = validate(stored)
            }
        }
    }

    /** 粘贴整段 Cookie（自动解析 userId + passToken/serviceToken）。 */
    fun saveFromCookie(rawCookie: String) {
        val parsed = CookieParser.parse(rawCookie)
        if (parsed == null) {
            _state.value = AuthUiState.NotConfigured(
                "无法解析出 userId 与（passToken 或 serviceToken）。" +
                    "若只有 userId，请先在浏览器访问 i.mi.com/gallery/h5#/ 完成设备验证后重新复制"
            )
            return
        }
        saveAndValidate(parsed)
    }

    /** 手动填写 userId + passToken。 */
    fun saveManually(userId: String, passToken: String) {
        if (userId.isBlank() || passToken.isBlank()) {
            _state.value = AuthUiState.NotConfigured("userId 与 passToken 不能为空")
            return
        }
        saveAndValidate(XiaomiCredential.PassToken(userId.trim(), passToken.trim()))
    }

    /** 手动刷新 serviceToken（10 分钟缓存周期之外触发；仅 passToken 凭证可刷新）。 */
    fun refreshNow() {
        val ready = _state.value as? AuthUiState.Ready ?: return
        val passToken = (ready.credential as? XiaomiCredential.PassToken) ?: return
        viewModelScope.launch {
            _state.value = AuthUiState.Loading
            val result = withContext(ioDispatcher) {
                runCatching { authService.exchange(passToken) }
            }
            _state.value = mapResult(passToken, result)
        }
    }

    /**
     * AutoRenewal 续期（直连会话）：用整段 Cookie 换新 serviceToken 并持久化，
     * 无需重新登录复制。仅 [XiaomiCredential.ServiceToken.rawCookie] 存在时可用。
     */
    fun renewNow() {
        val ready = _state.value as? AuthUiState.Ready ?: return
        val session = ready.credential as? XiaomiCredential.ServiceToken ?: return
        viewModelScope.launch {
            _state.value = AuthUiState.Loading
            val result = withContext(ioDispatcher) {
                runCatching {
                    val fresh = authService.renewServiceToken(session)
                    store.updateServiceToken(fresh.serviceToken)
                    fresh
                }
            }
            _state.value = result.fold(
                onSuccess = { AuthUiState.Ready(session.copy(serviceToken = it.serviceToken), it.obtainedAt) },
                onFailure = { AuthUiState.NotConfigured(it.message ?: "续期失败") },
            )
        }
    }

    /**
     * 静默自动续期（软件运行期间定期调用）：
     * 成功持久化新 serviceToken；失败**保持现状**（不踢回登录页），
     * 返回是否成功（供 UI 显示上次自动续期结果）。
     */
    fun autoRenewSilently(onResult: (Boolean) -> Unit = {}) {
        val session = (_state.value as? AuthUiState.Ready)?.credential as? XiaomiCredential.ServiceToken ?: run {
            onResult(false)
            return
        }
        viewModelScope.launch {
            val fresh = withContext(ioDispatcher) {
                runCatching {
                    val renewed = authService.renewServiceToken(session)
                    store.updateServiceToken(renewed.serviceToken)
                    renewed
                }.getOrNull()
            }
            if (fresh != null) {
                _state.value = AuthUiState.Ready(
                    session.copy(serviceToken = fresh.serviceToken),
                    fresh.obtainedAt,
                )
            }
            onResult(fresh != null)
        }
    }

    /** 清除本地凭证并回到未配置状态；同时通知各板块缓存失效。 */
    fun clearCredentials() {
        store.clear()
        onCacheInvalidate()
        _state.value = AuthUiState.NotConfigured(null)
    }

    /** WebView 登录前获取合法登录链（含 callback 与 sign），失败回调 null。 */
    fun fetchWebLoginUrl(onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val url = withContext(ioDispatcher) {
                runCatching { authService.fetchWebLoginUrl() }.getOrNull()
            }
            onResult(url)
        }
    }

    private fun saveAndValidate(credential: XiaomiCredential) {
        viewModelScope.launch {
            _state.value = AuthUiState.Loading
            val result = withContext(ioDispatcher) {
                runCatching {
                    store.save(credential)
                    authService.getServiceToken(credential)
                }
            }
            if (result.isFailure) {
                // 新凭证验证失败：不留无效凭证
                store.clear()
            }
            _state.value = mapResult(credential, result)
        }
    }

    private suspend fun validate(credential: XiaomiCredential): AuthUiState = mapResult(
        credential,
        withContext(ioDispatcher) {
            runCatching { authService.getServiceToken(credential) }
        },
    )

    /** 统一把换取结果映射为 UI 状态（成功 → Ready；失败 → NotConfigured）。 */
    private fun mapResult(credential: XiaomiCredential, result: Result<SessionToken>): AuthUiState =
        result.fold(
            onSuccess = { AuthUiState.Ready(credential, it.obtainedAt) },
            onFailure = { AuthUiState.NotConfigured(it.message ?: "凭证验证失败") },
        )

    /** AppContainer 装配工厂。 */
    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AuthViewModel(
                store = container.credentialStore,
                authService = container.authService,
                onCacheInvalidate = container::invalidateCache,
            ) as T
    }
}
