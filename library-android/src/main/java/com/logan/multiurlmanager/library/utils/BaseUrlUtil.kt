package com.logan.multiurlmanager.library.utils

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.logan.multiurlmanager.library.bean.BaseUrl

/**
 * BaseUrl is a utility class for information persistence, implemented based on SharedPreferences and Gson.
 * BaseUrl 信息持久化工具类，基于 SharedPreferences 和 Gson 实现。
 *
 * @author logan
 * @date 2025/12/02
 */
object BaseUrlUtil {

    private const val CONFIG_PREF_NAME = "dynamic_base_urls_config"

    private val gson = Gson()
    private val baseUrlType = object : TypeToken<BaseUrl>() {}.type

    private inline fun SharedPreferences.edit(action: SharedPreferences.Editor.() -> Unit) {
        val editor = edit()
        editor.action()
        editor.apply()
    }

    fun put(context: Context, baseUrl: BaseUrl) {
        if (baseUrl.configKey.isNullOrBlank() || baseUrl.url.isNullOrBlank()) return
        val json = gson.toJson(baseUrl)
        getSharedPreferences(context).edit {
            putString("${baseUrl.configKey}-${baseUrl.url}", json)
        }
    }

    fun remove(context: Context, baseUrl: BaseUrl) {
        if (baseUrl.configKey.isNullOrBlank() || baseUrl.url.isNullOrBlank()) return
        getSharedPreferences(context).edit {
            remove("${baseUrl.configKey}-${baseUrl.url}")
        }
    }

    fun clear(context: Context) {
        getSharedPreferences(context).edit { clear() }
    }

    fun getBaseURL(context: Context, configKey: String): BaseUrl? {
        val map = loadDynamicBaseUrlConfigs(context)
        val items = map.filter {
            val baseUrl = it.value
            (baseUrl.configKey == configKey) && baseUrl.select
        }
        return items.values.firstOrNull()
    }

    /**
     * * Loads all dynamic BaseUrl configurations.
     * * Returns a Map<Key, BaseUrl>, where the Value is the deserialized BaseUrl object.
     *
     * 加载所有动态 BaseUrl 配置。
     * 返回 Map<Key, BaseUrl>，其中 Value 是反序列化后的 BaseUrl 对象。
     */
    fun loadDynamicBaseUrlConfigs(context: Context): Map<String, BaseUrl> {
        return try {
            getSharedPreferences(context).all
                .mapNotNull { (key, value) ->
                    if (key is String && value is String && key.isNotEmpty() && value.isNotEmpty()) {
                        try {
                            // Key 是配置 Key，Value 是 BaseUrl JSON
                            val baseUrl = gson.fromJson<BaseUrl>(value, baseUrlType)
                            key to baseUrl
                        } catch (e: Exception) {
                            null
                        }
                    } else {
                        null
                    }
                }
                .toMap()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyMap()
        }
    }

    /**
     * 获取配置的 SharedPreferences。
     */
    private fun getSharedPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(CONFIG_PREF_NAME, Context.MODE_PRIVATE)
    }
}