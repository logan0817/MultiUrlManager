package com.logan.multiurlmanager.consumer

import android.app.Activity
import android.os.Bundle
import com.logan.multiurlmanager.library.BaseUrlManager
import com.logan.multiurlmanager.library.UrlEnvironment
import com.logan.multiurlmanager.library.bean.BaseUrl

class ConsumerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BaseUrlManager.builder(this).setDefaultEnvironment("release")
            .setEnvironments(listOf(UrlEnvironment("release", "Release", listOf(BaseUrl("api", "https://example.com/", true)))))
            .build()
    }
    fun roundTrip(): String? {
        val manager = checkNotNull(BaseUrlManager.instance)
        manager.addBaseURL(BaseUrl("api", "https://consumer.example.com/", true))
        manager.refreshData()
        return manager.getUrl("api")
    }
}
