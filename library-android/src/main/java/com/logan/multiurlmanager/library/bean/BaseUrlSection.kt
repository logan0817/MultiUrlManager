package com.logan.multiurlmanager.library.bean

/** 配置列表的一行：非空 headerText 表示业务分组标题，否则展示 baseUrl 候选。 */
class BaseUrlSection(
    val baseUrl: BaseUrl? = null,
    val headerText: String? = null
) {
    val isHeader: Boolean
        get() = !headerText.isNullOrBlank()
}
