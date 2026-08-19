package com.monishguy.mifanscloud.data.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * 基于 Android Keystore 的加密凭证存储。
 *
 * - 主密钥：Android Keystore（AES-256-GCM，[MasterKeys]），设备级保护；
 * - 键用 AES-SIV 加密、值用 AES-GCM 加密（security-crypto 1.0.0 稳定版推荐组合）。
 */
class SecureCredentialStore(context: Context) : CredentialStore {

    private val prefs: SharedPreferences by lazy {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        // 注意：security-crypto 1.0.0 的 create 签名为 (fileName, masterKeyAlias, context, ...)，
        // Context 在第三位（1.1.0 起才改为首位）。
        EncryptedSharedPreferences.create(
            PREFS_NAME,
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun save(credential: XiaomiCredential) {
        val rawCookie = (credential as? XiaomiCredential.ServiceToken)?.rawCookie
        prefs.edit()
            .putString(KEY_USER_ID, credential.userId)
            .putString(KEY_TOKEN, credential.token)
            .putString(KEY_TOKEN_TYPE, credential.typeCode)
            .putString(KEY_RAW_COOKIE, rawCookie)
            .apply()
    }

    override fun load(): XiaomiCredential? {
        val userId = prefs.getString(KEY_USER_ID, null) ?: return null
        val token = prefs.getString(KEY_TOKEN, null) ?: return null
        return when (prefs.getString(KEY_TOKEN_TYPE, null)) {
            TYPE_SERVICE -> XiaomiCredential.ServiceToken(
                userId = userId,
                serviceToken = token,
                rawCookie = prefs.getString(KEY_RAW_COOKIE, null),
            )
            else -> XiaomiCredential.PassToken(userId, token)
        }
    }

    /** AutoRenewal 续期后更新 serviceToken（保留 rawCookie 与类型）。 */
    override fun updateServiceToken(newServiceToken: String) {
        val current = load() ?: return
        save(
            when (current) {
                is XiaomiCredential.PassToken -> current
                is XiaomiCredential.ServiceToken ->
                    current.copy(serviceToken = newServiceToken)
            }
        )
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREFS_NAME = "xiaomi_credentials"
        const val KEY_USER_ID = "userId"
        const val KEY_TOKEN = "token"
        const val KEY_TOKEN_TYPE = "tokenType"
        const val KEY_RAW_COOKIE = "rawCookie"
        const val TYPE_SERVICE = "service"

        val XiaomiCredential.token: String
            get() = when (this) {
                is XiaomiCredential.PassToken -> passToken
                is XiaomiCredential.ServiceToken -> serviceToken
            }

        val XiaomiCredential.typeCode: String
            get() = when (this) {
                is XiaomiCredential.PassToken -> "pass"
                is XiaomiCredential.ServiceToken -> TYPE_SERVICE
            }
    }
}
