package com.logan.multiurlmanager.library.utils

import android.content.Context
import com.google.gson.JsonParser
import com.logan.multiurlmanager.library.UrlEnvironment
import com.logan.multiurlmanager.library.bean.BaseUrl
import com.logan.multiurlmanager.library.internal.StateCodec
import com.logan.multiurlmanager.library.internal.UrlRules

/** 读取最终合入宿主 APK 的固定文件，不根据库的构建类型猜测环境。 */
object BaseUrlConfigLoader {
    const val RELEASE_CONFIG_KEY = "release_defaults"
    const val DEBUG_CONFIG_KEY = "debug_defaults"

    internal data class Configuration(
        val environments: List<UrlEnvironment>,
        val defaultEnvironment: String?
    )

    internal fun loadConfiguration(context: Context): Configuration =
        context.assets.open("base_urls_config.json").bufferedReader(Charsets.UTF_8).use {
            parseConfiguration(it.readText())
        }

    internal fun parseConfiguration(text: String): Configuration {
        val root = JsonParser.parseString(text).asJsonObject
        val modern = root.has("schemaVersion")
        val environments = if (modern) {
            StateCodec.requireSupportedVersion(root)
            root.getAsJsonArray("environments").map { raw ->
                val env = raw.asJsonObject
                UrlRules.validate(
                    UrlEnvironment(
                        env.get("id").asString,
                        env.get("name").asString,
                        env.getAsJsonArray("urls").map { StateCodec.parseUrl(it.asJsonObject) },
                        env.getAsJsonArray("requiredKeys")?.map { it.asString }?.toSet() ?: emptySet()
                    )
                )
            }
        } else {
            // 旧格式顶层键就是环境 id，不能把新版字段混进旧数组对象中。
            root.entrySet().map { (key, value) ->
                UrlRules.validate(
                    UrlEnvironment(key, key, value.asJsonArray.map { StateCodec.parseUrl(it.asJsonObject) })
                )
            }
        }
        require(environments.isNotEmpty() && environments.map { it.id }.distinct().size == environments.size) {
            "Invalid environments"
        }
        val defaultId = if (modern && root.has("defaultEnvironment")) {
            val value = root.get("defaultEnvironment")
            require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "defaultEnvironment must be a string" }
            value.asString.also { id ->
                require(environments.any { it.id == id }) { "Unknown default environment" }
            }
        } else {
            null
        }
        return Configuration(environments, defaultId)
    }

    fun loadEnvironments(context: Context): List<UrlEnvironment> = loadConfiguration(context).environments

    /** configKeyName 为旧显式入口；缺省时依次取 JSON 声明和 release_defaults。 */
    fun loadFromAssets(context: Context, configKeyName: String?): List<BaseUrl> {
        val config = loadConfiguration(context)
        val id = configKeyName ?: config.defaultEnvironment ?: RELEASE_CONFIG_KEY
        return config.environments.firstOrNull { it.id == id }?.urls
            ?: throw IllegalArgumentException("Unknown configuration environment")
    }
}
