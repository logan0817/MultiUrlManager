package com.logan.multiurlmanager.library.bean

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Information and data categories
 * 信息数据类
 *
 * @author logan
 * @date 2025/12/02
 */
@Parcelize
data class BaseUrl(
    val configKey: String,
    val url: String,
    var select: Boolean = false,
    var remark: String? = null,
    val time: Long = System.currentTimeMillis()
) : Parcelable, Comparable<BaseUrl> {

    override fun compareTo(other: BaseUrl): Int {
        return this.time.compareTo(other.time)
    }
}