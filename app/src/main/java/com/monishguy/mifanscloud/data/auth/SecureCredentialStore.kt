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

    override fun save(credentials: XiaomiCredentials) {
        prefs.edit()
            .putString(KEY_USER_ID, credentials.userId)
            .putString(KEY_PASS_TOKEN, credentials.passToken)
            .apply()
    }

    override fun load(): XiaomiCredentials? {
        val userId = prefs.getString(KEY_USER_ID, null) ?: return null
        val passToken = prefs.getString(KEY_PASS_TOKEN, null) ?: return null
        return XiaomiCredentials(userId, passToken)
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREFS_NAME = "xiaomi_credentials"
        const val KEY_USER_ID = "userId"
        const val KEY_PASS_TOKEN = "passToken"
    }
}
