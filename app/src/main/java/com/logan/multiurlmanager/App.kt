package com.logan.multiurlmanager

import android.app.Application
import com.logan.multiurlmanager.library.BaseUrlManager
import com.logan.multiurlmanager.library.bean.BaseUrl
import com.logan.multiurlmanager.library.utils.BaseUrlConfigLoader

class App : Application() {

    override fun onCreate() {
        super.onCreate()
//        initBaseUrlManagerByConfigFile()
        initBaseUrlManagerBuyCode()
    }

    fun initBaseUrlManagerByConfigFile() {
        //加载base_urls_config.json配置
        BaseUrlManager.builder(this).build()
        BaseUrlManager.instance?.getBaseUrl("customKey")
    }

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
            .setFileConfigKey(BaseUrlConfigLoader.DEBUG_CONFIG_KEY)
//            .setFileConfigKey(BaseUrlConfigLoader.RELEASE_CONFIG_KEY)
//            .setFileConfigKey("CUSTOM_CONFIG_KEY")
            .build()
    }

}