package com.logan.multiurlmanager.library.bean

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * 单个业务候选地址。configKey 在宿主查询与配置文件中必须一致；url 经管理器校验和规范化。
 * select、remark 保留旧版可变字段，管理器返回的副本仍需显式保存；time 用于历史排序。
 */
@Parcelize
data class BaseUrl(
    val configKey: String,
    val url: String,
    var select: Boolean = false,
    var remark: String? = null,
    val time: Long = System.currentTimeMillis()
) : Parcelable, Comparable<BaseUrl> {
    override fun compareTo(other: BaseUrl): Int = time.compareTo(other.time)
}
