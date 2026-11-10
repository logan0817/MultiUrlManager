package com.logan.multiurlmanager.okhttp

import com.logan.multiurlmanager.library.UrlEnvironment
import com.logan.multiurlmanager.library.UrlSnapshot
import com.logan.multiurlmanager.library.bean.BaseUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Tag
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class UrlRoutingInterceptorTest {
    private val servers = mutableListOf<MockWebServer>()
    private val clients = mutableListOf<OkHttpClient>()
    private val state = AtomicReference<UrlSnapshot>()
    private val source = "https://source.example/api/v1/"

    @After fun tearDown() {
        clients.forEach {
            it.dispatcher.executorService.shutdownNow()
            it.connectionPool.evictAll()
        }
        servers.forEach { it.shutdown() }
    }

    @Test fun existingRetrofitServiceUsesLatestSuccessfulSnapshotForEveryBusiness() {
        val dev = server()
        val test = server()
        state.set(snapshot("dev", 1, dev, "dev"))
        val api = api(client())
        dev.enqueue(MockResponse().setBody("dev-order"))
        assertEquals("dev-order", api.order(RouteKey("order")).execute().body()!!.string())
        assertEquals("/dev/order/orders/42?detail=true", received(dev).path)

        state.set(snapshot("test", 2, test, "test"))
        test.enqueue(MockResponse().setBody("test-order"))
        test.enqueue(MockResponse().setBody("test-login"))
        assertEquals("test-order", api.order(RouteKey("order")).execute().body()!!.string())
        assertEquals("test-login", api.login(RouteKey("login")).execute().body()!!.string())
        assertEquals("/test/order/orders/42?detail=true", received(test).path)
        assertEquals("/test/login/session", received(test).path)
        assertEquals(1, dev.requestCount)
    }

    @Test fun replacingOneBusinessDoesNotMoveTheOtherBusiness() {
        val first = server()
        val backup = server()
        val original = snapshot("dev", 4, first, "dev")
        state.set(original.copy(revision = 5, environments = original.environments.map { env ->
            env.copy(urls = env.urls.map { if (it.configKey == "order") it.copy(url = backup.url("/backup/").toString()) else it })
        }))
        val api = api(client())
        backup.enqueue(MockResponse())
        first.enqueue(MockResponse())
        api.order(RouteKey("order")).execute().body()!!.close()
        api.login(RouteKey("login")).execute().body()!!.close()
        assertEquals("/backup/orders/42?detail=true", received(backup).path)
        assertEquals("/dev/login/session", received(first).path)
    }

    @Test fun unmarkedThirdPartyRequestIsUnchangedAndDoesNotReadTheManager() {
        val thirdParty = server()
        thirdParty.enqueue(MockResponse())
        val interceptor = UrlRoutingInterceptor({ error("Must not read") }, emptyMap())
        val client = tracked(OkHttpClient.Builder().addInterceptor(interceptor).build())
        val request = Request.Builder().url(thirdParty.url("/signed/file?a=b%2Fc"))
            .header("Authorization", "third-party-token").build()
        client.newCall(request).execute().use { assertNull(it.request.tag(RouteContext::class.java)) }
        val received = received(thirdParty)
        assertEquals("/signed/file?a=b%2Fc", received.path)
        assertEquals("third-party-token", received.getHeader("Authorization"))
    }

    @Test fun methodBodyQueryAndEncodedPathArePreserved() {
        val target = server()
        state.set(snapshot("dev", 7, target, "gateway%2Froot"))
        target.enqueue(MockResponse())
        val request = request("${source}orders/a%2Fb/%E4%B8%AD/?key=a%2Fb&key=2&empty=")
            .post("payload".toRequestBody()).header("X-Request-ID", "id-7").build()
        client().newCall(request).execute().close()
        val received = received(target)
        assertEquals("/gateway%2Froot/order/orders/a%2Fb/%E4%B8%AD/?key=a%2Fb&key=2&empty=", received.path)
        assertEquals("POST", received.method)
        assertEquals("payload", received.body.readUtf8())
        assertEquals("id-7", received.getHeader("X-Request-ID"))
        assertNull(received.getHeader("RouteKey"))
    }

    @Test fun exactBasePathAndRootBaseKeepTrailingSlash() {
        val target = server()
        state.set(snapshot("dev", 7, target, "gateway"))
        target.enqueue(MockResponse())
        client().newCall(request(source).build()).execute().close()
        assertEquals("/gateway/order/", received(target).path)
        target.enqueue(MockResponse())
        val root = tracked(OkHttpClient.Builder().addInterceptor(UrlRoutingInterceptor(state::get, mapOf("order" to "https://source.example/"))).build())
        root.newCall(request("https://source.example/items").build()).execute().close()
        assertEquals("/gateway/order/items", received(target).path)
    }

    @Test fun markedUnknownSourceAndMissingSelectionFailWithoutAnyRequest() {
        val target = server()
        state.set(snapshot("dev", 1, target, "dev"))
        val client = client()
        assertThrows(IOException::class.java) {
            client.newCall(request().tag(RouteKey::class.java, RouteKey("unknown")).build()).execute()
        }
        state.set(state.get().copy(environments = state.get().environments.map { it.copy(urls = emptyList()) }))
        assertThrows(IOException::class.java) { client.newCall(request().build()).execute() }
        assertEquals(0, target.requestCount)
    }

    @Test fun originOrCompletePathMismatchFailsWithoutRequest() {
        val target = server()
        state.set(snapshot("dev", 1, target, "dev"))
        val client = client()
        listOf(
            "https://third-party.example/api/v1/orders",
            "http://source.example/api/v1/orders",
            "https://source.example:8443/api/v1/orders",
            "https://source.example/api/v10/orders",
            "https://source.example/api/v1",
            "https://source.example/orders",
            "https://user:password@source.example/api/v1/orders",
            "https://source.example/api/%76%31/orders"
        ).forEach { url ->
            assertThrows(url, IOException::class.java) { client.newCall(request(url).build()).execute() }
        }
        assertEquals(0, target.requestCount)
    }

    @Test fun snapshotFailureIsReportedAsIOException() {
        val interceptor = UrlRoutingInterceptor({ error("Configuration unavailable") }, mapOf("order" to source))
        val client = tracked(OkHttpClient.Builder().addInterceptor(interceptor).build())
        assertThrows(IOException::class.java) { client.newCall(request().build()).execute() }
    }

    @Test fun malformedTargetDoesNotSendRequest() {
        val target = server()
        val original = snapshot("dev", 1, target, "dev")
        val client = client()
        listOf("https://user:password@example.com/", "https://example.com/no-slash", "https://example.com/?q=secret", "https://example.com/#fragment").forEach { url ->
            state.set(original.copy(environments = original.environments.map {
                it.copy(urls = listOf(BaseUrl("order", url, true)))
            }))
            assertThrows(IOException::class.java) { client.newCall(request().build()).execute() }
        }
        assertEquals(0, target.requestCount)
    }

    @Test fun invalidSourceConfigurationFailsAtConstruction() {
        listOf("https://user:password@example.com/", "https://@example.com/", "https://example.com/no-slash", "https://example.com/?q=secret", "https://example.com/#fragment", "ftp://example.com/").forEach { url ->
            assertThrows(Exception::class.java) { UrlRoutingInterceptor(state::get, mapOf("order" to url)) }
        }
        assertThrows(IllegalArgumentException::class.java) { RouteKey(" order ") }
        assertThrows(IllegalArgumentException::class.java) { RouteKey("") }
    }

    @Test fun preboundCredentialsCookiesAndHostFailEvenWhenTargetHostDoesNotChange() {
        val target = server()
        val sourceUrl = target.url("/source/").toString()
        state.set(snapshot("different-environment", 9, target, "destination"))
        val client = tracked(OkHttpClient.Builder().addInterceptor(UrlRoutingInterceptor(state::get, mapOf("order" to sourceUrl))).build())
        listOf("Authorization", "Cookie", "Host", "Proxy-Authorization", "Signature", "Signature-Input").forEach { name ->
            val request = request(sourceUrl + "order").header(name, "old-value").build()
            assertThrows(IOException::class.java) { client.newCall(request).execute() }
        }
        assertEquals(0, target.requestCount)
    }

    @Test fun downstreamAuthenticationUsesOnePinnedContextEvenIfManagerChangesAgain() {
        val target = server()
        state.set(snapshot("test", 12, target, "test"))
        val contexts = mutableListOf<RouteContext>()
        val client = client(after = Interceptor { chain ->
            val context = checkNotNull(chain.request().tag(RouteContext::class.java))
            contexts += context
            state.set(snapshot("production", 13, target, "production"))
            chain.proceed(chain.request().newBuilder()
                .header("Authorization", "token-${context.environmentId}")
                .header("X-Environment", context.environmentId).build())
        })
        target.enqueue(MockResponse())
        client.newCall(request().build()).execute().close()
        val received = received(target)
        assertEquals("/test/order/orders/42", received.path)
        assertEquals("token-test", received.getHeader("Authorization"))
        assertEquals("test", received.getHeader("X-Environment"))
        assertEquals(12L, contexts.single().revision)
        assertEquals("order", contexts.single().configKey)
        assertEquals(target.url("/test/order/"), contexts.single().targetBaseUrl)
    }

    @Test fun alreadyCreatedRequestUsesEnvironmentWhenItEntersRouting() {
        val target = server()
        state.set(snapshot("dev", 1, target, "dev"))
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val client = client(before = Interceptor { chain ->
            entered.countDown()
            check(resume.await(5, TimeUnit.SECONDS))
            chain.proceed(chain.request())
        }, after = Interceptor { chain ->
            val context = checkNotNull(chain.request().tag(RouteContext::class.java))
            chain.proceed(chain.request().newBuilder().header("Authorization", "token-${context.environmentId}").build())
        })
        val call = client.newCall(request().build())
        val executor = Executors.newSingleThreadExecutor()
        try {
            target.enqueue(MockResponse())
            val future = executor.submit<String> { call.execute().use { it.body!!.string() } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            state.set(snapshot("test", 2, target, "test"))
            resume.countDown()
            future.get(5, TimeUnit.SECONDS)
            val received = received(target)
            assertEquals("/test/order/orders/42", received.path)
            assertEquals("token-test", received.getHeader("Authorization"))
        } finally {
            resume.countDown()
            call.cancel()
            executor.shutdownNow()
        }
    }

    @Test fun inFlightRequestStaysOnOriginalTargetWhileNextRequestUsesNewTarget() {
        val original = server()
        val next = server()
        state.set(snapshot("dev", 1, original, "dev"))
        val arrived = CountDownLatch(1)
        val resume = CountDownLatch(1)
        original.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                arrived.countDown()
                check(resume.await(5, TimeUnit.SECONDS))
                return MockResponse().setBody("dev-response")
            }
        }
        val client = client()
        val executor = Executors.newSingleThreadExecutor()
        val call = client.newCall(request().build())
        try {
            val future = executor.submit<String> { call.execute().use { it.body!!.string() } }
            assertTrue(arrived.await(5, TimeUnit.SECONDS))
            state.set(snapshot("test", 2, next, "test"))
            next.enqueue(MockResponse().setBody("test-response"))
            client.newCall(request().build()).execute().use { assertEquals("test-response", it.body!!.string()) }
            resume.countDown()
            assertEquals("dev-response", future.get(5, TimeUnit.SECONDS))
            assertEquals("/dev/order/orders/42", received(original).path)
            assertEquals("/test/order/orders/42", received(next).path)
        } finally {
            resume.countDown()
            call.cancel()
            executor.shutdownNow()
        }
    }

    @Test fun redirectUsesOkHttpFollowUpWithoutReroutingOrReadingNewSnapshot() {
        val original = server()
        val redirected = server()
        val reads = AtomicInteger()
        state.set(snapshot("dev", 1, original, "dev"))
        val interceptor = UrlRoutingInterceptor({ reads.incrementAndGet(); state.get() }, mapOf("order" to source))
        val client = tracked(OkHttpClient.Builder().addInterceptor(interceptor).build())
        original.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                state.set(snapshot("test", 2, redirected, "test"))
                return MockResponse().setResponseCode(302).setHeader("Location", redirected.url("/redirect/final"))
            }
        }
        redirected.enqueue(MockResponse().setBody("redirected"))
        client.newCall(request().build()).execute().use {
            assertEquals("redirected", it.body!!.string())
            assertEquals("dev", it.request.tag(RouteContext::class.java)!!.environmentId)
        }
        assertEquals(1, reads.get())
        assertEquals("/dev/order/orders/42", received(original).path)
        assertEquals("/redirect/final", received(redirected).path)
    }

    @Test fun sourceMapIsCopiedAndRequestReadsOnlyOneSnapshot() {
        val target = server()
        state.set(snapshot("dev", 1, target, "dev"))
        val sources = mutableMapOf("order" to source)
        val reads = AtomicInteger()
        val interceptor = UrlRoutingInterceptor({ reads.incrementAndGet(); state.get() }, sources)
        sources.clear()
        target.enqueue(MockResponse())
        tracked(OkHttpClient.Builder().addInterceptor(interceptor).build())
            .newCall(request().build()).execute().close()
        assertEquals(1, reads.get())
        assertEquals("/dev/order/orders/42", received(target).path)
    }

    private fun request(url: String = "${source}orders/42"): Request.Builder = Request.Builder()
        .url(url).tag(RouteKey::class.java, RouteKey("order"))

    private fun client(before: Interceptor? = null, after: Interceptor? = null): OkHttpClient = tracked(
        OkHttpClient.Builder().apply {
            before?.let(::addInterceptor)
            addInterceptor(UrlRoutingInterceptor(state::get, mapOf("order" to source, "login" to source)))
            after?.let(::addInterceptor)
        }.build()
    )

    private fun tracked(client: OkHttpClient): OkHttpClient = client.also(clients::add)

    private fun server(): MockWebServer = MockWebServer().apply { start(); servers += this }

    private fun received(server: MockWebServer): RecordedRequest =
        checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "Expected a local HTTP request" }

    private fun snapshot(id: String, revision: Long, server: MockWebServer, path: String): UrlSnapshot =
        UrlSnapshot(revision, id, listOf(UrlEnvironment(id, id, listOf(
            BaseUrl("order", server.url("/$path/order/").toString(), true),
            BaseUrl("login", server.url("/$path/login/").toString(), true)
        ))))

    private fun api(client: OkHttpClient): Service = Retrofit.Builder().baseUrl(source).client(client).build().create(Service::class.java)

    interface Service {
        @GET("orders/42?detail=true")
        fun order(@Tag route: RouteKey): retrofit2.Call<ResponseBody>

        @GET("session")
        fun login(@Tag route: RouteKey): retrofit2.Call<ResponseBody>
    }
}
