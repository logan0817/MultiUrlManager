package com.logan.multiurlmanager.library

import com.google.gson.JsonParser
import com.logan.multiurlmanager.library.bean.BaseUrl
import com.logan.multiurlmanager.library.internal.DefaultMerger
import com.logan.multiurlmanager.library.internal.StateStore
import org.junit.Assert.*
import org.junit.Test

class ConfigurationTransferTest {
    private val primary = BaseUrl("order", "https://local.example.com/", true, "local", 1)
    private val other = BaseUrl("login", "https://login.example.com/", true, time = 2)
    private val local = UrlEnvironment("dev", "Local development", listOf(primary, other), setOf("order"))
    private val initial = UrlSnapshot(7, "dev", listOf(local))

    private class MemoryStore : StateStore {
        var saved: UrlSnapshot? = null
        override fun read() = saved
        override fun write(state: UrlSnapshot) { saved = state }
    }

    private fun manager(state: UrlSnapshot = initial, definitions: List<UrlEnvironment> = listOf(local)): BaseUrlManager =
        BaseUrlManager(definitions, state, MemoryStore()) { it() }

    private fun json(environments: String, defaultId: String = "dev") =
        """{"schemaVersion":1,"defaultEnvironment":"$defaultId","environments":[$environments]}"""

    private fun environment(
        id: String = "dev",
        urls: String = """{"configKey":"order","url":"https://remote.example.com/","select":true,"remark":"remote"}""",
        required: String = "[]"
    ) = """{"id":"$id","name":"Remote name","requiredKeys":$required,"urls":[$urls]}"""

    @Test fun exportRoundTripRemovesDeviceMetadataAndPreviewDoesNotCommit() {
        val manager = manager()
        val exported = manager.exportConfiguration(setOf("dev"))
        val root = JsonParser.parseString(exported).asJsonObject
        assertFalse(root.has("revision"))
        assertFalse(root.has("environmentId"))
        assertFalse(root.has("defaults"))
        assertFalse(root.getAsJsonArray("environments")[0].asJsonObject.getAsJsonArray("urls")[0].asJsonObject.has("time"))
        val ready = manager.previewImport(exported, manager.snapshot(), setOf("dev"), ImportMode.MERGE) as ImportResult.Ready
        assertEquals(initial, ready.draft)
        assertTrue(ready.changes.isEmpty())
        assertEquals(initial, manager.snapshot())
        assertTrue(manager.inspectImport(exported) is ImportInspectionResult.Success)
    }

    @Test fun mergeKeepsLocalNameRemarksSelectionsAndAddsUnselectedCandidates() {
        val manager = manager()
        val incoming = json(environment(urls = """{"configKey":"order","url":"${primary.url}","remark":"changed","select":false},
            {"configKey":"order","url":"https://remote.example.com/","select":true}"""))
        val ready = manager.previewImport(incoming, manager.snapshot(), setOf("dev"), ImportMode.MERGE) as ImportResult.Ready
        assertEquals(local.name, ready.draft.environments.single().name)
        assertEquals(primary, ready.draft.urls.first())
        assertFalse(ready.draft.urls.last().select)
        assertTrue(ready.changes.any { it.type == ImportChangeType.LOCAL_PRESERVED })
        assertTrue(ready.changes.any { it.type == ImportChangeType.CANDIDATE_ADDED })
        assertEquals(initial, manager.snapshot())
    }

    @Test fun mergeDoesNotRestoreSelectionForDeletedOptionalGroup() {
        val optional = local.copy(requiredKeys = emptySet())
        val state = initial.copy(environments = listOf(optional.copy(urls = listOf(other))))
        val manager = manager(state, listOf(optional))
        val ready = manager.previewImport(json(environment()), state, setOf("dev"), ImportMode.MERGE) as ImportResult.Ready
        assertFalse(ready.draft.urls.single { it.configKey == "order" }.select)
        assertTrue(manager.knownBusinessKeys().contains("order"))
    }

    @Test fun mergeKeepsExistingUnselectedGroupUnselected() {
        val optional = local.copy(requiredKeys = emptySet(), urls = listOf(primary.copy(select = false), other))
        val state = initial.copy(environments = listOf(optional))
        val manager = manager(state, listOf(optional))
        val ready = manager.previewImport(json(environment()), state, setOf("dev"), ImportMode.MERGE) as ImportResult.Ready
        assertTrue(ready.draft.urls.filter { it.configKey == "order" }.none { it.select })
    }

    @Test fun replacementPreservesRequiredKeysAndRejectsIncompleteSelection() {
        val manager = manager()
        val missing = json(environment(urls = """{"configKey":"login","url":"https://new-login.example.com/","select":true}"""))
        val failure = manager.previewImport(missing, initial, setOf("dev"), ImportMode.REPLACE_ENVIRONMENTS) as ImportResult.Failure
        assertEquals(ImportFailureReason.VALIDATION, failure.reason)
        val ready = manager.previewImport(json(environment()), initial, setOf("dev"), ImportMode.REPLACE_ENVIRONMENTS) as ImportResult.Ready
        assertEquals(local.requiredKeys, ready.draft.environments.single().requiredKeys)
        assertEquals(local.name, ready.draft.environments.single().name)
        assertEquals("https://remote.example.com/", ready.draft.effectiveUrls()["order"])
        assertTrue(ready.changes.any { it.type == ImportChangeType.CANDIDATE_REMOVED })
        assertTrue(ready.changes.any { it.type == ImportChangeType.SELECTION_CHANGED })
    }

    @Test fun replacementCannotLowerRequiredKeysThroughTamperedDraft() {
        val manager = manager()
        val tampered = initial.copy(environments = listOf(local.copy(requiredKeys = emptySet())))
        val failure = manager.previewImport(json(environment()), tampered, setOf("dev"), ImportMode.REPLACE_ENVIRONMENTS) as ImportResult.Failure
        assertEquals(ImportFailureReason.VALIDATION, failure.reason)
    }

    @Test fun newEnvironmentUsesKnownKeysAndDoesNotSwitchCurrentEnvironment() {
        val manager = manager()
        val ready = manager.previewImport(json(environment("shared"), "shared"), initial, setOf("shared"), ImportMode.MERGE) as ImportResult.Ready
        assertEquals("dev", ready.draft.environmentId)
        assertEquals(initial.revision, ready.draft.revision)
        assertEquals(local, ready.draft.environments.first())
        assertEquals("https://remote.example.com/", ready.draft.environments.last().urls.single().url)
        assertTrue(ready.draft.environments.last().urls.single().select)
        assertTrue(ready.changes.any { it.type == ImportChangeType.ENVIRONMENT_ADDED })
    }

    @Test fun unknownBusinessCannotDeclareItselfThroughFileOrDraft() {
        val manager = manager()
        val input = json(environment(urls = """{"configKey":"unknown","url":"https://unknown.example.com/","select":true}"""))
        val tampered = initial.copy(environments = listOf(local.copy(urls = local.urls + BaseUrl("unknown", "https://known-in-draft.example.com/"))))
        assertEquals(ImportFailureReason.UNKNOWN_BUSINESS,
            (manager.previewImport(input, tampered, setOf("dev"), ImportMode.MERGE) as ImportResult.Failure).reason)
        assertEquals(ImportFailureReason.UNKNOWN_BUSINESS, (manager.inspectImport(input) as ImportInspectionResult.Failure).reason)
    }

    @Test fun validFileCannotCarryAnUndeclaredBusinessFromBaseDraftIntoPreview() {
        val manager = manager()
        val tampered = initial.copy(environments = listOf(local.copy(
            urls = local.urls + BaseUrl("unknown", "https://draft.example.com/", true)
        )))
        ImportMode.values().forEach { mode ->
            val failure = manager.previewImport(json(environment()), tampered, setOf("dev"), mode) as ImportResult.Failure
            assertEquals(ImportFailureReason.UNKNOWN_BUSINESS, failure.reason)
        }
        val unknownRequired = initial.copy(environments = initial.environments + UrlEnvironment(
            "draft-only", "Draft only", emptyList(), setOf("unknown")
        ))
        val failure = manager.previewImport(json(environment()), unknownRequired, setOf("dev"), ImportMode.MERGE) as ImportResult.Failure
        assertEquals(ImportFailureReason.UNKNOWN_BUSINESS, failure.reason)
        assertEquals(initial, manager.snapshot())
    }

    @Test fun repeatedReplacementKeepsTimestampsAndDoesNotCreateAnotherRevision() {
        val manager = manager()
        val incoming = json(environment(urls = """{"configKey":"order","url":"${primary.url}","select":true,"remark":"new remark","time":99}"""))
        val first = manager.previewImport(incoming, initial, setOf("dev"), ImportMode.REPLACE_ENVIRONMENTS) as ImportResult.Ready
        assertEquals(primary.time, first.draft.urls.single().time)
        val saved = manager.save(first.draft) as SaveResult.Success
        assertTrue(saved.changed)
        val repeated = manager.previewImport(incoming, saved.snapshot, setOf("dev"), ImportMode.REPLACE_ENVIRONMENTS) as ImportResult.Ready
        assertEquals(saved.snapshot, repeated.draft)
        assertTrue(repeated.changes.isEmpty())
        val noChange = manager.save(repeated.draft) as SaveResult.Success
        assertFalse(noChange.changed)
        assertEquals(saved.snapshot.revision, noChange.snapshot.revision)
    }

    @Test fun staleDraftUnknownSelectionAndMalformedDocumentsAreRejectedWithoutMutation() {
        val manager = manager()
        assertEquals(ImportFailureReason.CONFLICT,
            (manager.previewImport(json(environment()), initial.copy(revision = 6), setOf("dev"), ImportMode.MERGE) as ImportResult.Failure).reason)
        assertTrue(manager.previewImport(json(environment()), initial, setOf("absent"), ImportMode.MERGE) is ImportResult.Failure)
        listOf("{}", "{", """{"dev":[]}""", json(environment()).replace("true", "\"tru\""),
            json(environment()).replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"revision\":7")).forEach {
            assertTrue(manager.inspectImport(it) is ImportInspectionResult.Failure)
        }
        assertEquals(initial, manager.snapshot())
    }

    @Test fun byteAndEnvironmentLimitsAreWholeOperationFailures() {
        val manager = manager()
        val large = json(environment(urls = """{"configKey":"order","url":"https://remote.example.com/","remark":"${"中".repeat(90000)}"}"""))
        assertEquals(ImportFailureReason.LIMIT, (manager.inspectImport(large) as ImportInspectionResult.Failure).reason)
        val many = json((0..32).joinToString(",") { environment("e$it") }, "e0")
        assertEquals(ImportFailureReason.LIMIT, (manager.inspectImport(many) as ImportInspectionResult.Failure).reason)
        assertEquals(initial, manager.snapshot())
    }

    @Test fun countLimitAlsoAppliesToMergedDraft() {
        val environments = (0..31).map { local.copy(id = "e$it") }
        val state = UrlSnapshot(0, "e0", environments)
        val manager = manager(state, environments)
        val result = manager.previewImport(json(environment("extra"), "extra"), state, setOf("extra"), ImportMode.MERGE) as ImportResult.Failure
        assertEquals(ImportFailureReason.LIMIT, result.reason)
        assertEquals(state, manager.snapshot())
    }

    @Test fun candidateLimitRejectsBothIncomingFileAndMergedResult() {
        val reader = manager()
        val tooMany = (0..1000).joinToString(",") {
            """{"configKey":"order","url":"https://candidate$it.example/"}"""
        }
        assertEquals(ImportFailureReason.LIMIT,
            (reader.inspectImport(json(environment(urls = tooMany))) as ImportInspectionResult.Failure).reason)

        val existing = local.copy(urls = (0..998).map {
            BaseUrl("order", "https://existing$it.example/", select = it == 0, time = 1)
        })
        val state = initial.copy(environments = listOf(existing))
        val receiver = manager(state)
        val incoming = json(environment(urls = """{"configKey":"order","url":"https://extra1.example/"},
            {"configKey":"order","url":"https://extra2.example/"}"""))
        val failure = receiver.previewImport(incoming, state, setOf("dev"), ImportMode.MERGE) as ImportResult.Failure
        assertEquals(ImportFailureReason.LIMIT, failure.reason)
        assertEquals(state, receiver.snapshot())
    }

    @Test fun fixedModeRejectsImportAndOnlyExportsVisibleEnvironment() {
        val hidden = local.copy(id = "hidden", urls = listOf(BaseUrl("hidden-key", "https://hidden.example/", true)), requiredKeys = emptySet())
        val manager = manager(initial, listOf(local, hidden))
        BaseUrlManager::class.java.getDeclaredField("fixedEnvironmentId").apply {
            isAccessible = true
            set(manager, "dev")
        }
        assertFalse(manager.knownBusinessKeys().contains("hidden-key"))
        assertEquals(ImportFailureReason.READ_ONLY,
            (manager.inspectImport(json(environment())) as ImportInspectionResult.Failure).reason)
        assertEquals(ImportFailureReason.READ_ONLY,
            (manager.previewImport(json(environment()), initial, setOf("dev"), ImportMode.MERGE) as ImportResult.Failure).reason)
        assertThrows(IllegalArgumentException::class.java) { manager.exportConfiguration(setOf("hidden")) }
        val root = JsonParser.parseString(manager.exportConfiguration(setOf("dev"))).asJsonObject
        assertEquals(1, root.getAsJsonArray("environments").size())
        assertEquals("dev", root.getAsJsonArray("environments")[0].asJsonObject.get("id").asString)
        assertEquals(initial, manager.snapshot())
    }

    @Test fun defaultsUpgradeChangesRevisionAndRejectsOldDraft() {
        val updated = local.copy(urls = listOf(primary.copy(url = "https://new-default.example.com/"), other))
        val loaded = DefaultMerger.merge(initial, listOf(local), listOf(updated))
        assertEquals(initial.revision + 1, loaded.revision)
        val manager = manager(loaded, listOf(updated))
        assertEquals(SaveResult.Reason.CONFLICT, (manager.save(initial) as SaveResult.Failure).reason)
        assertEquals(loaded, DefaultMerger.merge(loaded, listOf(updated), listOf(updated)))
        val timestampOnly = local.copy(urls = local.urls.map { it.copy(time = 99) })
        assertEquals(initial, DefaultMerger.merge(initial, listOf(local), listOf(timestampOnly)))
    }
}
