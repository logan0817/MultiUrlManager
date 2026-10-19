package com.logan.multiurlmanager.library

import com.logan.multiurlmanager.library.bean.BaseUrl

/** 兼容旧宿主的同步接口。写入及重载访问磁盘，应在后台线程调用，失败向调用方抛出异常。 */
interface IBaseUrlManager {
    /** 当前环境的候选副本；直接修改此集合或对象不会更新管理器。 */
    val dynamicUrls: MutableList<BaseUrl>

    /** 当前环境的候选数，包括未选中的地址。 */
    val count: Int

    /** 重新读取持久化状态；读取失败不替换内存状态，也不解除存储失败保护。 */
    fun refreshData()

    /** 当前环境选中地址；没有选择时才使用经校验的 defaultUrl，fallback 不写入存储。 */
    fun getBaseUrl(configKey: String, defaultUrl: String? = null): BaseUrl?

    /** 新增或更新候选，选中时取消同业务其他选择；保留已存在候选的位置与时间。 */
    fun addBaseURL(baseUrl: BaseUrl)

    /** 完整校验后一次性新增或更新；同一批输入的同业务多选会被拒绝。 */
    fun addBaseURL(baseUrls: Collection<BaseUrl>)

    /** 整体替换当前环境候选；其他环境保持不变，必选声明仍需满足。 */
    fun updateBaseURL(baseUrls: Collection<BaseUrl>)

    /** 按业务键和 URL 移除候选；不自动选择备用地址，移除必选业务唯一选择会失败。 */
    fun remove(baseUrl: BaseUrl)

    /** 清空全部环境候选但保留环境及必选声明；恢复默认须另行调用。 */
    fun clear()
}
