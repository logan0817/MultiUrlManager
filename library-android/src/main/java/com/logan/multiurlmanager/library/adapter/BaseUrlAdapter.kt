package com.logan.multiurlmanager.library.adapter

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.logan.multiurlmanager.library.R
import com.logan.multiurlmanager.library.bean.BaseUrl
import com.logan.multiurlmanager.library.bean.BaseUrlSection

/** 按业务分组展示候选地址；页面通过回调维护草稿，旧调用方仍可直接更新选中状态。 */
class BaseUrlAdapter(var listData: MutableList<BaseUrlSection>) :
    RecyclerView.Adapter<BaseUrlAdapter.BaseUrlViewHolder>() {

    var sourceLabel: ((BaseUrl) -> String)? = null
    var onSelect: ((BaseUrl) -> Unit)? = null
    var onActions: ((View, BaseUrl) -> Unit)? = null

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_NORMAL = 1
    }

    abstract class BaseUrlViewHolder(view: View) : RecyclerView.ViewHolder(view)

    class HeaderViewHolder(view: View) : BaseUrlViewHolder(view) {
        val tvKey: TextView = view.findViewById(R.id.tvKey)
    }

    class NormalViewHolder(view: View) : BaseUrlViewHolder(view) {
        val ivSelect: View = view.findViewById(R.id.ivSelect)
        val tvUrl: TextView = view.findViewById(R.id.tvUrl)
        val tvUrlRemark: TextView = view.findViewById(R.id.tvUrlRemark)
        val tvSource: TextView = view.findViewById(R.id.tvSource)
    }

    override fun getItemCount(): Int = listData.size

    override fun getItemViewType(position: Int): Int {
        return if (listData[position].isHeader) TYPE_HEADER else TYPE_NORMAL
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseUrlViewHolder {
        val layoutInflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> {
                val view = layoutInflater.inflate(R.layout.base_url_item_header_type, parent, false)
                HeaderViewHolder(view)
            }
            TYPE_NORMAL -> {
                val view = layoutInflater.inflate(R.layout.base_url_item_normal_type, parent, false)
                NormalViewHolder(view)
            }
            else -> throw IllegalArgumentException("Invalid view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: BaseUrlViewHolder, position: Int) {
        val item = listData[position]

        when (holder.itemViewType) {
            TYPE_HEADER -> {
                (holder as HeaderViewHolder).tvKey.text = item.headerText
            }
            TYPE_NORMAL -> {
                val normalHolder = holder as NormalViewHolder
                val baseUrl = item.baseUrl

                normalHolder.ivSelect.isSelected = (baseUrl?.select == true)
                normalHolder.itemView.isSelected = (baseUrl?.select == true)
                normalHolder.tvUrl.text = baseUrl?.url
                normalHolder.tvUrlRemark.text = baseUrl?.remark
                normalHolder.tvSource.text = baseUrl?.let { sourceLabel?.invoke(it) }.orEmpty()

                val isRemarkVisible = !baseUrl?.remark.isNullOrBlank()
                normalHolder.tvUrlRemark.visibility = if (isRemarkVisible) View.VISIBLE else View.GONE

                normalHolder.ivSelect.contentDescription = normalHolder.itemView.context.getString(
                    if (baseUrl?.select == true) R.string.base_url_selected else R.string.base_url_select
                )
                normalHolder.ivSelect.setOnClickListener { normalHolder.itemView.performClick() }
                normalHolder.itemView.setOnLongClickListener { view ->
                    baseUrl?.let { onActions?.invoke(view, it) }
                    true
                }
                normalHolder.itemView.setOnClickListener {
                    baseUrl?.let { value ->
                        onSelect?.invoke(value) ?: setSelected(value)
                    }
                }
            }
        }
    }

    /** 兼容未提供 onSelect 回调的调用方，同一业务只保留一个选中候选。 */
    @SuppressLint("NotifyDataSetChanged")
    fun setSelected(baseUrl: BaseUrl?) {
        val configKey = baseUrl?.configKey
        listData.filter { it.baseUrl?.configKey == configKey }.forEach {
            it.baseUrl?.select = (it.baseUrl?.url == baseUrl?.url)
        }
        notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    internal fun replaceEnvironment(list: MutableList<BaseUrlSection>) {
        // 整套环境没有候选身份连续性，原位重绑以避免删除动画结束后才显示新地址。
        listData = list
        notifyDataSetChanged()
    }

    fun setNewInstance(list: MutableList<BaseUrlSection>?) {
        if (list === listData) {
            return
        }
        val previous = listData
        val next = list ?: arrayListOf()
        val changes = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = previous.size
            override fun getNewListSize() = next.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val old = previous[oldItemPosition]
                val new = next[newItemPosition]
                return if (old.isHeader || new.isHeader) {
                    old.isHeader == new.isHeader && old.headerText == new.headerText
                } else {
                    old.baseUrl?.configKey == new.baseUrl?.configKey && old.baseUrl?.url == new.baseUrl?.url
                }
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                previous[oldItemPosition].headerText == next[newItemPosition].headerText &&
                    previous[oldItemPosition].baseUrl == next[newItemPosition].baseUrl
        })
        listData = next
        changes.dispatchUpdatesTo(this)
    }
}
