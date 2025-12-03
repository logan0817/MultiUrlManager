package com.logan.multiurlmanager.library.bean

/**
 * 封装baseURL的SectionEntity子类实现
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
