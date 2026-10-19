package com.logan.multiurlmanager.library.utils

import android.content.Context
import com.logan.multiurlmanager.library.BaseUrlManager
import com.logan.multiurlmanager.library.bean.BaseUrl

/**
 * 旧版兼容入口，与管理器共享状态及存储，使用前必须初始化。
 * context 参数只为保留旧签名；写入方法访问磁盘，失败抛异常。
 */
object BaseUrlUtil {
    private fun manager(): BaseUrlManager = checkNotNull(BaseUrlManager.instance) { "Initialize BaseUrlManager first" }

    fun put(context: Context, baseUrl: BaseUrl) {
        manager().addBaseURL(baseUrl)
    }

    fun remove(context: Context, baseUrl: BaseUrl) {
        manager().remove(baseUrl)
    }

    fun clear(context: Context) {
        manager().clear()
    }

    fun getBaseURL(context: Context, configKey: String): BaseUrl? = manager().getBaseUrl(configKey)

    fun loadDynamicBaseUrlConfigs(context: Context): Map<String, BaseUrl> =
        manager().snapshot().urls.associateBy { "${it.configKey}-${it.url}" }
}
