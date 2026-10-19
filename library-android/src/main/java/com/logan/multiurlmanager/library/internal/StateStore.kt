package com.logan.multiurlmanager.library.internal

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.google.gson.JsonParser
import com.logan.multiurlmanager.library.UrlEnvironment
import com.logan.multiurlmanager.library.UrlSnapshot
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

internal interface StateStore {
    fun read(): UrlSnapshot?
    fun write(state: UrlSnapshot)
}

/** 原子快照存储。只在确认文件不存在时返回 null；损坏或未知版本必须上报，不能覆盖为默认值。 */
internal class FileStateStore(
    private val context: Context,
    private val defaults: List<UrlEnvironment> = emptyList()
) : StateStore {
    private val file = AtomicFile(File(context.filesDir, "multiurlmanager-state.json"))

    override fun read(): UrlSnapshot? = try {
        file.openRead().bufferedReader(Charsets.UTF_8).use {
            val text = it.readText()
            val saved = StateCodec.decode(text)
            val oldDefaults = JsonParser.parseString(text).asJsonObject.get("defaults")?.let { definition ->
                StateCodec.decode(definition.toString()).environments
            } ?: emptyList()
            DefaultMerger.merge(saved, oldDefaults, defaults)
        }
    } catch (e: FileNotFoundException) {
        if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) throw e
        null
    }

    override fun write(state: UrlSnapshot) {
        val document = JsonParser.parseString(StateCodec.encode(state)).asJsonObject
        if (defaults.isNotEmpty()) {
            // 连同本次默认定义保存，下一版才能区分未修改默认值与用户覆盖。
            document.add("defaults", JsonParser.parseString(StateCodec.encode(UrlSnapshot(0, defaults.first().id, defaults))))
        }
        val bytes = document.toString().toByteArray(Charsets.UTF_8)
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            stream.fd.sync()
            file.finishWrite(stream)
            if (!file.openRead().use { it.readBytes() }.contentEquals(bytes)) {
                throw IOException("State verification failed")
            }
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
    }

    fun migrate(defaults: List<UrlEnvironment>): UrlSnapshot? {
        val legacy = context.getSharedPreferences("dynamic_base_urls_config", Context.MODE_PRIVATE).all
        if (legacy.isEmpty()) return null
        val urls = legacy.values.mapNotNull { raw ->
            runCatching { StateCodec.parseUrl(JsonParser.parseString(raw as String).asJsonObject) }.getOrNull()
        }.sortedWith(compareBy({ it.time }, { it.url }))
        val invalidCount = legacy.size - urls.size
        require(urls.isNotEmpty()) { "Legacy configuration is invalid; restore defaults explicitly" }
        // 旧版按原始 URL 存储；补尾斜线、统一端口后可能碰撞，不能让较早的未选中项吞掉生效项。
        val unique = urls.groupBy { it.configKey to it.url }.values.map { duplicates ->
            duplicates.firstOrNull { it.select } ?: duplicates.first()
        }.sortedWith(compareBy({ it.time }, { it.url }))
        val selected = mutableSetOf<String>()
        val repaired = unique.map {
            it.copy(select = it.select && selected.add(it.configKey))
        }
        val repairedSelections = urls.count { it.select } - repaired.count { it.select }
        if (invalidCount > 0 || repairedSelections > 0) {
            Log.w("MultiUrlManager", "Legacy migration: invalid=$invalidCount repairedSelections=$repairedSelections")
        }
        // 宿主可能已使用 legacy，历史数据使用未占用的 id，不能删除同名默认环境。
        val occupied = defaults.map { it.id }.toSet()
        var id = "legacy"
        var suffix = 1
        while (id in occupied) id = "legacy-${suffix++}"
        val env = UrlEnvironment(id, "历史配置 / Legacy", repaired)
        // 旧 SharedPreferences 原样保留；新快照成功落盘后才算完成迁移。
        return UrlSnapshot(1, env.id, defaults + env).also(::write)
    }
}
