package com.logan.multiurlmanager.okhttp

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.logan.multiurlmanager.library.BaseUrlManager
import com.logan.multiurlmanager.library.SaveResult
import com.logan.multiurlmanager.library.UrlEnvironment
import com.logan.multiurlmanager.library.UrlSnapshot
import com.logan.multiurlmanager.library.bean.BaseUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** 真正串起异步保存、冲突/存储失败、固定模式与设备回环 HTTP；不触碰宿主配置。 */
@RunWith(AndroidJUnit4::class)
class RouteManagerTest {
    private lateinit var context: IsolatedContext
    private lateinit var dev: MockWebServer
    private lateinit var production: MockWebServer
    private var previousManager: BaseUrlManager? = null
    private val managers = mutableListOf<BaseUrlManager>()
    private val clients = mutableListOf<OkHttpClient>()
    private val instanceField = BaseUrlManager::class.java.getDeclaredField("instance").apply { isAccessible = true }
    private val source = "https://bootstrap.example/api/"

    @Before fun setUp() {
        previousManager = BaseUrlManager.instance
        instanceField.set(null, null)
        context = IsolatedContext(InstrumentationRegistry.getInstrumentation().targetContext)
        dev = MockWebServer().apply { start() }
        production = MockWebServer().apply { start() }
    }

    @After fun tearDown() {
        clients.forEach {
            it.dispatcher.cancelAll()
            it.dispatcher.executorService.shutdownNow()
            it.connectionPool.evictAll()
        }
        // 测试创建的队列独立释放；原进程管理器及其队列不受影响。
        val executor = BaseUrlManager::class.java.getDeclaredField("executor").apply { isAccessible = true }
        managers.forEach { (executor.get(it) as ExecutorService).shutdownNow() }
        instanceField.set(null, previousManager)
        dev.shutdown()
        production.shutdown()
        context.dispose()
    }

    @Test fun realManagerOnlyRoutesSuccessfulCommitsAndRejectsConflictOrStorageFailure() {
        val manager = manager()
        val client = client(manager)
        assertRequest(client, dev, "/dev/orders/42", "dev", 0)
        val stale = manager.snapshot()
        val success = save(manager, stale.copy(environmentId = "production")) as SaveResult.Success
        assertTrue(success.changed)
        assertRequest(client, production, "/production/orders/42", "production", success.snapshot.revision)

        assertEquals(SaveResult.Reason.CONFLICT, (save(manager, stale) as SaveResult.Failure).reason)
        assertRequest(client, production, "/production/orders/42", "production", success.snapshot.revision)
        val beforeFailure = context.files()
        val rejectWrites = injectWriteFailure(manager)
        val failure = save(manager, manager.snapshot().copy(environmentId = "dev")) as SaveResult.Failure
        assertEquals(SaveResult.Reason.STORAGE, failure.reason)
        assertEquals(success.snapshot, manager.snapshot())
        assertEquals(beforeFailure, context.files())
        assertRequest(client, production, "/production/orders/42", "production", success.snapshot.revision)

        // 即使底层恢复，也必须先成功重读，不能跳过不确定状态保护直接提交。
        rejectWrites.set(false)
        assertEquals(SaveResult.Reason.STORAGE,
            (save(manager, manager.snapshot().copy(environmentId = "dev")) as SaveResult.Failure).reason)
        manager.refreshData()
        val recovered = save(manager, manager.snapshot().copy(environmentId = "dev")) as SaveResult.Success
        assertRequest(client, dev, "/dev/orders/42", "dev", recovered.snapshot.revision)
    }

    @Test fun fixedModeRoutesPackagedDefinitionAndLeavesPersistedOverridesForOrdinaryMode() {
        val ordinary = manager()
        val draft = ordinary.snapshot()
        val override = draft.copy(environments = draft.environments.map { environment ->
            if (environment.id == "production") {
                environment.copy(urls = listOf(BaseUrl("order", dev.url("/custom-production/").toString(), true)))
            } else environment
        })
        assertTrue(save(ordinary, override) is SaveResult.Success)
        val persisted = ordinary.snapshot()
        val files = context.files()
        instanceField.set(null, null)
        val fixed = manager(fixed = true)
        assertTrue(fixed.isReadOnly)
        val client = client(fixed)
        assertRequest(client, production, "/production/orders/42", "production", 0)
        assertEquals(SaveResult.Reason.VALIDATION,
            (save(fixed, fixed.snapshot().copy(environmentId = "dev")) as SaveResult.Failure).reason)
        fixed.refreshData()
        assertRequest(client, production, "/production/orders/42", "production", 0)
        assertEquals(files, context.files())

        instanceField.set(null, null)
        val restored = manager()
        assertEquals(persisted, restored.snapshot())
        val selected = save(restored, restored.snapshot().copy(environmentId = "production")) as SaveResult.Success
        assertRequest(client(restored), dev, "/custom-production/orders/42", "production", selected.snapshot.revision)
    }

    private fun manager(fixed: Boolean = false): BaseUrlManager {
        val definitions = listOf(
            UrlEnvironment("dev", "Development", listOf(BaseUrl("order", dev.url("/dev/").toString(), true, time = 1))),
            UrlEnvironment("production", "Production", listOf(BaseUrl("order", production.url("/production/").toString(), true, time = 1)))
        )
        return BaseUrlManager.builder(context).setEnvironments(definitions).setDefaultEnvironment("dev")
            .apply { if (fixed) setFixedEnvironment("production") }
            .build().also(managers::add)
    }

    private fun client(manager: BaseUrlManager): OkHttpClient = OkHttpClient.Builder()
        // 回环测试不能经过设备的抓包代理，否则 localhost 会指向代理所在机器。
        .proxy(java.net.Proxy.NO_PROXY)
        .addInterceptor(UrlRoutingInterceptor(manager, mapOf("order" to source)))
        .addInterceptor { chain ->
            val route = checkNotNull(chain.request().tag(RouteContext::class.java))
            chain.proceed(chain.request().newBuilder()
                .header("Authorization", "token-${route.environmentId}")
                .header("X-Revision", route.revision.toString()).build())
        }.build().also(clients::add)

    private fun assertRequest(client: OkHttpClient, target: MockWebServer, path: String, environment: String, revision: Long) {
        target.enqueue(MockResponse().setBody("local-response"))
        val request = Request.Builder().url(source + "orders/42").tag(RouteKey::class.java, RouteKey("order")).build()
        client.newCall(request).execute().use { assertEquals("local-response", it.body!!.string()) }
        val received: RecordedRequest = checkNotNull(target.takeRequest(5, TimeUnit.SECONDS))
        assertEquals(path, received.path)
        assertEquals("token-$environment", received.getHeader("Authorization"))
        assertEquals(revision.toString(), received.getHeader("X-Revision"))
    }

    private fun save(manager: BaseUrlManager, draft: UrlSnapshot): SaveResult {
        val completed = CountDownLatch(1)
        val result = AtomicReference<SaveResult>()
        manager.saveAsync(draft) { result.set(it); completed.countDown() }
        check(completed.await(10, TimeUnit.SECONDS)) { "Async save callback did not complete" }
        return checkNotNull(result.get())
    }

    private fun injectWriteFailure(manager: BaseUrlManager): AtomicBoolean {
        val field = BaseUrlManager::class.java.getDeclaredField("store").apply { isAccessible = true }
        val original = field.get(manager)
        val contract = Class.forName("com.logan.multiurlmanager.library.internal.StateStore")
        val reject = AtomicBoolean(true)
        val failing = Proxy.newProxyInstance(contract.classLoader, arrayOf(contract)) { _, method, arguments ->
            if (method.name == "write" && reject.get()) throw IOException("Injected storage failure")
            try {
                method.invoke(original, *(arguments ?: emptyArray()))
            } catch (error: InvocationTargetException) {
                throw checkNotNull(error.cause)
            }
        }
        field.set(manager, failing)
        return reject
    }

    private class IsolatedContext(private val base: Context) : ContextWrapper(base) {
        private val id = "route-manager-${UUID.randomUUID()}"
        private val directory = File(base.cacheDir, id).apply { check(mkdirs()) }
        private val preferences = mutableSetOf<String>()
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = directory
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolated = "$id-$name"
            preferences += isolated
            return base.getSharedPreferences(isolated, mode)
        }
        fun files(): Map<String, List<Byte>> = directory.listFiles().orEmpty().associate { it.name to it.readBytes().toList() }
        fun dispose() {
            preferences.forEach { check(base.deleteSharedPreferences(it)) }
            check(directory.deleteRecursively())
        }
    }
}
