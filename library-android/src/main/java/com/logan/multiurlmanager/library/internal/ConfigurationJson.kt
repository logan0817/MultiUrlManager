package com.logan.multiurlmanager.library.internal

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.logan.multiurlmanager.library.UrlEnvironment
import com.logan.multiurlmanager.library.bean.BaseUrl
import java.io.StringReader
import java.math.BigDecimal

/** 新配置严格读取类型和字段路径；错误不回显配置原文或地址。旧存储仍由 StateCodec 兼容读取。 */
internal object ConfigurationJson {
    fun document(text: String, strict: Boolean = true): JsonObject {
        var path = "$"
        try {
            JsonReader(StringReader(text)).use { reader ->
                reader.strictness = if (strict) Strictness.STRICT else Strictness.LENIENT
                fun read(depth: Int): JsonElement {
                    path = reader.path
                    require(depth <= 32) { "Configuration nesting exceeds 32 levels" }
                    return when (reader.peek()) {
                        JsonToken.BEGIN_OBJECT -> JsonObject().apply {
                            reader.beginObject()
                            while (reader.hasNext()) {
                                val name = reader.nextName()
                                path = reader.path
                                require(!strict || !has(name)) { "Duplicate field" }
                                add(name, read(depth + 1))
                            }
                            reader.endObject()
                        }
                        JsonToken.BEGIN_ARRAY -> JsonArray().apply {
                            reader.beginArray()
                            while (reader.hasNext()) add(read(depth + 1))
                            reader.endArray()
                        }
                        JsonToken.STRING -> JsonPrimitive(reader.nextString())
                        JsonToken.NUMBER -> JsonPrimitive(BigDecimal(reader.nextString()))
                        JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
                        JsonToken.NULL -> { reader.nextNull(); JsonNull.INSTANCE }
                        else -> throw IllegalArgumentException("Unexpected JSON token")
                    }
                }
                val root = read(0)
                require(reader.peek() == JsonToken.END_DOCUMENT && root.isJsonObject)
                return root.asJsonObject
            }
        } catch (_: Exception) {
            // JsonReader 的原始异常可能包含输入文本；只公开字段路径。
            throw IllegalArgumentException("$path: invalid JSON structure")
        }
    }

    fun string(value: JsonElement?, path: String): String {
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) {
            "$path: expected string"
        }
        return value.asString
    }

    fun array(value: JsonElement?, path: String): JsonArray {
        require(value != null && value.isJsonArray) { "$path: expected array" }
        return value.asJsonArray
    }

    fun objectValue(value: JsonElement?, path: String): JsonObject {
        require(value != null && value.isJsonObject) { "$path: expected object" }
        return value.asJsonObject
    }

    private fun integer(value: JsonElement, path: String): Long {
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "$path: expected integer" }
        return try { value.asBigDecimal.longValueExact() } catch (_: ArithmeticException) {
            throw IllegalArgumentException("$path: expected 64-bit integer")
        }
    }

    fun environment(value: JsonElement, path: String): UrlEnvironment {
        val env = objectValue(value, path)
        val id = string(env.get("id"), "$path.id")
        val name = string(env.get("name"), "$path.name")
        require(id.isNotBlank()) { "$path.id: must not be blank" }
        require(name.isNotBlank()) { "$path.name: must not be blank" }
        val required = env.get("requiredKeys")?.let { raw ->
            array(raw, "$path.requiredKeys").mapIndexed { index, key ->
                string(key, "$path.requiredKeys[$index]").also {
                    require(it.isNotBlank() && it == it.trim()) { "$path.requiredKeys[$index]: invalid business key" }
                }
            }.also { require(it.distinct().size == it.size) { "$path.requiredKeys: duplicate key" } }
        }.orEmpty()
        val urls = array(env.get("urls"), "$path.urls").mapIndexed { index, raw ->
            val location = "$path.urls[$index]"
            val item = objectValue(raw, location)
            val key = string(item.get("configKey"), "$location.configKey")
            require(key.isNotBlank()) { "$location.configKey: must not be blank" }
            val url = string(item.get("url"), "$location.url")
            val selected = item.get("select")?.let {
                require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean) { "$location.select: expected boolean" }
                it.asBoolean
            } ?: false
            val remark = item.get("remark")?.takeUnless { it.isJsonNull }?.let { string(it, "$location.remark") }
            val time = item.get("time")?.let { integer(it, "$location.time") } ?: 0L
            try { UrlRules.normalize(BaseUrl(key, url, selected, remark, time)) } catch (_: Exception) {
                throw IllegalArgumentException("$location.url: invalid HTTP(S) base URL")
            }
        }
        val seen = mutableSetOf<Pair<String, String>>()
        val selected = mutableSetOf<String>()
        urls.forEachIndexed { index, url ->
            require(seen.add(url.configKey to url.url)) { "$path.urls[$index].url: duplicate candidate" }
            require(!url.select || selected.add(url.configKey)) { "$path.urls[$index].select: multiple selections" }
        }
        return UrlEnvironment(id, name, urls, required.toSet())
    }
}
