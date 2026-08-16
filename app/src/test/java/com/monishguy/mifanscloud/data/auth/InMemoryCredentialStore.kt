package com.monishguy.mifanscloud.data.auth

/**
 * 测试用内存凭证存储（CredentialStore 接口的 fake）。
 */
class InMemoryCredentialStore : CredentialStore {

    private var credentials: XiaomiCredentials? = null

    override fun save(credentials: XiaomiCredentials) {
        this.credentials = credentials
    }

    override fun load(): XiaomiCredentials? = credentials

    override fun clear() {
        credentials = null
    }
}
