package com.logan.multiurlmanager.library

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonObject
import com.logan.multiurlmanager.library.bean.BaseUrl
import com.logan.multiurlmanager.library.internal.FileStateStore
import com.logan.multiurlmanager.library.internal.StateCodec
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** 每例独立使用文件目录和偏好名称，不依赖管理器全局单例或已有应用数据。 */
@RunWith(AndroidJUnit4::class)
class FileStateStoreTest {
    private lateinit var context: IsolatedContext
    private lateinit var store: FileStateStore
    private val login = BaseUrl("login", "https://dev.example.com/", true, "默认", 1)
    private val environment = UrlEnvironment("dev", "开发", listOf(login))
    private val initial = UrlSnapshot(4, "dev", listOf(environment))

    @Before fun setUp() {
        context = IsolatedContext(InstrumentationRegistry.getInstrumentation().targetContext)
        store = FileStateStore(context, listOf(environment))
    }

    @After fun tearDown() { context.dispose() }

    @Test fun missingFileReturnsNullAndWriteCanBeReadByNewStore() {
        assertNull(store.read())
        store.write(initial)
        assertEquals(initial, FileStateStore(context, listOf(environment)).read())
        assertTrue(stateFile().isFile)
    }

    @Test fun interruptedAtomicReplacementKeepsLastCompleteState() {
        store.write(initial)
        val atomic = AtomicFile(stateFile())
        val unfinished = atomic.startWrite()
        unfinished.write("{\"schemaVersion\":".toByteArray(Charsets.UTF_8))
        atomic.failWrite(unfinished)
        assertEquals(initial, FileStateStore(context, listOf(environment)).read())
    }

    @Test fun truncatedStateIsReportedAndDoesNotFallBackToLegacy() {
        writeLegacy(mapOf("original" to legacyJson(login)))
        stateFile().writeText("{\"schemaVersion\":1,\"environments\":[", Charsets.UTF_8)
        val damaged = stateFile().readText()
        expectFailure { store.read() ?: store.migrate(listOf(environment)) }
        assertEquals(damaged, stateFile().readText())
        assertEquals(legacyJson(login), preferences().getString("original", null))
    }

    @Test fun unknownSchemaIsReportedWithoutReplacingFile() {
        val future = StateCodec.encode(initial).replace("\"schemaVersion\":1", "\"schemaVersion\":99")
        stateFile().writeText(future, Charsets.UTF_8)
        expectFailure { store.read() }
        assertEquals(future, stateFile().readText())
    }

    @Test fun migrationKeepsValidRowsRepairsMultipleSelectionAndPreservesPreferences() {
        val earlier = login.copy(url = "https://a.example.com/", time = 1)
        val sameTime = login.copy(url = "https://b.example.com/", time = 1)
        val later = login.copy(url = "https://c.example.com/", time = 3)
        val files = BaseUrl("files", "https://files.example.com/", true, time = 2)
        writeLegacy(mapOf(
            "later" to legacyJson(later),
            "same-time" to legacyJson(sameTime),
            "earlier" to legacyJson(earlier),
            "duplicate" to legacyJson(earlier),
            "files" to legacyJson(files),
            "broken-json" to "{",
            "invalid-url" to legacyJson(login.copy(url = "file:///private/"))
        ))
        assertTrue(preferences().edit().putInt("wrong-type", 42).commit())
        val original = preferences().all.toMap()

        val migrated = store.migrate(listOf(environment))!!

        assertEquals("legacy", migrated.environmentId)
        assertEquals(1L, migrated.revision)
        assertEquals(4, migrated.urls.size)
        assertEquals(earlier.url, migrated.effectiveUrls()["login"])
        assertEquals(files.url, migrated.effectiveUrls()["files"])
        assertEquals(1, migrated.urls.count { it.configKey == "login" && it.select })
        assertEquals(original, preferences().all)
        assertEquals(migrated, FileStateStore(context, listOf(environment)).read())
    }

    @Test fun completelyInvalidLegacyDataFailsWithoutCreatingNewState() {
        writeLegacy(mapOf("bad" to "not-json"))
        val original = preferences().all.toMap()
        expectFailure { store.migrate(listOf(environment)) }
        assertFalse(stateFile().exists())
        assertEquals(original, preferences().all)
    }

    @Test fun migrationKeepsSelectedRecordWhenLegacyUrlsNormalizeToTheSameAddress() {
        val unselected = login.copy(url = "HTTPS://DEV.EXAMPLE.COM:443", select = false, remark = "旧候选", time = 1)
        val selected = login.copy(remark = "实际选择", time = 3)
        writeLegacy(mapOf("unselected" to legacyJson(unselected), "selected" to legacyJson(selected)))
        val original = preferences().all.toMap()

        val migrated = store.migrate(listOf(environment))!!

        assertEquals(listOf(selected), migrated.urls)
        assertEquals(selected.url, migrated.effectiveUrls()[login.configKey])
        assertEquals(original, preferences().all)
        assertEquals(migrated, FileStateStore(context, listOf(environment)).read())
    }

    @Test fun normalizedDuplicatesDoNotChangeWhichSelectedLegacyAddressWins() {
        val unselected = login.copy(url = login.url.removeSuffix("/"), select = false, time = 1)
        val earlierSelection = login.copy(url = "https://earlier.example.com/", time = 2)
        val laterSelection = login.copy(time = 3)
        writeLegacy(mapOf(
            "unselected" to legacyJson(unselected),
            "earlier" to legacyJson(earlierSelection),
            "later" to legacyJson(laterSelection)
        ))

        val migrated = store.migrate(listOf(environment))!!

        assertEquals(earlierSelection.url, migrated.effectiveUrls()[login.configKey])
        assertEquals(listOf(earlierSelection, laterSelection.copy(select = false)), migrated.urls)
    }

    @Test fun migrationPreservesHostEnvironmentsWithLegacyIds() {
        val defaults = listOf(environment, environment.copy(id = "legacy"), environment.copy(id = "legacy-1"))
        val collisionStore = FileStateStore(context, defaults)
        val old = login.copy(url = "https://old.example.com/")
        writeLegacy(mapOf("old" to legacyJson(old)))
        val originalPreferences = preferences().all.toMap()

        val migrated = collisionStore.migrate(defaults)!!

        assertEquals("legacy-2", migrated.environmentId)
        assertEquals(defaults, migrated.environments.take(defaults.size))
        assertEquals(listOf(old), migrated.urls)
        assertEquals(originalPreferences, preferences().all)
        assertEquals(migrated, FileStateStore(context, defaults).read())
    }

    @Test fun emptyLegacyDataDoesNotCreateState() {
        assertNull(store.migrate(listOf(environment)))
        assertFalse(stateFile().exists())
    }

    @Test fun initializationReadsMigratedStateWithoutReapplyingOldPreferences() {
        writeLegacy(mapOf("old" to legacyJson(login)))
        val migrated = store.read() ?: store.migrate(listOf(environment))!!
        val updated = migrated.copy(revision = migrated.revision + 1, environments = migrated.environments.map {
            if (it.id == "legacy") it.copy(urls = listOf(login.copy(url = "https://updated.example.com/"))) else it
        })
        store.write(updated)
        writeLegacy(mapOf("old" to legacyJson(login.copy(url = "https://obsolete.example.com/"))))

        val reopened = FileStateStore(context, listOf(environment))
        assertEquals(updated, reopened.read() ?: reopened.migrate(listOf(environment)))
    }

    @Test fun clearedConfigurationStaysEmptyAfterReopening() {
        store.write(initial)
        val manager = BaseUrlManager(listOf(environment), initial, store) { it() }
        manager.clear()
        assertTrue(manager.snapshot().urls.isEmpty())
        val reopened = FileStateStore(context, listOf(environment)).read()!!
        assertTrue(reopened.urls.isEmpty())
        assertEquals(initial.revision + 1, reopened.revision)
    }

    @Test fun oldDemoDefaultsRemainInLegacyWithoutRenamingKeysOrSwitchingServers() {
        val search = BaseUrl("searchApiDomain", "https://www.google.com/", true, "google Environment", 1)
        val git = BaseUrl("gitDomain", "https://www.github.com/", true, "github Environment", 2)
        writeLegacy(mapOf("search" to legacyJson(search), "git" to legacyJson(git)))
        val oldPreferences = preferences().all.toMap()
        val migrated = store.migrate(listOf(environment))!!
        assertEquals("legacy", migrated.environmentId)
        assertEquals(mapOf("searchApiDomain" to search.url, "gitDomain" to git.url), migrated.effectiveUrls())
        assertNull(migrated.effectiveUrls()["login"])
        assertEquals(listOf(search, git), migrated.urls)
        assertEquals(listOf(login), migrated.environments.single { it.id == "dev" }.urls)
        assertEquals(oldPreferences, preferences().all)
        val newDefaults = environment.copy(urls = listOf(login.copy(url = "https://new-dev.example.com/")))
        val reopened = FileStateStore(context, listOf(newDefaults)).read()!!
        assertEquals("legacy", reopened.environmentId)
        assertEquals(migrated.effectiveUrls(), reopened.effectiveUrls())
        assertEquals("https://new-dev.example.com/", reopened.environments.single { it.id == "dev" }.urls.single().url)
    }

    @Test fun oldDebugAndReleaseAssetDataMigrateAsLegacyWithoutNewDemoMapping() {
        val releaseRows = listOf(
            BaseUrl("searchApiDomain", "https://www.google.com/", true, "google Environment", 1),
            BaseUrl("githubDomain", "https://github.com/", true, "github Environment", 2)
        )
        val debugRows = listOf(
            releaseRows[0],
            BaseUrl("searchApiDomain", "https://www.bing.com/", false, "bing Environment", 3),
            BaseUrl("gitDomain", "https://www.github.com/", true, "github Environment", 4),
            BaseUrl("gitDomain", "https://www.gitee.com/", false, "gitee Environment", 5)
        )
        fun assertMigrated(rows: List<BaseUrl>) {
            writeLegacy(rows.mapIndexed { index, value -> index.toString() to legacyJson(value) }.toMap())
            val migrated = store.migrate(listOf(environment))!!
            assertEquals("legacy", migrated.environmentId)
            assertEquals(rows, migrated.urls)
            assertEquals(rows.filter { it.select }.associate { it.configKey to it.url }, migrated.effectiveUrls())
            assertNull(migrated.effectiveUrls()["order"])
            assertNull(migrated.effectiveUrls()["login"])
        }
        assertMigrated(releaseRows)

        context.dispose()
        context = IsolatedContext(InstrumentationRegistry.getInstrumentation().targetContext)
        store = FileStateStore(context, listOf(environment))
        assertMigrated(debugRows)
    }

    @Test fun deletedDefaultCandidateDoesNotReturnAfterReopening() {
        val backup = login.copy(url = "https://backup.example.com/", select = false, time = 2)
        val defaults = listOf(environment.copy(urls = listOf(login, backup)))
        FileStateStore(context, defaults).write(initial)

        assertEquals(initial, FileStateStore(context, defaults).read())
    }

    @Test fun updatedDefaultsFollowUntouchedGroupsButRetainUserChoicesAfterReopening() {
        val backup = login.copy(url = "https://backup.example.com/", select = false, remark = "备用", time = 2)
        val before = environment.copy(urls = listOf(login, backup))
        val previousStore = FileStateStore(context, listOf(before))
        previousStore.write(initial.copy(environments = listOf(before)))
        val updated = before.copy(urls = listOf(login.copy(url = "https://new-default.example.com/"), backup))
        val upgradedStore = FileStateStore(context, listOf(updated))
        assertEquals("https://new-default.example.com/", upgradedStore.read()!!.effectiveUrls()["login"])
        previousStore.write(initial.copy(environments = listOf(before.copy(urls = listOf(login.copy(select = false), backup.copy(select = true))))))
        val retained = upgradedStore.read()!!
        assertEquals(backup.url, retained.effectiveUrls()["login"])
        assertFalse(retained.urls.single { it.url == "https://new-default.example.com/" }.select)
        upgradedStore.write(retained)
        assertEquals(retained, FileStateStore(context, listOf(updated)).read())
    }

    private fun stateFile() = File(context.filesDir, "multiurlmanager-state.json")
    private fun preferences() = context.getSharedPreferences("dynamic_base_urls_config", Context.MODE_PRIVATE)
    private fun writeLegacy(values: Map<String, String>) {
        val editor = preferences().edit().clear()
        values.forEach { (key, value) -> editor.putString(key, value) }
        assertTrue(editor.commit())
    }
    private fun legacyJson(value: BaseUrl): String = JsonObject().apply {
        addProperty("configKey", value.configKey)
        addProperty("url", value.url)
        addProperty("select", value.select)
        addProperty("remark", value.remark)
        addProperty("time", value.time)
    }.toString()
    private fun expectFailure(action: () -> Unit) {
        val error = runCatching(action).exceptionOrNull()
        assertNotNull("损坏或不支持的存储必须向调用方报告", error)
    }

    private class IsolatedContext(base: Context) : ContextWrapper(base) {
        private val namespace = "state-store-test-${UUID.randomUUID()}"
        private val directory = File(base.cacheDir, namespace).apply { check(mkdirs()) }
        private val preferenceNames = mutableSetOf<String>()
        override fun getFilesDir(): File = directory
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolatedName = "$namespace-$name"
            preferenceNames.add(isolatedName)
            return baseContext.getSharedPreferences(isolatedName, mode)
        }
        fun dispose() {
            preferenceNames.forEach { baseContext.deleteSharedPreferences(it) }
            check(directory.deleteRecursively())
        }
    }
}
