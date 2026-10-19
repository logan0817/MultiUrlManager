package com.logan.multiurlmanager.library.internal

import com.logan.multiurlmanager.library.UrlEnvironment
import com.logan.multiurlmanager.library.UrlSnapshot
import com.logan.multiurlmanager.library.bean.BaseUrl

/** 只有仍与上版默认一致的组跟随升级；用户覆盖和显式清空都保留。 */
internal object DefaultMerger {
    fun merge(state: UrlSnapshot, previous: List<UrlEnvironment>, defaults: List<UrlEnvironment>): UrlSnapshot {
        val merged = state.environments.map { saved ->
            val old = previous.find { it.id == saved.id } ?: return@map saved
            val next = defaults.find { it.id == saved.id } ?: return@map saved
            if (saved.urls.isEmpty()) return@map next.copy(urls = emptyList())
            val keys = (saved.urls + old.urls + next.urls).map { it.configKey }.distinct()
            val urls = keys.flatMap { key ->
                val current = saved.urls.filter { it.configKey == key }
                val before = old.urls.filter { it.configKey == key }
                val after = next.urls.filter { it.configKey == key }
                // 缺组代表用户显式删除；只有未修改组才整体跟随新默认。
                if (current.isEmpty() && before.isNotEmpty()) emptyList()
                else if (signature(current) == signature(before)) after else current + after
                    // 旧默认中存在而当前缺失的候选已被用户删除，不能每次读取都补回。
                    .filterNot { item -> current.any { it.url == item.url } || before.any { it.url == item.url } }
                    .map { it.copy(select = false) }
            }
            next.copy(urls = urls)
        }.toMutableList()
        defaults.filterNot { env -> merged.any { it.id == env.id } }.forEach(merged::add)
        return state.copy(environments = merged)
    }

    // 时间不参与默认比较，否则每次构造 BaseUrl 都会把相同配置误判为用户覆盖。
    private fun signature(urls: List<BaseUrl>) = urls.map { listOf(it.configKey, it.url, it.select, it.remark) }.toSet()
}
