package com.logan.multiurlmanager.library

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.logan.multiurlmanager.library.bean.BaseUrl
import com.logan.multiurlmanager.library.internal.FileStateStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** 只使用独立目录；原进程的单例及设备数据在测试后保持原状。 */
@RunWith(AndroidJUnit4::class)
class FixedEnvironmentTest {
    private lateinit var context: IsolatedContext
    private var previousManager: BaseUrlManager? = null
    private val instanceField = BaseUrlManager::class.java.getDeclaredField("instance").apply { isAccessible = true }
    private val production = UrlEnvironment("production", "Production", listOf(
        BaseUrl("order", "https://production.example.com/", true, time = 1)
    ), setOf("order"))
    private val dev = production.copy(id = "dev", name = "Development", urls = listOf(
        BaseUrl("order", "https://dev.example.com/", true, time = 1)
    ))
    private val definitions get() = listOf(dev, production)

    @Before fun setUp() {
        context = IsolatedContext(InstrumentationRegistry.getInstrumentation().targetContext)
        previousManager = BaseUrlManager.instance
        instanceField.set(null, null)
    }

    @After fun tearDown() {
        instanceField.set(null, previousManager)
        context.dispose()
    }

    private fun builder() = BaseUrlManager.builder(context).setEnvironments(definitions).setDefaultEnvironment("dev")
    private fun fixed() = builder().setFixedEnvironment("production").build()

    @Test fun fixedDefinitionBypassesPersistedOverridesAndPreservesEveryFile() {
        val overridden = production.copy(urls = listOf(production.urls.single().copy(url = "https://custom.example.com/")))
        val stored = UrlSnapshot(42, "dev", listOf(dev, overridden))
        FileStateStore(context, definitions).write(stored)
        val before = context.files()
        val manager = fixed()
        assertTrue(manager.isReadOnly)
        assertEquals(UrlSnapshot(0, "production", listOf(production)), manager.snapshot())
        assertEquals(production.urls.single().url, manager.getUrl("order"))
        assertNull(manager.getUrl("missing"))
        assertThrows(IllegalArgumentException::class.java) {
            manager.getBaseUrl("missing", "https://fallback.example.com/")
        }
        assertThrows(NoSuchElementException::class.java) { manager.defaultEnvironment("dev") }
        assertFalse((manager.save(manager.snapshot()) as SaveResult.Success).changed)
        val completed = java.util.concurrent.CountDownLatch(1)
        var asynchronous: SaveResult? = null
        manager.saveAsync(manager.snapshot().copy(environments = listOf(production.copy(urls = dev.urls)))) {
            asynchronous = it
            completed.countDown()
        }
        assertTrue(completed.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(SaveResult.Reason.VALIDATION, (asynchronous as SaveResult.Failure).reason)
        assertEquals(SaveResult.Reason.VALIDATION, (manager.select("order", dev.urls.single().url) as SaveResult.Failure).reason)
        assertEquals(SaveResult.Reason.VALIDATION, (manager.switchEnvironment("dev") as SaveResult.Failure).reason)
        assertFalse((manager.restoreDefaults() as SaveResult.Success).changed)
        assertThrows(IllegalStateException::class.java) { manager.clear() }
        assertThrows(IllegalStateException::class.java) { manager.remove(production.urls.single()) }
        assertThrows(IllegalStateException::class.java) { manager.updateBaseURL(dev.urls) }
        assertThrows(IllegalStateException::class.java) { manager.addBaseURL(dev.urls.single()) }
        assertThrows(IllegalStateException::class.java) { manager.addBaseURL(dev.urls) }
        manager.refreshData()
        assertEquals(before, context.files())
        instanceField.set(null, null)
        val ordinary = builder().build()
        assertFalse(ordinary.isReadOnly)
        assertEquals(stored, ordinary.snapshot())
    }

    @Test fun fixedModeDoesNotReadDamagedStateOrAtomicBackupOrLegacyPreferences() {
        File(context.filesDir, "multiurlmanager-state.json").writeText("broken state")
        File(context.filesDir, "multiurlmanager-state.json.bak").writeText("untouched backup")
        File(context.filesDir, "multiurlmanager-state.json.new").writeText("unfinished write")
        val before = context.files()
        context.rejectStorageAccess = true
        val manager = fixed()
        manager.refreshData()
        assertEquals(production.urls.single().url, manager.getUrl("order"))
        context.rejectStorageAccess = false
        assertEquals(before, context.files())
    }

    @Test fun initializationIdentityIncludesPolicyAndCopiedDisplayNames() {
        val names = mutableMapOf("order" to "订单服务")
        val manager = builder().setBusinessNames(names).setFixedEnvironment("production").build()
        names["order"] = "changed"
        assertEquals("订单服务", manager.businessName("order"))
        assertEquals("unknown", manager.businessName("unknown"))
        assertSame(manager, builder().setBusinessNames(mapOf("order" to "订单服务")).setFixedEnvironment("production").build())
        assertThrows(IllegalArgumentException::class.java) { builder().build() }
        assertThrows(IllegalArgumentException::class.java) {
            builder().setBusinessNames(mapOf("order" to "Other")).setFixedEnvironment("production").build()
        }
    }

    @Test fun missingFixedEnvironmentIsRejectedWithoutCreatingState() {
        assertThrows(IllegalArgumentException::class.java) {
            builder().setFixedEnvironment("missing").build()
        }
        assertTrue(context.files().isEmpty())
        assertNull(BaseUrlManager.instance)
    }

    private class IsolatedContext(base: Context) : ContextWrapper(base) {
        private val directory = File(base.cacheDir, "fixed-environment-${UUID.randomUUID()}").apply { check(mkdirs()) }
        var rejectStorageAccess = false
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File {
            check(!rejectStorageAccess) { "Fixed mode accessed storage" }
            return directory
        }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            error("This test never migrates legacy preferences")
        fun files(): Map<String, List<Byte>> = directory.listFiles().orEmpty().associate { it.name to it.readBytes().toList() }
        fun dispose() { check(directory.deleteRecursively()) }
    }
}
