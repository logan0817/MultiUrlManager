package com.logan.multiurlmanager.upgrade11

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.logan.multiurlmanager.library.BaseUrlManager
import com.logan.multiurlmanager.library.SaveResult
import com.logan.multiurlmanager.library.UrlEnvironment
import com.logan.multiurlmanager.library.bean.BaseUrl
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** 真实 1.2.0 普通配置，经新版固定模式启动后再次普通启动，原文件与选择必须保留。 */
@RunWith(AndroidJUnit4::class)
class FixedUpgrade12Test {
    @Test fun fixedUpgradeLeavesOrdinaryFilesUntouchedAndRestoresSelection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val expected = File(context.filesDir, "upgrade12-hashes.txt")
        val definitions = listOf("dev", "production").map { id ->
            UrlEnvironment(id, id, listOf(BaseUrl("order", "https://$id.example.com/", true, time = 1)), setOf("order"))
        }
        val builder = BaseUrlManager.builder(context).setEnvironments(definitions).setDefaultEnvironment("production")
        fun storedHashes(): String = listOf(context.filesDir, File(context.applicationInfo.dataDir, "shared_prefs"))
            .flatMap { directory -> directory.walkTopDown().filter {
                it.isFile && (it.name.startsWith("multiurlmanager-state.json") ||
                    it.name.startsWith("dynamic_base_urls_config.xml"))
            }.toList() }
            .sortedBy { it.absolutePath }.joinToString("\n") { file ->
                file.relativeTo(File(context.applicationInfo.dataDir)).path + "=" +
                    MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            }
        if (BuildConfig.LEGACY) {
            assertFalse(expected.exists())
            val manager = builder.build()
            assertTrue(manager.switchEnvironment("dev") is SaveResult.Success)
            assertTrue(manager.select("order", "https://custom.example.com/") is SaveResult.Success)
            context.getSharedPreferences("dynamic_base_urls_config", 0).edit().putString("retained", "legacy backup").commit()
            expected.writeText(storedHashes())
        } else {
            assertTrue("必须保留旧版本数据", expected.isFile)
            val before = expected.readText()
            assertEquals(before, storedHashes())
            val fixed = InstrumentationRegistry.getArguments().getString("attempt") == "0"
            val manager = if (fixed) builder.setFixedEnvironment("production").build() else builder.build()
            assertEquals(fixed, manager.isReadOnly)
            assertEquals(if (fixed) "production" else "dev", manager.snapshot().environmentId)
            assertEquals(if (fixed) "https://production.example.com/" else "https://custom.example.com/", manager.getUrl("order"))
            manager.refreshData()
            assertEquals(before, storedHashes())
        }
    }
}
