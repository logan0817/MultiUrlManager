package com.logan.multiurlmanager.library

import android.annotation.SuppressLint
import android.content.Context
import com.logan.multiurlmanager.library.bean.BaseUrl
import com.logan.multiurlmanager.library.utils.BaseUrlConfigLoader
import com.logan.multiurlmanager.library.utils.BaseUrlUtil

/**
 * The specific implementation of the manager
 * 管理器的具体实现。
 *
 * @author logan
 * @date 2025/12/02
 */
class BaseUrlManager private constructor(
    private val context: Context,
    private val defaultProvider: (() -> List<BaseUrl>)?,
    private val configKeyToLoad: String?
) : IBaseUrlManager {

    override var dynamicUrls: MutableList<BaseUrl> = mutableListOf()
    override val count: Int
        get() = dynamicUrls.size

    init {
        refreshData()
    }

    override fun getBaseUrl(configKey: String, defaultUrl: String?): BaseUrl? {
        val config = dynamicUrls.find { it.configKey == configKey && it.select }
        return config ?: defaultUrl?.let { BaseUrl(configKey = configKey, url = it, select = true) }
    }


    override fun refreshData() {
        dynamicUrls =
            BaseUrlUtil.loadDynamicBaseUrlConfigs(context).map { it.value }.sorted().toMutableList()

        if (dynamicUrls.isEmpty()) {
            addDefaultConfigs()
        }

    }

    override fun addBaseURL(baseUrl: BaseUrl) {
        if (baseUrl.configKey.isNullOrBlank() || baseUrl.url.isNullOrBlank()) return
        dynamicUrls.removeIf { it.configKey == baseUrl.configKey && it.url == baseUrl.url }
        dynamicUrls.add(baseUrl)
        BaseUrlUtil.put(context, baseUrl)
    }

    override fun addBaseURL(baseUrls: Collection<BaseUrl>) {
        baseUrls.forEach { addBaseURL(it) }
    }

    override fun updateBaseURL(baseUrls: Collection<BaseUrl>) {
        clear()
        baseUrls.forEach { addBaseURL(it) }
    }

    override fun remove(baseUrl: BaseUrl) {
        if (baseUrl.configKey.isNullOrBlank() || baseUrl.url.isNullOrBlank()) return
        dynamicUrls.removeIf { it.configKey == baseUrl.configKey && it.url == baseUrl.url }
        BaseUrlUtil.remove(context, baseUrl)
    }

    override fun clear() {
        dynamicUrls.clear()
        BaseUrlUtil.clear(context)
    }


    /**
     * High priority: Code settings add default BaseUrl configuration, data is obtained from the external defaultProvider
     * Low priority: Load from a unified configuration file (BaseUrlConfigLoader handles environment differentiation)
     *
     * 优先级高：代码设置 添加默认的 BaseUrl 配置，从外部传入的 defaultProvider 获取数据
     * 优先级低：从统一配置文件中加载（BaseUrlConfigLoader 已处理环境区分）
     */
    private fun addDefaultConfigs() {
        // 优先从代码设置中获取默认配置
        val configsByCode = defaultProvider?.invoke()
        val defaultList: List<BaseUrl> = if (!configsByCode.isNullOrEmpty()) {
            // [High Priority] Configuration passed in via code - 【优先级高】使用代码传入的配置
            configsByCode
        } else {
            // [Low Priority] If no code is passed in, load the configuration file -【优先级低】如果代码未传入，则加载配置文件
            BaseUrlConfigLoader.loadFromAssets(context, configKeyToLoad)
        }

        if (defaultList.isNotEmpty()) {
            defaultList.forEach {
                addBaseURL(it.copy(time = System.currentTimeMillis()))
            }
        }
    }

    @SuppressLint("StaticFieldLeak")
    companion object {
        const val KEY_REGEX: String = "key_regex"
        const val HTTP_URL_REGEX: String =
            "^(https?|ftp|file)://[-a-zA-Z0-9+&@#/%?=~_|!:,.;]*[-a-zA-Z0-9+&@#/%=~_|]"

        var instance: BaseUrlManager? = null
            private set

        @JvmStatic
        fun builder(context: Context): Builder {
            return Builder(context.applicationContext)
        }

        class Builder(private val context: Context) {

            private var provider: (() -> List<BaseUrl>)? = null
            private var fileConfigKey: String? = null

            /**
             * Set high-priority code in the default BaseUrl configuration.
             * 设置高优先级的代码  BaseUrl默认配置。
             */
            fun setDefaultProvider(provider: (() -> List<BaseUrl>)?): Builder {
                this.provider = provider
                return this
            }

            /**
             * Set a low-priority configuration file key. If not configured, it will be automatically selected based on the environment (if (BuildConfig.DEBUG) DEBUG_CONFIG_KEY else RELEASE_CONFIG_KEY).
             * 设置低优先级的配置文件键名。如果不配置会根据环境自动选择(if (BuildConfig.DEBUG) DEBUG_CONFIG_KEY else RELEASE_CONFIG_KEY)
             */
            fun setFileConfigKey(key: String): Builder {
                this.fileConfigKey = key
                return this
            }

            /**
             * Build and initialize the BaseUrlManager singleton.
             * 构建并初始化 BaseUrlManager 单例。
             */
            fun build(): BaseUrlManager {
                if (instance == null) {
                    instance = BaseUrlManager(
                        context = context,
                        defaultProvider = provider,
                        configKeyToLoad = fileConfigKey
                    )
                } else {
                    println("BaseUrlManager has already been initialized.")
                }
                return instance!!
            }
        }
    }
}