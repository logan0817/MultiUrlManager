package com.logan.multiurlmanager.okhttp

import okhttp3.HttpUrl

/**
 * 一次请求的固定选址结果。后续鉴权/签名读取此 Tag，不再单独读取管理器当前环境。
 * targetBaseUrl 是基础地址，最终请求路径及 query 仍以 Request.url 为准。
 */
data class RouteContext(
    val environmentId: String,
    val revision: Long,
    val configKey: String,
    val targetBaseUrl: HttpUrl
)
