package com.logan.multiurlmanager

import com.logan.multiurlmanager.library.BaseUrlManager
import com.logan.multiurlmanager.library.UrlChangeListener
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
