package com.logan.multiurlmanager.library

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonParser
import com.logan.multiurlmanager.library.bean.BaseUrl
import com.logan.multiurlmanager.library.internal.FileStateStore
import com.logan.multiurlmanager.library.internal.StateCodec
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DefaultUpgradeStoreTest {
    private val url = BaseUrl("order", "https://old.example.com/", true, time = 1)
    private val before = UrlEnvironment("dev", "Development", listOf(url), setOf("order"))
    private val initial = UrlSnapshot(7, "dev", listOf(before))

    @Test fun changedDefaultsArePersistedBeforeReadReturnsAndDoNotRepeat() = isolated { context ->
        FileStateStore(context, listOf(before)).write(initial)
        val next = before.copy(urls = listOf(url.copy(url = "https://new.example.com/")))
        val upgraded = FileStateStore(context, listOf(next)).read()!!
        assertEquals(8L, upgraded.revision)
        assertEquals("https://new.example.com/", upgraded.effectiveUrls()["order"])
        val stored = File(context.filesDir, "multiurlmanager-state.json").readText()
        assertEquals(upgraded, StateCodec.decode(stored))
        assertEquals(upgraded, FileStateStore(context, listOf(next)).read())
        assertEquals(stored, File(context.filesDir, "multiurlmanager-state.json").readText())
        val manager = BaseUrlManager(listOf(next), upgraded, FileStateStore(context, listOf(next))) { it() }
        assertEquals(SaveResult.Reason.CONFLICT, (manager.save(initial) as SaveResult.Failure).reason)
    }

    @Test fun onlyDefaultBaselineChangesWithoutInventingStateRevision() = isolated { context ->
        val overridden = initial.copy(environments = listOf(before.copy(urls = listOf(url.copy(remark = "my override")))))
        FileStateStore(context, listOf(before)).write(overridden)
        val next = before.copy(urls = listOf(url.copy(remark = "updated bundled remark")))
        val upgraded = FileStateStore(context, listOf(next)).read()!!
        assertEquals(overridden, upgraded)
        val path = File(context.filesDir, "multiurlmanager-state.json")
        val saved = path.readText()
        val root = JsonParser.parseString(saved).asJsonObject
        assertEquals(next.urls.single().remark, StateCodec.decode(root.get("defaults").toString()).urls.single().remark)
        assertEquals(upgraded, FileStateStore(context, listOf(next)).read())
        assertEquals(saved, path.readText())
    }

    @Test fun generatedDefaultTimestampsDoNotCauseWritesOrRevisionChanges() = isolated { context ->
        FileStateStore(context, listOf(before)).write(initial)
        val path = File(context.filesDir, "multiurlmanager-state.json")
        val saved = path.readText()
        val timestampOnly = before.copy(urls = listOf(url.copy(time = 999999)))
        assertEquals(initial, FileStateStore(context, listOf(timestampOnly)).read())
        assertEquals(saved, path.readText())
    }

    @Test fun failedDefaultUpgradePreservesFileAndBlocksWritesUntilSuccessfulReload() = isolated { context ->
        FileStateStore(context, listOf(before)).write(initial)
        val path = File(context.filesDir, "multiurlmanager-state.json")
        val originalBytes = path.readBytes()
        val next = before.copy(urls = listOf(url.copy(url = "https://new.example.com/")))
        val upgradedStore = FileStateStore(context, listOf(next))
        val manager = BaseUrlManager(listOf(next), initial, upgradedStore) { it() }

        // 同时禁止原文件写入与目录中新建/重命名，覆盖旧版及新版 AtomicFile 的写入策略。
        check(path.setWritable(false, false))
        check(context.filesDir.setWritable(false, false))
        try {
            assertThrows(Exception::class.java) { upgradedStore.read() }
            assertArrayEquals(originalBytes, path.readBytes())
            assertThrows(Exception::class.java) { manager.refreshData() }
            assertEquals(initial, manager.snapshot())
            assertEquals(SaveResult.Reason.STORAGE, (manager.save(initial) as SaveResult.Failure).reason)
            assertArrayEquals(originalBytes, path.readBytes())
        } finally {
            check(context.filesDir.setWritable(true, true))
            check(path.setWritable(true, true))
        }

        manager.refreshData()
        assertEquals(8L, manager.snapshot().revision)
        assertEquals("https://new.example.com/", manager.getUrl("order"))
        assertEquals(SaveResult.Reason.CONFLICT, (manager.save(initial) as SaveResult.Failure).reason)
        assertTrue(manager.save(manager.snapshot()) is SaveResult.Success)
    }

    private fun isolated(test: (Context) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "default-upgrade-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = directory
        }
        try {
            test(context)
        } finally {
            check(directory.deleteRecursively())
        }
    }
}
