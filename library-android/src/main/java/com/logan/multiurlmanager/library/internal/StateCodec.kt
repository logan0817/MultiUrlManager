package com.logan.multiurlmanager.library.internal

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.logan.multiurlmanager.library.UrlEnvironment
import com.logan.multiurlmanager.library.UrlSnapshot
import com.logan.multiurlmanager.library.bean.BaseUrl
import java.math.BigDecimal

/** 手工字段映射，避免反射序列化依赖混淆后的字段名；格式版本独立于库版本。 */
internal object StateCodec {
    fun requireSupportedVersion(root: JsonObject) {
        val version = root.get("schemaVersion")
        // Gson 的 asInt 会把 1.5 截为 1，也会接受字符串 "1"；格式版本不可这样降级。
        require(version != null && version.isJsonPrimitive && version.asJsonPrimitive.isNumber &&
            version.asBigDecimal.compareTo(BigDecimal.ONE) == 0) { "Unsupported schemaVersion" }
    }

    fun encode(state: UrlSnapshot): String = JsonObject().apply {
        addProperty("schemaVersion", 1)
        addProperty("revision", state.revision)
        addProperty("environmentId", state.environmentId)
        add("environments", JsonArray().apply {
            state.environments.forEach { env ->
                add(JsonObject().apply {
                    addProperty("id", env.id)
                    addProperty("name", env.name)
                    add("requiredKeys", JsonArray().apply {
                        env.requiredKeys.forEach { add(it) }
                    })
                    add("urls", JsonArray().apply {
                        env.urls.forEach { value ->
                            add(JsonObject().apply {
                                addProperty("configKey", value.configKey)
                                addProperty("url", value.url)
                                addProperty("select", value.select)
                                addProperty("remark", value.remark)
                                addProperty("time", value.time)
                            })
                        }
                    })
                })
            }
        })
    }.toString()

    fun decode(text: String): UrlSnapshot {
        val root = JsonParser.parseString(text).asJsonObject
        requireSupportedVersion(root)
        val environments = root.getAsJsonArray("environments").map { element ->
            val env = element.asJsonObject
            // 允许读回 clear 或用户删除后的未完成配置；显式保存时再验证当前环境必选项。
            UrlRules.validate(
                UrlEnvironment(
                    env.get("id").asString,
                    env.get("name").asString,
                    env.getAsJsonArray("urls").map { parseUrl(it.asJsonObject) },
                    env.getAsJsonArray("requiredKeys")?.map { it.asString }?.toSet() ?: emptySet()
                ),
                checkRequired = false
            )
        }
        require(environments.isNotEmpty() && environments.map { it.id }.distinct().size == environments.size) {
            "Invalid environments"
        }
        val current = root.get("environmentId").asString
        require(environments.any { it.id == current }) { "Unknown current environment" }
        return UrlSnapshot(root.get("revision")?.asLong ?: 0, current, environments)
    }

    fun parseUrl(item: JsonObject): BaseUrl = UrlRules.normalize(
        BaseUrl(
            item.get("configKey").asString,
            item.get("url").asString,
            item.get("select")?.asBoolean ?: false,
            item.get("remark")?.takeUnless { it.isJsonNull }?.asString,
            item.get("time")?.asLong ?: 0
        )
    )
}
