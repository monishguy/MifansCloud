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

    /** 清除本地凭证并回到未配置状态。 */
    fun clearCredentials() {
        store.clear()
        _state.value = AuthUiState.NotConfigured(null)
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
            ) as T
    }
}
