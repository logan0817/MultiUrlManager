package com.logan.multiurlmanager.okhttp

/** 显式选择受管业务；作为 Request Tag 使用，不会成为 HTTP 请求头。 */
data class RouteKey(val configKey: String) {
    init {
        require(configKey.isNotBlank() && configKey == configKey.trim()) {
            "Route key must not be blank or contain surrounding whitespace"
        }
    }
}
