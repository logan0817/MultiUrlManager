package com.logan.multiurlmanager.okhttp

import com.logan.multiurlmanager.library.BaseUrlManager
import com.logan.multiurlmanager.library.UrlSnapshot
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.net.URI

/**
 * 注册为 application interceptor，并放在依赖目标环境的鉴权与签名之前。
 * 无 RouteKey 的请求原样通过；受管请求按登记来源的完整 Base URL 路径前缀替换。
 * 每个 Call 进入拦截器时读取一次快照，排队请求会使用届时已成功提交的配置。
 *
 * 受管请求不得提前附加鉴权、Cookie、Host 或签名头；它们应由后续拦截器按
 * RouteContext 生成。自定义鉴权头、CookieJar 及 Authenticator 的环境隔离由宿主负责。
 */
class UrlRoutingInterceptor internal constructor(
    private val snapshotProvider: () -> UrlSnapshot,
    sourceBaseUrls: Map<String, String>
) : Interceptor {
    constructor(manager: BaseUrlManager, sourceBaseUrls: Map<String, String>) :
        this(manager::snapshot, sourceBaseUrls)

    private val sources = sourceBaseUrls.map { (key, value) ->
        RouteKey(key).configKey to parseBaseUrl(value)
    }.toMap()

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val key = request.tag(RouteKey::class.java)?.configKey ?: return chain.proceed(request)
        val source = sources[key] ?: throw IOException("No source Base URL registered for route: $key")
        val url = request.url
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
            throw IOException("Managed requests must not contain URL credentials")
        }
        val prefix = source.encodedPathSegments.dropLast(1)
        if (url.scheme != source.scheme || url.host != source.host || url.port != source.port ||
            url.encodedPathSegments.size <= prefix.size ||
            url.encodedPathSegments.take(prefix.size) != prefix
        ) {
            throw IOException("Request is outside the registered source Base URL for route: $key")
        }
        // 即使 host 相同也可能属于不同环境，不能根据 host 相同而信任旧凭据。
        val unsafeHeader = request.headers.names().firstOrNull { name ->
            PREBOUND_HEADERS.any { it.equals(name, ignoreCase = true) }
        }
        if (unsafeHeader != null) {
            throw IOException("Add $unsafeHeader after URL routing using RouteContext")
        }
        val snapshot = try {
            snapshotProvider()
        } catch (error: Exception) {
            throw IOException("Unable to read URL routing configuration", error)
        }
        val target = try {
            val value = snapshot.effectiveUrls()[key]
                ?: throw IllegalArgumentException("No selected Base URL")
            parseBaseUrl(value)
        } catch (error: Exception) {
            throw IOException("No valid selected Base URL for route: $key", error)
        }
        val segments = target.encodedPathSegments.dropLast(1) + url.encodedPathSegments.drop(prefix.size)
        val routedUrl = url.newBuilder()
            .scheme(target.scheme)
            .host(target.host)
            .port(target.port)
            .encodedPath("/" + segments.joinToString("/"))
            .build()
        val routed = request.newBuilder()
            .url(routedUrl)
            .tag(RouteContext::class.java, RouteContext(snapshot.environmentId, snapshot.revision, key, target))
            .build()
        return chain.proceed(routed)
    }

    private companion object {
        val PREBOUND_HEADERS = setOf("Authorization", "Proxy-Authorization", "Cookie", "Host", "Signature", "Signature-Input")

        fun parseBaseUrl(value: String): HttpUrl {
            val uri = URI(value)
            require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
                "Base URL must not contain credentials, query or fragment"
            }
            return value.toHttpUrl().also {
                require(it.encodedPath.endsWith('/')) { "Base URL must end with /" }
            }
        }
    }
}
