package com.logan.multiurlmanager.upgrade11

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.logan.multiurlmanager.library.BaseUrlManager
import com.logan.multiurlmanager.library.SaveResult
import com.logan.multiurlmanager.library.UrlEnvironment
import com.logan.multiurlmanager.library.bean.BaseUrl
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 真实 1.1.0 写入多环境快照，覆盖安装后验证选择、删除与自定义候选均保留。 */
@RunWith(AndroidJUnit4::class)
class Upgrade11Test {
    @Test fun preservesVersionedSnapshotAcrossUpgradeAndRestart() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val expected = File(context.filesDir, "upgrade11-expected.json")
        val gson = Gson()
        val defaults = listOf("dev", "production").map { id ->
            UrlEnvironment(id, id, listOf(
                BaseUrl("order", "https://$id.example.com/api/", true, "primary", 1),
                BaseUrl("order", "https://backup-$id.example.com/api/", false, "backup", 2),
                BaseUrl("login", "https://login-$id.example.com/", true, "login", 3)
            ), setOf("order", "login"))
        }
        val manager = BaseUrlManager.builder(context).setDefaultEnvironment("production")
            .setEnvironments(defaults).build()
        if (BuildConfig.LEGACY) {
            assertFalse("必须从隔离空夹具开始", expected.exists())
            assertTrue(manager.switchEnvironment("dev") is SaveResult.Success)
            val draft = manager.snapshot()
            val edited = draft.copy(environments = draft.environments.map { env ->
                if (env.id == "dev") env.copy(urls = env.urls.filterNot { it.remark == "backup" }
                    .map { if (it.configKey == "order") it.copy(select = false) else it }
                    + BaseUrl("order", "https://custom.example.com/v2/", true, "custom / 中文", 9))
                else env.copy(urls = env.urls.map { it.copy(remark = "Production kept") })
            })
            assertTrue(manager.save(edited) is SaveResult.Success)
            // 先由真实 1.1.0 重读持久化状态，保留它按业务分组后的既有候选顺序。
            manager.refreshData()
            expected.writeText(gson.toJson(manager.snapshot()))
        } else {
            assertTrue("不能清除数据代替覆盖升级", expected.isFile)
            val firstUpgrade = InstrumentationRegistry.getArguments().getString("attempt") == "0"
            val expectedSnapshot = JsonParser.parseString(expected.readText()).asJsonObject
            if (firstUpgrade) {
                // 旧版 refresh 只在内存中按业务归组；1.3 首次将同一归组结果落盘，revision 必须加一。
                expectedSnapshot.addProperty("revision", expectedSnapshot.get("revision").asLong + 1)
            }
            assertEquals(expectedSnapshot, gson.toJsonTree(manager.snapshot()))
            assertEquals("dev", manager.snapshot().environmentId)
            assertEquals("https://custom.example.com/v2/", manager.getUrl("order"))
            assertFalse(manager.snapshot().urls.any { it.url == "https://backup-dev.example.com/api/" })
            val persisted = File(context.filesDir, "multiurlmanager-state.json").readBytes()
            manager.refreshData()
            assertEquals(expectedSnapshot, gson.toJsonTree(manager.snapshot()))
            assertArrayEquals("重复刷新不得重新写入或继续增加 revision", persisted,
                File(context.filesDir, "multiurlmanager-state.json").readBytes())
            if (firstUpgrade) {
                val draft = manager.snapshot()
                val edited = draft.copy(environments = draft.environments.map { env ->
                    if (env.id == "dev") env.copy(urls = env.urls.map { it.copy(remark = "Edited in new version") }) else env
                })
                assertTrue(manager.save(edited) is SaveResult.Success)
                expected.writeText(gson.toJson(manager.snapshot()))
            }
        }
    }
}
