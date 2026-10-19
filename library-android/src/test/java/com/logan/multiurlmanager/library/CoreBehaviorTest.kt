package com.logan.multiurlmanager.library

import com.logan.multiurlmanager.library.bean.BaseUrl
import com.logan.multiurlmanager.library.internal.StateCodec
import com.logan.multiurlmanager.library.internal.StateStore
import com.logan.multiurlmanager.library.internal.UrlRules
import org.junit.Assert.*
import org.junit.Test

class CoreBehaviorTest {
    private val first = BaseUrl("login", "https://dev.example.com/", true, time = 1)
    private val second = BaseUrl("login", "https://test.example.com/", false, time = 2)
    private fun env() = UrlEnvironment("dev", "Development", listOf(first, second))

    @Test fun selectingOneKeyPreservesOtherKeysAndDoesNotMutateInput() {
        val input = listOf(first, second, BaseUrl("files", "https://files.example.com/", true))
        val output = UrlRules.upsert(input, second.copy(select = true))
        assertEquals(second.url, output.single { it.configKey == "login" && it.select }.url)
        assertTrue(output.single { it.configKey == "files" }.select)
        assertTrue(first.select)
    }
    @Test fun addingExistingUnselectedCandidateDoesNotDeselectIt() {
        assertTrue(UrlRules.upsert(listOf(first), first.copy(select = false)).single().select)
    }
    @Test fun bulkMultipleSelectionIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            UrlRules.validate(env().copy(urls = listOf(first, second.copy(select = true))))
        }
    }
    @Test fun urlsAreValidatedAndNormalized() {
        assertEquals("http://[::1]:8080/api/", UrlRules.normalize(BaseUrl(" x ", "http://[::1]:8080/api")).url)
        listOf("ftp://a/", "https:///", "https://user:pass@a/", "https://a/?q=1", "https://a/#x", "https://a:99999/", "https://a/%ZZ").forEach {
            assertThrows(Exception::class.java) { UrlRules.normalize(BaseUrl("key", it)) }
        }
    }
    @Test fun equivalentHostsAndDefaultPortsNormalizeToOneAddress() {
        assertEquals("https://example.com/api/", UrlRules.normalize(BaseUrl("key", "HTTPS://EXAMPLE.COM:443/api")).url)
    }
    @Test fun requiredKeyMustHaveSelection() {
        assertThrows(IllegalArgumentException::class.java) {
            UrlRules.validate(env().copy(urls = listOf(second), requiredKeys = setOf("login")))
        }
    }
    @Test fun snapshotRoundTripAndUnknownSchema() {
        val state = UrlSnapshot(3, "dev", listOf(env()))
        assertEquals(state, StateCodec.decode(StateCodec.encode(state)))
        assertThrows(IllegalArgumentException::class.java) { StateCodec.decode(StateCodec.encode(state).replace("\"schemaVersion\":1", "\"schemaVersion\":9")) }
    }
    @Test fun failedWriteDoesNotPublishStateAndStaleDraftConflicts() {
        val initial = UrlSnapshot(0, "dev", listOf(env()))
        val store = MemoryStore().apply { saved = initial }
        val manager = BaseUrlManager(listOf(env()), initial, store) { it() }
        store.fail = true
        assertTrue(manager.save(initial.copy(environments = listOf(env().copy(urls = listOf(second))))) is SaveResult.Failure)
        assertEquals(initial, manager.snapshot())
        store.fail = false
        manager.refreshData()
        val changed = initial.copy(environments = listOf(env().copy(urls = listOf(first))))
        assertTrue(manager.save(changed) is SaveResult.Success)
        assertEquals(SaveResult.Reason.CONFLICT, (manager.save(initial) as SaveResult.Failure).reason)
    }
    @Test fun snapshotMutationCannotChangeManagerAndAliasedReplaceKeepsData() {
        val manager = BaseUrlManager(listOf(env()), UrlSnapshot(0, "dev", listOf(env())), MemoryStore()) { it() }
        manager.snapshot().urls.first().select = false
        assertEquals(first.url, manager.getUrl("login"))
        manager.updateBaseURL(manager.dynamicUrls)
        assertEquals(2, manager.count)
    }
    @Test fun environmentChangeEmitsOneCompleteSnapshot() {
        val test = UrlEnvironment("test", "Test", listOf(first.copy(url = "https://test.example.com/")))
        val manager = BaseUrlManager(listOf(env(), test), UrlSnapshot(0, "dev", listOf(env(), test)), MemoryStore()) { it() }
        var calls = 0
        manager.addListener { old, current ->
            calls++
            assertEquals("dev", old.environmentId)
            assertEquals("test", current.environmentId)
            assertEquals("https://test.example.com/", current.effectiveUrls()["login"])
        }
        assertTrue(manager.switchEnvironment("test") is SaveResult.Success)
        assertEquals(1, calls)
        manager.switchEnvironment("test")
        assertEquals(1, calls)
    }
    @Test fun upgradedDefaultsFollowUnchangedKeysAndPreserveOverrides() {
        val original = env()
        val next = original.copy(urls = listOf(first.copy(url = "https://new-default.example.com/"), second,
            BaseUrl("files", "https://files.example.com/", true)))
        val untouched = UrlSnapshot(0, "dev", listOf(original))
        val merged = com.logan.multiurlmanager.library.internal.DefaultMerger.merge(untouched, listOf(original), listOf(next))
        assertEquals("https://new-default.example.com/", merged.effectiveUrls()["login"])
        assertEquals("https://files.example.com/", merged.effectiveUrls()["files"])
        val overridden = untouched.copy(environments = listOf(original.copy(urls = listOf(second.copy(select = true)))))
        val retained = com.logan.multiurlmanager.library.internal.DefaultMerger.merge(overridden, listOf(original), listOf(next))
        assertEquals(second.url, retained.effectiveUrls()["login"])
    }
    @Test fun clearPublishesOneEmptyMappingAndCannotResurrectDefaults() {
        val store = MemoryStore()
        val manager = BaseUrlManager(listOf(env()), UrlSnapshot(0, "dev", listOf(env())), store) { it() }
        var notifications = 0
        manager.addListener { _, next -> notifications++; assertTrue(next.urls.isEmpty()) }
        manager.clear()
        manager.refreshData()
        assertTrue(manager.snapshot().urls.isEmpty())
        assertEquals(1, notifications)
    }
    @Test fun deletedDefaultCandidateStaysDeletedWhileNewCandidatesAreAdded() {
        val original = env()
        val deleted = UrlSnapshot(2, original.id, listOf(original.copy(urls = listOf(first))))
        val merger = com.logan.multiurlmanager.library.internal.DefaultMerger
        assertEquals(deleted, merger.merge(deleted, listOf(original), listOf(original)))

        val added = first.copy(url = "https://new.example.com/", time = 3)
        val updated = original.copy(urls = listOf(first.copy(select = false), second, added))
        val upgraded = merger.merge(deleted, listOf(original), listOf(updated))
        assertEquals(listOf(first, added.copy(select = false)), upgraded.urls)
        assertEquals(first.url, upgraded.effectiveUrls()[first.configKey])
        assertEquals(upgraded, merger.merge(upgraded, listOf(updated), listOf(updated)))
    }
    @Test fun restoreAfterClearingMultipleRequiredEnvironmentsIsPossible() {
        val dev = env().copy(requiredKeys = setOf("login"))
        val test = dev.copy(id = "test", name = "Test")
        val manager = BaseUrlManager(listOf(dev, test), UrlSnapshot(0, "dev", listOf(dev, test)), MemoryStore()) { it() }
        manager.clear()
        assertTrue(manager.restoreDefaults() is SaveResult.Success)
        assertEquals(first.url, manager.getUrl("login"))
        assertTrue(manager.switchEnvironment("test") is SaveResult.Failure)
    }
    @Test fun selectingKeepsCandidateAndGroupOrderAndTimestamp() {
        val input = listOf(first, second, BaseUrl("files", "https://files.example.com/", true, time = 3))
        val changed = UrlRules.upsert(input, second.copy(select = true, time = 999))
        assertEquals(input.map { it.configKey to it.url }, changed.map { it.configKey to it.url })
        assertEquals(input.map { it.time }, changed.map { it.time })
        assertEquals(listOf("login", "files"), changed.groupBy { it.configKey }.keys.toList())
    }
    @Test fun managerSelectionKeepsRemarkAndRepeatedSelectionIsNoOp() {
        val withRemark = env().copy(urls = listOf(first.copy(remark = "primary"), second.copy(remark = "backup")))
        val manager = BaseUrlManager(listOf(withRemark), UrlSnapshot(0, "dev", listOf(withRemark)), MemoryStore()) { it() }
        val selected = manager.select("login", second.url) as SaveResult.Success
        assertTrue(selected.changed)
        assertEquals("backup", selected.snapshot.urls[1].remark)
        assertEquals(second.time, selected.snapshot.urls[1].time)
        val again = manager.select(" login ", second.url.removeSuffix("/")) as SaveResult.Success
        assertFalse(again.changed)
        assertEquals(selected.snapshot.revision, again.snapshot.revision)
    }

    @Test fun modifyingReturnedDefaultRequiredKeysCannotChangeValidation() {
        val required = setOf("login", "order")
        val definition = env().copy(
            requiredKeys = required,
            urls = listOf(first, BaseUrl("order", "https://order.example.com/", true))
        )
        val manager = BaseUrlManager(
            listOf(definition), UrlSnapshot(0, "dev", listOf(definition)), MemoryStore()
        ) { it() }
        (manager.defaultEnvironment("dev").requiredKeys as MutableSet<String>).clear()
        assertEquals(required, manager.defaultEnvironment("dev").requiredKeys)
        val draft = manager.snapshot()
        val missing = draft.copy(environments = listOf(definition.copy(urls = listOf(first))))
        assertEquals(SaveResult.Reason.VALIDATION, (manager.save(missing) as SaveResult.Failure).reason)
        assertTrue(manager.save(draft) is SaveResult.Success)
    }
    @Test fun asynchronousReloadOnlyUnlocksSavingAfterSuccessfulRead() {
        val initial = UrlSnapshot(0, "dev", listOf(env()))
        val store = MemoryStore().apply { saved = initial; fail = true }
        val manager = BaseUrlManager(listOf(env()), initial, store) { it() }
        assertEquals(SaveResult.Reason.STORAGE, (manager.select("login", second.url) as SaveResult.Failure).reason)
        store.fail = false
        store.failRead = true
        fun reload(): Result<UrlSnapshot> {
            val latch = java.util.concurrent.CountDownLatch(1)
            var result: Result<UrlSnapshot>? = null
            manager.reloadAsync { result = it; latch.countDown() }
            assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS))
            return requireNotNull(result)
        }
        assertTrue(reload().isFailure)
        assertEquals(initial, manager.snapshot())
        assertEquals(SaveResult.Reason.STORAGE, (manager.select("login", second.url) as SaveResult.Failure).reason)
        store.failRead = false
        assertEquals(initial, reload().getOrThrow())
        assertTrue(manager.select("login", second.url) is SaveResult.Success)
    }
    private class MemoryStore : StateStore {
        var fail = false
        var failRead = false
        var saved: UrlSnapshot? = null
        override fun read(): UrlSnapshot? { if (failRead) error("injected read failure"); return saved }
        override fun write(state: UrlSnapshot) { if (fail) error("injected failure"); saved = state }
    }
}
