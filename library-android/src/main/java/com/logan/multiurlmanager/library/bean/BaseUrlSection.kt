package com.logan.multiurlmanager.library.bean

/**
 * Section implementation that encapsulates baseURL
 * 封装baseURL的Section实现
 *
 * @author logan
 * @date 2025/12/02
 */

class BaseUrlSection(
    val baseUrl: BaseUrl? = null,
    val headerText: String? = null
) {
    val isHeader: Boolean
        get() = !headerText.isNullOrBlank()
}
