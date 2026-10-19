package com.logan.multiurlmanager.library

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.logan.multiurlmanager.library.bean.BaseUrl
import com.logan.multiurlmanager.library.internal.FileStateStore
import com.logan.multiurlmanager.library.internal.StateStore
import com.logan.multiurlmanager.library.internal.UrlRules
import com.logan.multiurlmanager.library.utils.BaseUrlConfigLoader
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

/**
 * 单进程配置入口。查询返回副本，写入先持久化再发布内存状态。
 *
 * 同步写入方法访问磁盘，应在后台调用；页面使用 [saveAsync]，其回调和变更通知在主线程派发。
 */
class BaseUrlManager internal constructor(
    defaults: List<UrlEnvironment>,
    initial: UrlSnapshot,
    private val store: StateStore,
    private val dispatch: ((() -> Unit) -> Unit) = { task -> Handler(Looper.getMainLooper()).post { task() } }
) : IBaseUrlManager {
    private val defaults = defaults.map {
        it.copy(urls = it.urls.map { url -> url.copy() }, requiredKeys = it.requiredKeys.toSet())
    }

    @Volatile
    private var storageUncertain = false

    @Volatile
    private var state = copy(initial)

    private val listeners = CopyOnWriteArraySet<UrlChangeListener>()
    private var defaultId: String? = null
    private val executor = Executors.newSingleThreadExecutor()

    @Deprecated("Use snapshot() and explicit save")
    override var dynamicUrls: MutableList<BaseUrl>
        get() = snapshot().urls.toMutableList()
        set(value) {
            updateBaseURL(value)
        }

    override val count: Int get() = state.urls.size

    /** 返回隔离副本；revision 必须随草稿保留，保存时用于检测并发编辑。 */
    fun snapshot(): UrlSnapshot = copy(state)

    /** 获取内置默认值副本；未知环境抛出 NoSuchElementException。 */
    fun defaultEnvironment(id: String): UrlEnvironment = defaults.first { it.id == id }.let {
        it.copy(urls = it.urls.map { url -> url.copy() }, requiredKeys = it.requiredKeys.toSet())
    }

    fun getUrl(configKey: String): String? = getBaseUrl(configKey)?.url

    override fun getBaseUrl(configKey: String, defaultUrl: String?): BaseUrl? =
        state.urls.find { it.configKey == configKey && it.select }?.copy()
            ?: defaultUrl?.let { UrlRules.normalize(BaseUrl(configKey, it, true)) }

    /** 仅环境或生效地址变化才通知；宿主应在相应生命周期结束时移除监听器。 */
    fun addListener(listener: UrlChangeListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: UrlChangeListener) {
        listeners.remove(listener)
    }

    /** 校验和提交整份快照；验证失败、存储失败或 revision 过期均不覆盖当前内存状态。 */
    @Synchronized
    fun save(candidate: UrlSnapshot): SaveResult {
        if (storageUncertain) {
            return SaveResult.Failure(SaveResult.Reason.STORAGE, "Reload storage before retrying")
        }
        if (candidate.revision != state.revision) {
            return SaveResult.Failure(SaveResult.Reason.CONFLICT, "Configuration changed; reload before saving")
        }
        val validated = try {
            require(candidate.environments.all { env ->
                val definition = defaults.find { it.id == env.id }
                definition == null || env.requiredKeys == definition.requiredKeys
            }) { "Required key declarations cannot be changed" }
            require(candidate.environments.map { it.id }.distinct().size == candidate.environments.size) {
                "Duplicate environment"
            }
            candidate.copy(environments = candidate.environments.map {
                UrlRules.validate(it, checkRequired = it.id == candidate.environmentId)
            }).also {
                require(it.environments.any { env -> env.id == it.environmentId }) { "Unknown environment" }
            }
        } catch (e: Exception) {
            return SaveResult.Failure(SaveResult.Reason.VALIDATION, e.message ?: "Invalid configuration")
        }
        return commit(validated)
    }

    private fun commit(validated: UrlSnapshot): SaveResult {
        if (storageUncertain) {
            return SaveResult.Failure(SaveResult.Reason.STORAGE, "Reload storage before retrying")
        }
        val previous = state
        if (validated == previous) return SaveResult.Success(snapshot(), false)
        val next = copy(validated.copy(revision = previous.revision + 1))
        try {
            store.write(next)
        } catch (_: Exception) {
            // 写入可能已完成而校验失败，必须重新读取确认，不能盲目重试覆盖磁盘。
            storageUncertain = true
            return SaveResult.Failure(
                SaveResult.Reason.STORAGE,
                "Unable to persist configuration; reload before retrying"
            )
        }
        state = next
        notifyChanged(previous, next)
        return SaveResult.Success(snapshot(), true)
    }

    private fun notifyChanged(previous: UrlSnapshot, next: UrlSnapshot) {
        if (previous.environmentId == next.environmentId && previous.effectiveUrls() == next.effectiveUrls()) return
        val observers = listeners.toList()
        dispatch {
            observers.forEach { observer ->
                try {
                    observer.onChanged(copy(previous), copy(next))
                } catch (e: Exception) {
                    Log.e("MultiUrlManager", "Configuration listener failed", e)
                }
            }
        }
    }

    /** 入队前复制草稿，防止调用方后续修改；后台串行写入，结果回到主线程。 */
    fun saveAsync(candidate: UrlSnapshot, callback: (SaveResult) -> Unit) {
        val input = copy(candidate)
        executor.execute {
            val result = save(input)
            dispatch { callback(result) }
        }
    }

    internal fun reloadAsync(callback: (Result<UrlSnapshot>) -> Unit) {
        executor.execute {
            val result = runCatching {
                refreshData()
                snapshot()
            }
            dispatch { callback(result) }
        }
    }

    @Synchronized
    fun switchEnvironment(id: String): SaveResult = save(snapshot().copy(environmentId = id))

    @Synchronized
    fun restoreDefaults(): SaveResult {
        val environment = defaults.find { it.id == state.environmentId }
            ?: return SaveResult.Failure(SaveResult.Reason.VALIDATION, "No defaults for current environment")
        return replace(environment.urls)
    }

    @Synchronized
    fun select(configKey: String, url: String): SaveResult = try {
        val normalized = UrlRules.normalize(BaseUrl(configKey, url, true))
        val existing = state.urls.find { it.configKey == normalized.configKey && it.url == normalized.url }
        replace(UrlRules.upsert(state.urls, existing?.copy(select = true) ?: normalized))
    } catch (_: Exception) {
        SaveResult.Failure(SaveResult.Reason.VALIDATION, "Invalid key or HTTP(S) base URL")
    }

    private fun replace(urls: List<BaseUrl>): SaveResult {
        val current = snapshot()
        return save(current.copy(environments = current.environments.map {
            if (it.id == current.environmentId) it.copy(urls = urls) else it
        }))
    }

    @Synchronized
    override fun addBaseURL(baseUrl: BaseUrl) {
        checkResult(replace(UrlRules.upsert(state.urls, baseUrl)))
    }

    @Synchronized
    override fun addBaseURL(baseUrls: Collection<BaseUrl>) {
        val inputs = baseUrls.map { it.copy() }
        UrlRules.validate(UrlEnvironment("input", "input", inputs))
        checkResult(replace(inputs.fold(state.urls) { values, value -> UrlRules.upsert(values, value) }))
    }

    @Synchronized
    override fun updateBaseURL(baseUrls: Collection<BaseUrl>) {
        checkResult(replace(baseUrls.map { it.copy() }))
    }

    @Synchronized
    override fun remove(baseUrl: BaseUrl) {
        checkResult(replace(state.urls.filterNot { it.configKey == baseUrl.configKey && it.url == baseUrl.url }))
    }

    @Synchronized
    override fun clear() {
        // 兼容旧清空入口；保留环境与必选声明，之后普通保存仍须补齐当前环境必选项。
        checkResult(commit(snapshot().copy(environments = state.environments.map { it.copy(urls = emptyList()) })))
    }

    @Synchronized
    override fun refreshData() {
        val loaded = store.read() ?: throw IllegalStateException("No persisted state; cannot reload")
        val previous = state
        state = copy(loaded)
        storageUncertain = false
        notifyChanged(previous, state)
    }

    companion object {
        @Volatile
        var instance: BaseUrlManager? = null
            private set

        @JvmStatic
        fun builder(context: Context): Builder = Builder(context.applicationContext)

        private fun copy(value: UrlSnapshot): UrlSnapshot = value.copy(environments = value.environments.map {
            it.copy(urls = it.urls.map { url -> url.copy() }, requiredKeys = it.requiredKeys.toSet())
        })

        private fun sameDefinitions(first: List<UrlEnvironment>, second: List<UrlEnvironment>): Boolean =
            first.map { it.copy(urls = it.urls.map { url -> url.copy(time = 0) }) } ==
                second.map { it.copy(urls = it.urls.map { url -> url.copy(time = 0) }) }

        private fun checkResult(result: SaveResult) {
            if (result is SaveResult.Failure) throw IllegalStateException("${result.reason}: ${result.message}")
        }

        /** 默认来源依次为显式环境、Provider、assets；默认环境优先级为 Builder、JSON、旧 release_defaults。 */
        class Builder(private val context: Context) {
            private var provider: (() -> List<BaseUrl>)? = null
            private var environments: List<UrlEnvironment>? = null
            private var environmentId: String? = null

            fun setDefaultProvider(provider: (() -> List<BaseUrl>)?): Builder = apply { this.provider = provider }

            fun setFileConfigKey(key: String): Builder = setDefaultEnvironment(key)

            fun setDefaultEnvironment(id: String): Builder = apply { environmentId = id }

            fun setEnvironments(values: List<UrlEnvironment>): Builder = apply { environments = values }

            fun build(): BaseUrlManager = synchronized(BaseUrlManager::class.java) {
                val fileConfig = if (environments == null && provider == null) {
                    BaseUrlConfigLoader.loadConfiguration(context)
                } else {
                    null
                }
                val defaultId = environmentId ?: fileConfig?.defaultEnvironment ?: BaseUrlConfigLoader.RELEASE_CONFIG_KEY
                val definitions = (environments
                    ?: provider?.let { listOf(UrlEnvironment(defaultId, defaultId, it())) }
                    ?: requireNotNull(fileConfig).environments).map { UrlRules.validate(it) }
                require(definitions.isNotEmpty() && definitions.map { it.id }.distinct().size == definitions.size) {
                    "Invalid environments"
                }
                require(definitions.any { it.id == defaultId }) { "Unknown default environment" }
                instance?.let { existing ->
                    require(sameDefinitions(existing.defaults, definitions) && existing.defaultId == defaultId) {
                        "BaseUrlManager already initialized with different configuration"
                    }
                    return@synchronized existing
                }
                val store = FileStateStore(context, definitions)
                // 已存配置和迁移优先；构建变体只决定首次安装默认值，不强制覆盖用户选择。
                val persisted = store.read() ?: store.migrate(definitions)
                val initial = persisted ?: UrlSnapshot(0, defaultId, definitions).also(store::write)
                BaseUrlManager(definitions, initial, store).also {
                    it.defaultId = defaultId
                    instance = it
                }
            }
        }
    }
}
