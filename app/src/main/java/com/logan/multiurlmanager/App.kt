package com.logan.multiurlmanager

import android.app.Application
import com.logan.multiurlmanager.library.BaseUrlManager
import com.logan.multiurlmanager.library.BuildConfig
import com.logan.multiurlmanager.library.bean.BaseUrl
import com.logan.multiurlmanager.library.utils.BaseUrlConfigLoader

class App : Application() {

    override fun onCreate() {
        super.onCreate()
//        initBaseUrlManagerByConfigFile()
        initBaseUrlManagerBuyCode()
    }

    /**
     * Method 1: Using base_urls_config.json configuration
     * 方式一：使用base_urls_config.json配置
     * */
    fun initBaseUrlManagerByConfigFile() {
        //Load the base_urls_config.json configuration. - 加载base_urls_config.json配置
        BaseUrlManager.builder(this)
            .setFileConfigKey(
                if (BuildConfig.DEBUG) {
                    BaseUrlConfigLoader.DEBUG_CONFIG_KEY
                    //setFileConfigKey("CUSTOM_CONFIG_KEY")
                } else {
                    BaseUrlConfigLoader.RELEASE_CONFIG_KEY
                    //setFileConfigKey("CUSTOM_CONFIG_KEY")
                }
            )
            .build()

        // Get baseUrl
        val videoApiDomainUrl = BaseUrlManager.instance?.getBaseUrl("videoApiDomain")
        val mailDomainBaseUrl = BaseUrlManager.instance?.getBaseUrl("mailDomain")
        val customKeyDmomainUrl = BaseUrlManager.instance?.getBaseUrl("customKey")
    }

    /**
     * Method 2: Using code configuration (base_urls_config.json will be ignored, code configuration has higher priority)
     * 方式二：使用代码配置，base_urls_config.json会失效。代码配置优先级高
     * */
    fun initBaseUrlManagerBuyCode() {
        BaseUrlManager.builder(this)
            .setDefaultProvider {
                listOf(
                    BaseUrl(configKey = "videoApiDomain", url = "https://www.douyin.com/", select = true, remark = "douyin Environment"),
                    BaseUrl(configKey = "videoApiDomain", url = "https://www.kuaishou.com//", select = false, remark = "kuaishou Environment"),
                    BaseUrl(configKey = "mailDomain", url = "https://mail.google.com/", select = true, remark = "mail google Environment"),
                    BaseUrl(configKey = "mailDomain", url = "https://mail.qq.com/", select = false, remark = "mail qq Environment")
                )
            }
            .build()

        // Get baseUrl
        val videoApiDomainUrl = BaseUrlManager.instance?.getBaseUrl("videoApiDomain")
        val mailDomainBaseUrl = BaseUrlManager.instance?.getBaseUrl("mailDomain")
        val customKeyDmomainUrl = BaseUrlManager.instance?.getBaseUrl("customKey")
    }

}