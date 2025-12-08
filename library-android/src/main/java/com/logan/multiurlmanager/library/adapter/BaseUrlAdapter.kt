package com.logan.multiurlmanager.library.adapter

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.logan.multiurlmanager.library.R
import com.logan.multiurlmanager.library.bean.BaseUrl
import com.logan.multiurlmanager.library.bean.BaseUrlSection

/**
 * 配置列表的适配器 (原生实现)。
 * 负责显示 BaseUrl 列表并管理选中状态。
 *
 * @author logan
 * @date 2025/12/02
 */
class BaseUrlAdapter(var listData: MutableList<BaseUrlSection>) :
    RecyclerView.Adapter<BaseUrlAdapter.BaseUrlViewHolder>() {

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
    }

    // --------------------------------------------------------------------------

    override fun getItemCount(): Int = listData.size

    /**
     * 手动管理 Header 和 Normal 视图类型。
     */
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
                // 绑定 Header
                (holder as HeaderViewHolder).tvKey.text = item.headerText
            }
            TYPE_NORMAL -> {
                // 绑定 Normal Item
                val normalHolder = holder as NormalViewHolder
                val baseUrl = item.baseUrl

                normalHolder.ivSelect.isSelected = (baseUrl?.select == true)
                normalHolder.tvUrl.text = baseUrl?.url
                normalHolder.tvUrlRemark.text = baseUrl?.remark

                val isRemarkVisible = !baseUrl?.remark.isNullOrBlank()
                normalHolder.tvUrlRemark.visibility = if (isRemarkVisible) View.VISIBLE else View.GONE

                normalHolder.itemView.setOnClickListener {
                    setSelected(baseUrl)
                }
            }
        }
    }

    /**
     * 更新选中状态的逻辑
     */
    @SuppressLint("NotifyDataSetChanged")
    fun setSelected(baseUrl: BaseUrl?) {
        val configKey = baseUrl?.configKey
        listData.filter { it.baseUrl?.configKey == configKey }.forEach {
            it.baseUrl?.select = (it.baseUrl?.url == baseUrl?.url)
        }
        notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun setNewInstance(list: MutableList<BaseUrlSection>?) {
        if (list === listData) {
            return
        }
        listData = list ?: arrayListOf()
        notifyDataSetChanged()
    }
}