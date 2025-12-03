package com.logan.multiurlmanager.library.utils

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.logan.multiurlmanager.library.BuildConfig
import com.logan.multiurlmanager.library.bean.BaseUrl
import java.io.InputStreamReader


/**
 * 负责从 Assets 或其他配置源加载默认 BaseUrl 列表。
 *
 * @author logan
 * @date 2025/12/02
 */
object BaseUrlConfigLoader {

    private const val ASSET_FILE_NAME = "base_urls_config.json"

    const val RELEASE_CONFIG_KEY = "release_defaults"
    const val DEBUG_CONFIG_KEY = "debug_defaults"

    private val gson = Gson()

    private val jsonObjectType = object : TypeToken<JsonObject>() {}.type
    private val listType = object : TypeToken<List<BaseUrl>>() {}.type

    /**
     * 根据传入的 JSON 键名加载对应的配置列表。
     * @param context Context。
     * @param configKeyName 要加载的配置在 JSON 文件中的键名（如 "debug_defaults"）。
     * @return 对应的 BaseUrl 列表，如果键不存在或解析失败则返回空列表。
     */
    fun loadFromAssets(context: Context, configKeyName: String?): List<BaseUrl> {
        try {
            val defaultKeyName = if (BuildConfig.DEBUG) DEBUG_CONFIG_KEY else RELEASE_CONFIG_KEY
            val keyName = configKeyName ?: defaultKeyName
            context.assets.open(ASSET_FILE_NAME).use { inputStream ->
                InputStreamReader(inputStream).use { reader ->
                    val rootObject = gson.fromJson<JsonObject>(reader, jsonObjectType)

                    if (rootObject.has(keyName)) {
                        val jsonArray = rootObject.getAsJsonArray(keyName)
                        return gson.fromJson<List<BaseUrl>>(jsonArray, listType)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return emptyList()
    }
}