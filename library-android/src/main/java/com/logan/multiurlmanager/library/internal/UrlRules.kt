package com.logan.multiurlmanager.library.internal

import com.logan.multiurlmanager.library.UrlEnvironment
import com.logan.multiurlmanager.library.bean.BaseUrl
import java.net.URI
import java.util.Locale

/** 配置文件、公开写入 API 和页面共用的校验规则，避免不同入口产生不一致状态。 */
internal object UrlRules {
    fun normalize(value: BaseUrl): BaseUrl {
        val key = value.configKey.trim()
        require(key.isNotEmpty()) { "configKey must not be blank" }
        val uri = URI(value.url.trim())
        require(uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https")) { "Only HTTP(S) URLs are supported" }
        require(!uri.host.isNullOrBlank() && uri.rawUserInfo == null) { "A valid host without credentials is required" }
        require(uri.port == -1 || uri.port in 1..65535) { "Invalid port" }
        require(uri.rawQuery == null && uri.rawFragment == null) { "Base URL cannot contain query or fragment" }
        val scheme = uri.scheme.lowercase(Locale.ROOT)
        val host = uri.host.lowercase(Locale.ROOT)
        val port = if (uri.port == -1 || (scheme == "http" && uri.port == 80) || (scheme == "https" && uri.port == 443)) {
            ""
        } else {
            ":${uri.port}"
        }
        val path = uri.rawPath.orEmpty().let { if (it.endsWith('/')) it else "$it/" }
        val url = "$scheme://$host$port$path"
        return value.copy(configKey = key, url = url)
    }

    fun validate(environment: UrlEnvironment, checkRequired: Boolean = true): UrlEnvironment {
        require(environment.id.isNotBlank() && environment.name.isNotBlank()) { "Environment id/name must not be blank" }
        val urls = environment.urls.map(::normalize)
        require(urls.map { it.configKey to it.url }.distinct().size == urls.size) { "Duplicate URL" }
        require(urls.filter { it.select }.groupBy { it.configKey }.none { it.value.size > 1 }) { "Multiple selected URLs for one key" }
        require(!checkRequired || environment.requiredKeys.all { key -> urls.any { it.configKey == key && it.select } }) { "Required key has no selected URL" }
        return environment.copy(urls = urls, requiredKeys = environment.requiredKeys.toSet())
    }

    fun upsert(items: List<BaseUrl>, input: BaseUrl): List<BaseUrl> {
        val value = normalize(input)
        // 更新已有条目而非删除后追加，保持候选和业务分组的展示顺序。
        val previous = items.find { it.configKey == value.configKey && it.url == value.url }
        val selected = value.select || previous?.select == true
        val updated = value.copy(select = selected, time = previous?.time ?: value.time)
        val result = items.map { item ->
            when {
                item.configKey == value.configKey && item.url == value.url -> updated
                selected && item.configKey == value.configKey -> item.copy(select = false)
                else -> item.copy()
            }
        }
        return if (previous == null) result + updated else result
    }
}
