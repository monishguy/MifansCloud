package com.monishguy.mifanscloud.data.auth

/**
 * 测试用内存凭证存储（CredentialStore 接口的 fake）。
 */
class InMemoryCredentialStore : CredentialStore {

    private var credential: XiaomiCredential? = null

    override fun save(credential: XiaomiCredential) {
        this.credential = credential
    }

    override fun load(): XiaomiCredential? = credential

    override fun updateServiceToken(newServiceToken: String) {
        credential = when (val c = credential) {
            is XiaomiCredential.PassToken -> c
            is XiaomiCredential.ServiceToken -> c.copy(serviceToken = newServiceToken)
            null -> null
        }
    }

    override fun clear() {
        credential = null
    }
}
