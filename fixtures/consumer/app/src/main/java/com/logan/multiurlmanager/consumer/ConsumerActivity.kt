package com.logan.multiurlmanager.consumer

import android.app.Activity
import android.os.Bundle
import com.logan.multiurlmanager.library.BaseUrlManager
import com.logan.multiurlmanager.library.UrlEnvironment
import com.logan.multiurlmanager.library.ImportMode
import com.logan.multiurlmanager.library.ImportResult
import com.logan.multiurlmanager.library.utils.BaseUrlConfigLoader
import com.logan.multiurlmanager.library.bean.BaseUrl
import com.logan.multiurlmanager.okhttp.RouteContext
import com.logan.multiurlmanager.okhttp.RouteKey
import com.logan.multiurlmanager.okhttp.UrlRoutingInterceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

class ConsumerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BaseUrlManager.builder(this).setDefaultEnvironment("release")
            .setEnvironments(listOf(UrlEnvironment("release", "Release", listOf(BaseUrl("api", "https://example.com/", true)))))
            .build()
    }
    fun routeIntegration(): Boolean {
        val manager = checkNotNull(BaseUrlManager.instance)
        val snapshot = manager.snapshot()
        val interceptor = UrlRoutingInterceptor(manager, mapOf("api" to "https://bootstrap.example/api/v1/"))
        val request = Request.Builder().url("https://bootstrap.example/api/v1/orders/a%2Fb?detail=a%2Fb")
            .tag(RouteKey::class.java, RouteKey("api")).build()
        val client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            // 保留真实路由执行，只在末端返回响应，避免消费验收访问公网。
            .addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body("routed".toResponseBody()).build()
            }
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                val routed = response.request
                val context = routed.tag(RouteContext::class.java)
                routed.url.toString() == "https://consumer.example.com/orders/a%2Fb?detail=a%2Fb" &&
                    context?.environmentId == snapshot.environmentId &&
                    context.revision == snapshot.revision && context.configKey == "api" &&
                    context.targetBaseUrl.toString() == "https://consumer.example.com/" &&
                    routed.tag(RouteKey::class.java)?.configKey == "api" && response.body?.string() == "routed"
            }
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    fun roundTrip(): String? {
        val manager = checkNotNull(BaseUrlManager.instance)
        manager.addBaseURL(BaseUrl("api", "https://consumer.example.com/", true))
        manager.refreshData()
        return manager.getUrl("api")
    }

    fun emptyBundledConfiguration(): Boolean =
        BaseUrlConfigLoader.loadEnvironments(this).let { environments ->
            environments.map { it.id }.toSet() == setOf("release_defaults", "debug_defaults") &&
                environments.all { it.urls.isEmpty() }
        }

    fun configurationExchange(): Boolean {
        val manager = checkNotNull(BaseUrlManager.instance)
        val before = manager.snapshot()
        val json = manager.exportConfiguration(setOf(before.environmentId))
        val preview = manager.previewImport(json, before, setOf(before.environmentId), ImportMode.MERGE)
        return preview is ImportResult.Ready && preview.draft == before &&
            preview.changes.isEmpty() && manager.snapshot() == before && JavaConfigurationExchange.verify(manager)
    }
}
