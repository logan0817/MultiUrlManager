package com.logan.multiurlmanager.library

import com.logan.multiurlmanager.library.bean.BaseUrl

/**
 * 一套环境的候选地址。id 是持久化标识，升级时应保持稳定；name 只用于展示。
 * requiredKeys 仅约束显式声明的业务，每个必选业务必须恰有一个选中地址。
 */
data class UrlEnvironment(
    val id: String,
    val name: String,
    val urls: List<BaseUrl>,
    val requiredKeys: Set<String> = emptySet()
)

/**
 * 全部环境与当前选择的隔离快照。revision 来自管理器，保存草稿时不可自行递增或丢弃。
 * BaseUrl 保留旧版可变字段；修改副本后仍须显式保存才会生效。
 */
data class UrlSnapshot(
    val revision: Long,
    val environmentId: String,
    val environments: List<UrlEnvironment>
) {
    val urls: List<BaseUrl> get() = environments.first { it.id == environmentId }.urls

    fun effectiveUrls(): Map<String, String> = urls.filter { it.select }.associate { it.configKey to it.url }
}

/** 只有持久化成功才返回 Success；changed 表示整份快照是否发生变化。 */
sealed class SaveResult {
    data class Success(val snapshot: UrlSnapshot, val changed: Boolean) : SaveResult()

    data class Failure(val reason: Reason, val message: String) : SaveResult()

    enum class Reason {
        VALIDATION,
        STORAGE,
        CONFLICT
    }
}

/** 环境或生效映射变化时在主线程按提交顺序通知；仅备注/未选中候选变化不触发。 */
fun interface UrlChangeListener {
    fun onChanged(previous: UrlSnapshot, current: UrlSnapshot)
}
