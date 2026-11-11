package com.logan.multiurlmanager.library.internal

import com.google.gson.JsonParser
import com.logan.multiurlmanager.library.ConfigurationTransferLimits
import com.logan.multiurlmanager.library.ImportChange
import com.logan.multiurlmanager.library.ImportChangeType
import com.logan.multiurlmanager.library.ImportFailureReason
import com.logan.multiurlmanager.library.ImportMode
import com.logan.multiurlmanager.library.UrlEnvironment
import com.logan.multiurlmanager.library.UrlSnapshot
import com.logan.multiurlmanager.library.utils.BaseUrlConfigLoader

internal class ImportException(val reason: ImportFailureReason, message: String) : IllegalArgumentException(message)

/** 只构造隔离草稿，不持有 Context，不读取文件，不改变管理器状态。 */
internal object ConfigurationTransferCodec {
    fun parse(json: String, knownKeys: Set<String>): List<UrlEnvironment> {
        if (json.toByteArray(Charsets.UTF_8).size > ConfigurationTransferLimits.MAX_BYTES) {
            fail(ImportFailureReason.LIMIT, "File exceeds 256 KiB; split the configuration")
        }
        // 先检查结构，禁止把设备内部快照误当成可移植配置，也不接受旧数组文件。
        val root = ConfigurationJson.document(json)
        if (!root.has("schemaVersion") || root.has("revision") || root.has("environmentId") || root.has("defaults")) {
            fail(ImportFailureReason.VALIDATION, "Expected a portable schemaVersion 1 configuration")
        }
        val environments = BaseUrlConfigLoader.parseConfiguration(json, checkRequired = false).environments
        checkCounts(environments)
        environments.forEachIndexed { index, environment ->
            if ((environment.urls.map { it.configKey } + environment.requiredKeys).any { it !in knownKeys }) {
                fail(ImportFailureReason.UNKNOWN_BUSINESS, "environments[$index]: undeclared business key")
            }
        }
        return environments
    }

    fun export(snapshot: UrlSnapshot, environmentIds: Set<String>): String {
        require(environmentIds.isNotEmpty()) { "Select at least one environment" }
        require(environmentIds.all { id -> snapshot.environments.any { it.id == id } }) { "Unknown export environment" }
        val selected = snapshot.environments.filter { it.id in environmentIds }.map { UrlRules.validate(it, checkRequired = false) }
        checkCounts(selected)
        // 使用同一字段编码，移除所有设备元数据；时间也不影响团队交换的配置含义。
        val root = JsonParser.parseString(StateCodec.encode(snapshot.copy(environments = selected))).asJsonObject
        root.remove("revision")
        root.remove("environmentId")
        root.addProperty("defaultEnvironment", snapshot.environmentId.takeIf { it in environmentIds } ?: selected.first().id)
        root.getAsJsonArray("environments").forEach { environment ->
            environment.asJsonObject.getAsJsonArray("urls").forEach { it.asJsonObject.remove("time") }
        }
        return root.toString().also {
            require(it.toByteArray(Charsets.UTF_8).size <= ConfigurationTransferLimits.MAX_BYTES) {
                "Export exceeds 256 KiB; select fewer environments"
            }
        }
    }

    fun preview(
        base: UrlSnapshot,
        imported: List<UrlEnvironment>,
        environmentIds: Set<String>,
        mode: ImportMode,
        declaredGroups: Map<String, Set<String>>
    ): Pair<UrlSnapshot, List<ImportChange>> {
        require(environmentIds.isNotEmpty()) { "Select at least one environment" }
        require(environmentIds.all { id -> imported.any { it.id == id } }) { "Unknown import environment" }
        val selected = imported.filter { it.id in environmentIds }.associateBy { it.id }
        val changes = mutableListOf<ImportChange>()
        val merged = base.environments.map { local ->
            val incoming = selected[local.id] ?: return@map local
            val replacement = when (mode) {
                ImportMode.MERGE -> {
                    val existingGroups = local.urls.map { it.configKey }.toSet() + local.requiredKeys + declaredGroups[local.id].orEmpty()
                    val additions = incoming.urls.filterNot { value ->
                        local.urls.any { it.configKey == value.configKey && it.url == value.url }
                    }.map { value -> value.copy(select = value.select && value.configKey !in existingGroups) }
                    if (local.name != incoming.name || local.requiredKeys != incoming.requiredKeys) {
                        changes += ImportChange(ImportChangeType.LOCAL_PRESERVED, local.id)
                    }
                    incoming.urls.forEach { value ->
                        val existing = local.urls.find { it.configKey == value.configKey && it.url == value.url }
                        if (existing != null && (existing.remark != value.remark || existing.select != value.select) ||
                            existing == null && value.select && value.configKey in existingGroups) {
                            changes += ImportChange(ImportChangeType.LOCAL_PRESERVED, local.id, value.configKey, value.url)
                        }
                    }
                    local.copy(urls = local.urls + additions)
                }
                ImportMode.REPLACE_ENVIRONMENTS -> local.copy(urls = incoming.urls.map { value ->
                    // 相同候选保留本机历史时间，重复导入不产生只有时间不同的变更。
                    local.urls.find { it.configKey == value.configKey && it.url == value.url }
                        ?.let { value.copy(time = it.time) } ?: value.copy()
                })
            }
            UrlRules.validate(replacement)
        }.toMutableList()
        selected.values.filterNot { incoming -> base.environments.any { it.id == incoming.id } }.forEach {
            merged += UrlRules.validate(it)
        }
        checkCounts(merged)
        val result = base.copy(environments = merged)
        changes += differences(base, result)
        return result to changes
    }

    private fun differences(previous: UrlSnapshot, next: UrlSnapshot): List<ImportChange> = buildList {
        next.environments.forEach { environment ->
            val old = previous.environments.find { it.id == environment.id }
            if (old == null) add(ImportChange(ImportChangeType.ENVIRONMENT_ADDED, environment.id, current = environment.name))
            val before = old?.urls.orEmpty()
            val keys = (before.map { it.configKey } + environment.urls.map { it.configKey }).distinct()
            keys.forEach { key ->
                val oldUrl = before.find { it.configKey == key && it.select }?.url
                val newUrl = environment.urls.find { it.configKey == key && it.select }?.url
                if (oldUrl != newUrl) add(ImportChange(ImportChangeType.SELECTION_CHANGED, environment.id, key,
                    previous = oldUrl, current = newUrl))
            }
            environment.urls.forEach { value ->
                val prior = before.find { it.configKey == value.configKey && it.url == value.url }
                if (prior == null) add(ImportChange(ImportChangeType.CANDIDATE_ADDED, environment.id, value.configKey, value.url))
                else if (prior.remark != value.remark) add(ImportChange(ImportChangeType.REMARK_CHANGED, environment.id,
                    value.configKey, value.url, prior.remark, value.remark))
            }
            before.filter { value -> environment.urls.none { it.configKey == value.configKey && it.url == value.url } }.forEach {
                add(ImportChange(ImportChangeType.CANDIDATE_REMOVED, environment.id, it.configKey, it.url))
            }
        }
    }

    private fun checkCounts(environments: List<UrlEnvironment>) {
        if (environments.size > ConfigurationTransferLimits.MAX_ENVIRONMENTS ||
            environments.sumOf { it.urls.size.toLong() } > ConfigurationTransferLimits.MAX_CANDIDATES) {
            fail(ImportFailureReason.LIMIT, "Configuration exceeds 32 environments or 1000 candidates; split the configuration")
        }
    }

    private fun fail(reason: ImportFailureReason, message: String): Nothing = throw ImportException(reason, message)
}
