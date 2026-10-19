package com.logan.multiurlmanager

import android.app.Application
import android.util.Log
import com.logan.multiurlmanager.library.BaseUrlManager

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            // 地址和初始环境均来自构建合并后的 JSON，不在代码中按 DEBUG 切换。
            BaseUrlManager.builder(this).build()
        } catch (_: Exception) {
            // 保留损坏数据，不自动恢复到另一台服务器；主页面显示初始化错误。
            Log.e(
                "MultiUrlManagerSample",
                "Configuration initialization failed; existing data preserved"
            )
        }
    }
}
