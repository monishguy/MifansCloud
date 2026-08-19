package com.monishguy.mifanscloud.ui.auth

import com.monishguy.mifanscloud.data.auth.InMemoryCredentialStore
import com.monishguy.mifanscloud.data.auth.XiaomiAuthService
import com.monishguy.mifanscloud.data.auth.XiaomiCredential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * AuthViewModel 行为 seam：凭证输入 → 校验 → 状态流转与持久化。
 * 认证服务用 MockWebServer 模拟三步换取链，不依赖真实小米服务。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {

    private lateinit var server: MockWebServer
    private lateinit var store: InMemoryCredentialStore
    private lateinit var auth: XiaomiAuthService

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        store = InMemoryCredentialStore()
        auth = XiaomiAuthService(
            client = OkHttpClient.Builder().followRedirects(false).build(),
            baseUrl = server.url("/").toString().removeSuffix("/"),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun enqueueExchange(token: String, requestId: Int = 1) {
        val loginUrl = server.url("/mock/login-$requestId").toString()
        server.enqueue(MockResponse().setBody("""{"data":{"loginUrl":"$loginUrl"}}"""))
        server.enqueue(
            MockResponse().setResponseCode(302)
                .addHeader("Location", server.url("/mock/token-$requestId").toString())
        )
        server.enqueue(
            MockResponse().addHeader("Set-Cookie", "serviceToken=$token; Path=/").setBody("{}")
        )
    }

    private suspend fun TestScope.withMainDispatcher(block: suspend TestScope.() -> Unit) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            block()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `粘贴合法 cookie 保存并验证成功进入 Ready`() = runTest {
        withMainDispatcher {
            enqueueExchange("st_1")
            val vm = AuthViewModel(store, auth, ioDispatcher = StandardTestDispatcher(testScheduler))

            vm.saveFromCookie("userId=42; passToken=pt_abc; deviceId=wb_x; locale=zh_CN")
            advanceUntilIdle()

            val state = vm.state.value
            assertTrue("应进入 Ready", state is AuthUiState.Ready)
            assertEquals("42", (state as AuthUiState.Ready).credential.userId)
            assertEquals(XiaomiCredential.PassToken("42", "pt_abc"), store.load())
        }
    }

    @Test
    fun `粘贴无法解析的 cookie 进入 NotConfigured 且不落库`() = runTest {
        withMainDispatcher {
            val vm = AuthViewModel(store, auth, ioDispatcher = StandardTestDispatcher(testScheduler))

            vm.saveFromCookie("sessionId=abc; foo=bar")
            advanceUntilIdle()

            val state = vm.state.value
            assertTrue(state is AuthUiState.NotConfigured)
            assertNotNull((state as AuthUiState.NotConfigured).error)
            assertNull(store.load())
        }
    }

    @Test
    fun `只有 serviceToken 的 cookie 直连进入 Ready 且不发起网络`() = runTest {
        withMainDispatcher {
            val vm = AuthViewModel(store, auth, ioDispatcher = StandardTestDispatcher(testScheduler))

            vm.saveFromCookie("userId=42; serviceToken=st_only; i.mi.com_isvalid_servicetoken=true")
            advanceUntilIdle()

            val state = vm.state.value
            assertTrue("直连会话应直接 Ready", state is AuthUiState.Ready)
            assertEquals(
                XiaomiCredential.ServiceToken(
                    "42", "st_only",
                    rawCookie = "userId=42; serviceToken=st_only; i.mi.com_isvalid_servicetoken=true",
                ),
                store.load(),
            )
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `验证失败时清空已保存凭证`() = runTest {
        withMainDispatcher {
            server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
            val vm = AuthViewModel(store, auth, ioDispatcher = StandardTestDispatcher(testScheduler))

            vm.saveManually("42", "pt_bad")
            advanceUntilIdle()

            assertTrue(vm.state.value is AuthUiState.NotConfigured)
            assertNull("验证失败应清空存储", store.load())
        }
    }

    @Test
    fun `启动时已有有效凭证直接进入 Ready`() = runTest {
        withMainDispatcher {
            store.save(XiaomiCredential.PassToken("42", "pt_abc"))
            enqueueExchange("st_1")
            val vm = AuthViewModel(store, auth, ioDispatcher = StandardTestDispatcher(testScheduler))
            advanceUntilIdle()

            assertTrue(vm.state.value is AuthUiState.Ready)
        }
    }

    @Test
    fun `清除凭证回到 NotConfigured`() = runTest {
        withMainDispatcher {
            store.save(XiaomiCredential.PassToken("42", "pt_abc"))
            enqueueExchange("st_1")
            val vm = AuthViewModel(store, auth, ioDispatcher = StandardTestDispatcher(testScheduler))
            advanceUntilIdle()

            vm.clearCredentials()

            assertTrue(vm.state.value is AuthUiState.NotConfigured)
            assertNull(store.load())
        }
    }
}
