package com.logan.multiurlmanager.upgrade

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.logan.multiurlmanager.library.BaseUrlManager
import com.logan.multiurlmanager.library.bean.BaseUrl
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 两代测试使用相同包名/证书；旧进程写真实偏好，新 APK 覆盖安装后按旧 API 核验。 */
@RunWith(AndroidJUnit4::class)
class UpgradeTest {
    @Test fun preservesPersistedDataAcrossPackageUpgrade() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scenario = requireNotNull(InstrumentationRegistry.getArguments().getString("scenario"))
        val expectedFile = File(context.filesDir, "upgrade-expected.json")
        val prefsFile = File(context.filesDir, "upgrade-preferences.json")
        val preferences = context.getSharedPreferences("dynamic_base_urls_config", Context.MODE_PRIVATE)
        val gson = Gson()
        val builder = BaseUrlManager.builder(context)
        if (BuildConfig.LEGACY) {
            assertFalse("夹具必须从空数据开始", expectedFile.exists())
            val oldKey = if (BuildConfig.DEBUG) "debug_defaults" else "release_defaults"
            if (scenario == "code") {
                builder.setDefaultProvider {
                    listOf(
                        BaseUrl("videoApiDomain", "https://www.douyin.com/", true, "douyin Environment"),
                        BaseUrl("videoApiDomain", "https://www.kuaishou.com//", false, "kuaishou Environment"),
                        BaseUrl("mailDomain", "https://mail.google.com/", true, "mail google Environment"),
                        BaseUrl("mailDomain", "https://mail.qq.com/", false, "mail qq Environment")
                    )
                }
            } else builder.setFileConfigKey(oldKey)
            val manager = builder.build()
            if (scenario == "custom") {
                val rows = manager.dynamicUrls.map { it.copy(select = false) }.toMutableList()
                rows.add(BaseUrl("searchApiDomain", "https://custom.example.com/api/", true, "用户修改 / custom", 9))
                manager.updateBaseURL(rows)
            }
            if (scenario != "code" && scenario != "custom") {
                val asset = context.assets.open("base_urls_config.json").bufferedReader().use { it.readText() }
                val original = gson.fromJson(JsonParser.parseString(asset).asJsonObject.get(oldKey), Array<BaseUrl>::class.java).toList()
                assertEquals(original.map { it.copy(time = 0) }.toSet(), manager.dynamicUrls.map { it.copy(time = 0) }.toSet())
            }
            expectedFile.writeText(gson.toJson(manager.dynamicUrls))
            prefsFile.writeText(gson.toJson(preferences.all))
            assertFalse(File(context.filesDir, "multiurlmanager-state.json").exists())
        } else {
            assertTrue("必须保留旧 APK 写入的数据，不能用清除数据代替升级", expectedFile.isFile)
            val expected = gson.fromJson(expectedFile.readText(), Array<BaseUrl>::class.java).toList()
            val manager = builder.build()
            assertEquals(expected.toSet(), manager.dynamicUrls.toSet())
            expected.filter { it.select }.forEach { assertEquals(it.url, manager.getBaseUrl(it.configKey)?.url) }
            assertNull(manager.getBaseUrl("order"))
            assertNull(manager.getBaseUrl("login"))
            assertEquals(JsonParser.parseString(prefsFile.readText()), gson.toJsonTree(preferences.all))
            val state = JsonParser.parseString(File(context.filesDir, "multiurlmanager-state.json").readText()).asJsonObject
            assertEquals("legacy", state.get("environmentId").asString)
            val ids = state.getAsJsonArray("environments").map { it.asJsonObject.get("id").asString }.toSet()
            assertEquals(if (BuildConfig.DEBUG) setOf("dev", "test", "production", "legacy") else setOf("production", "legacy"), ids)
            manager.refreshData()
            assertEquals(expected.toSet(), manager.dynamicUrls.toSet())
            if (InstrumentationRegistry.getArguments().getString("attempt") == "0") {
                // 第二次进程必须保留新版编辑，而不是再次导入仍存在的旧偏好。
                val updated = manager.dynamicUrls.mapIndexed { index, row ->
                    if (index == 0) row.copy(remark = "Edited after migration") else row
                }
                manager.updateBaseURL(updated)
                expectedFile.writeText(gson.toJson(updated))
                assertEquals(JsonParser.parseString(prefsFile.readText()), gson.toJsonTree(preferences.all))
            }
        }
    }
}
