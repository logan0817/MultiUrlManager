package com.logan.multiurlmanager

import android.app.Application
import android.util.Log
import com.logan.multiurlmanager.library.BaseUrlManager

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            // 地址仍来自打包 JSON；Release 宿主示例明确选择固定模式，不覆盖设备调试数据。
            BaseUrlManager.builder(this).apply {
                if (BuildConfig.FIXED_PRODUCTION) setFixedEnvironment("production")
            }.build()
        } catch (_: Exception) {
            // 保留损坏数据，不自动恢复到另一台服务器；主页面显示初始化错误。
            Log.e(
                "MultiUrlManagerSample",
                "Configuration initialization failed; existing data preserved"
            )
        }
    }
}
