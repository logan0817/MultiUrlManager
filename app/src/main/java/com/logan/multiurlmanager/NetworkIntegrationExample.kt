package com.logan.multiurlmanager

import com.logan.multiurlmanager.library.BaseUrlManager
import com.logan.multiurlmanager.library.UrlChangeListener
import com.logan.multiurlmanager.okhttp.RouteContext
import com.logan.multiurlmanager.okhttp.RouteKey
import com.logan.multiurlmanager.okhttp.UrlRoutingInterceptor
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Tag
import java.io.Closeable
import java.net.URI

/**
 * 无额外网络依赖的接入样例。使用方用 ApiService 的位置创建自己的 Retrofit Service。
 * 在途请求继续使用旧实例；token、cookie 和业务缓存由宿主按环境自行处理。
 */
class NetworkIntegrationExample(private val manager: BaseUrlManager) : Closeable {
    data class ApiService(val baseUrl: URI) {
        fun endpoint(relativePath: String): URI {
            val relative = URI(relativePath)
            require(!relative.isAbsolute && relative.rawAuthority == null && !relativePath.startsWith('/'))
            return baseUrl.resolve(relative)
        }
    }

    @Volatile
    private var services = createServices()
    private val listener = UrlChangeListener { _, _ ->
        val current = manager.snapshot()
        val previous = services
        services = current.effectiveUrls().mapValues { (key, value) ->
            previous[key]?.takeIf { it.baseUrl.toString() == value } ?: ApiService(URI(value))
        }
    }

    init {
        manager.addListener(listener)
        // 注册后再读取一次，覆盖构造服务到注册监听之间可能发生的配置变更。
        services = createServices()
    }

    fun service(key: String): ApiService? = services[key]

    private fun createServices() = manager.snapshot().effectiveUrls().mapValues { ApiService(URI(it.value)) }

    override fun close() {
        manager.removeListener(listener)
    }
}

/**
 * 可选 OkHttp 模块的真实 Retrofit 接入；构造时不发送请求。
 * 宿主按 RouteContext.environmentId 查询凭据；CookieJar、401 刷新和业务缓存仍由宿主管理。
 */
class RoutingNetworkExample(
    manager: BaseUrlManager,
    tokenFor: (RouteContext) -> String?
) : Closeable {
    private val source = "https://bootstrap.example/api/"
    private val client = OkHttpClient.Builder()
        .addInterceptor(UrlRoutingInterceptor(manager, mapOf("order" to source, "login" to source)))
        .addInterceptor { chain ->
            val context = checkNotNull(chain.request().tag(RouteContext::class.java))
            val request = chain.request().newBuilder()
            tokenFor(context)?.let { request.header("Authorization", "Bearer $it") }
            chain.proceed(request.build())
        }
        .build()
    private val service = Retrofit.Builder().baseUrl(source).client(client).build().create(Service::class.java)

    // 同一 Service 可重复使用；实际选址发生在执行请求时。
    fun order(id: String): Call<ResponseBody> = service.order(id, RouteKey("order"))

    fun login(): Call<ResponseBody> = service.login(RouteKey("login"))

    override fun close() {
        client.dispatcher.cancelAll()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    interface Service {
        @GET("orders/{id}")
        fun order(@Path("id") id: String, @Tag route: RouteKey): Call<ResponseBody>

        @GET("session")
        fun login(@Tag route: RouteKey): Call<ResponseBody>
    }
}
