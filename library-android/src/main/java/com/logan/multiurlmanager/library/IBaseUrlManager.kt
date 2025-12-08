package com.logan.multiurlmanager.library

import com.logan.multiurlmanager.library.bean.BaseUrl


/**
 * Manager interface.
 * 管理器接口。
 *
 * @author logan
 * @date 2025/12/02
 */
interface IBaseUrlManager {

    /**
     * Retrieve a list of all BaseUrl information history records.
     * 获取所有 BaseUrl 信息历史记录列表。
     */
    val dynamicUrls: MutableList<BaseUrl>

    /**
     * The number of BaseUrl messages.
     * BaseUrl 信息的数量。
     */
    val count: Int

    /**
     * Refresh the data and reload the val dynamicUrls:MutableList<BaseUrl> list from persistent storage.
     * 刷新数据，从持久化存储中重新加载 val dynamicUrls:MutableList<BaseUrl> 列表。
     */
    fun refreshData()

    /**
     * Retrieve the BaseUrl of the currently selected select based on configKey.
     * 根据 configKey 获取 当前选中 select 的 BaseUrl。
     * @param configKey
     * @return 如果未配置则返回 null。
     */
    fun getBaseUrl(configKey: String, defaultUrl: String? = null): BaseUrl?

    /**
     * Add/update a BaseUrl to the history.
     * 添加/更新某个 BaseUrl 信息到历史记录。
     */
    fun addBaseURL(baseUrl: BaseUrl)

    /**
     * Add/update multiple BaseUrl entries to the history.
     * 添加/更新多个 BaseUrl 信息到历史记录。
     */
    fun addBaseURL(baseUrls: Collection<BaseUrl>)

    /**
     * Update all BaseUrl information to history [overwrites and will clear old data].
     * 更新全部 BaseUrl 信息到历史记录【覆盖，会清除旧数据】
     */
    fun updateBaseURL(baseUrls: Collection<BaseUrl>)

    /**
     * Remove the specified BaseUrl information and the corresponding data from persistent storage.
     * 移除指定的 BaseUrl 信息，同时从持久化存储中移除对应的数据
     */
    fun remove(baseUrl: BaseUrl)

    /**
     * Clear all configurations
     * 清除所有配置
     */
    fun clear()
}