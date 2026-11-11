package com.logan.multiurlmanager.compatibility

import com.logan.multiurlmanager.library.UrlEnvironment
import com.logan.multiurlmanager.library.UrlSnapshot
import com.logan.multiurlmanager.library.bean.BaseUrl
import com.logan.multiurlmanager.okhttp.RouteContext
import com.logan.multiurlmanager.okhttp.RouteKey
import com.logan.multiurlmanager.okhttp.UrlRoutingInterceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/** 分别对真实 1.2.0 与当前双 AAR 编译，运行时仅加载当前版本。 */
fun main(args: Array<String>) {
    main()
    JavaCompatibility12.verify()
    val selected = "https://target.example/gateway/"
    val snapshot = UrlSnapshot(12, "production", listOf(UrlEnvironment(
        "production", "Production", listOf(BaseUrl("order", selected, true))
    )))
    val source = "https://source.example/api/"
    // JVM 没有 Android Context，使用库内部快照提供器执行相同路由实现；公开构造另外核验。
    val constructor = UrlRoutingInterceptor::class.java.getConstructor(Function0::class.java, Map::class.java)
    val interceptor = constructor.newInstance({ snapshot }, mapOf("order" to source))
    val key = RouteKey("order").copy()
    val client = OkHttpClient.Builder().addInterceptor(interceptor).addInterceptor { chain ->
        val request = chain.request()
        check(request.url.toString() == "https://target.example/gateway/orders/42?q=1")
        val context = checkNotNull(request.tag(RouteContext::class.java))
        check(context.copy().environmentId == "production" && context.revision == 12L)
        check(context.configKey == key.configKey && context.targetBaseUrl.toString() == selected)
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
            .message("OK").body("routed".toResponseBody()).build()
    }.build()
    try {
        val request = Request.Builder().url(source + "orders/42?q=1").tag(RouteKey::class.java, key).build()
        client.newCall(request).execute().use { check(it.body?.string() == "routed") }
    } finally {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
    println("Kotlin/Java 1.2.0 双制品构造、copy、固定模式入口与实际路由消费通过")
}
