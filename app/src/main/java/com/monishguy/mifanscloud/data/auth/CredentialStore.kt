package com.monishguy.mifanscloud.data.auth

/**
 * 小米云凭证的持久化 seam。
 * 生产实现为 [SecureCredentialStore]（EncryptedSharedPreferences + Keystore）；
 * 测试使用内存 fake。明文 token 一律不允许落日志。
 */
interface CredentialStore {

    fun save(credential: XiaomiCredential)

    fun load(): XiaomiCredential?

    fun clear()
}
