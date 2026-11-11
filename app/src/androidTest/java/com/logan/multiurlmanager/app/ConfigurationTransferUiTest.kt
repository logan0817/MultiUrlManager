package com.logan.multiurlmanager.app

import android.content.Intent
import android.os.SystemClock
import android.widget.Spinner
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.logan.multiurlmanager.library.BaseUrlManager
import com.logan.multiurlmanager.library.BaseUrlManagerActivity
import com.logan.multiurlmanager.library.ImportInspectionResult
import com.logan.multiurlmanager.library.SaveResult
import com.logan.multiurlmanager.library.UrlEnvironment
import com.logan.multiurlmanager.library.UrlSnapshot
import com.logan.multiurlmanager.library.bean.BaseUrl
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import com.logan.multiurlmanager.library.R as LibraryR

@RunWith(AndroidJUnit4::class)
class ConfigurationTransferUiTest {
    private val manager get() = requireNotNull(BaseUrlManager.instance)
    private lateinit var original: UrlSnapshot

    @Before fun prepare() {
        original = manager.snapshot()
        assertTrue(manager.save(original.copy(
            environmentId = "dev",
            environments = listOf("dev", "test", "production").map(manager::defaultEnvironment)
        )) is SaveResult.Success)
    }

    @After fun restore() {
        if (::original.isInitialized) {
            assertTrue(manager.save(original.copy(revision = manager.snapshot().revision)) is SaveResult.Success)
        }
    }

    @Test fun importedPreviewOnlyChangesDraftAndSurvivesRecreation() {
        val committed = manager.snapshot()
        ActivityScenario.launch(BaseUrlManagerActivity::class.java).use { scenario ->
            waitForWindow()
            inspectFile(scenario, configuration())
            onView(withText(LibraryR.string.base_url_import_merge)).inRoot(isDialog()).perform(click())
            waitForPreview(scenario)
            onView(withText(LibraryR.string.base_url_import_apply)).inRoot(isDialog()).perform(click())
            onView(withText(android.R.string.ok)).inRoot(isDialog()).perform(click())
            assertEquals(committed, manager.snapshot())
            scenario.recreate()
            scenario.onActivity { activity ->
                val draft = state(activity).draft!!
                assertTrue(draft.urls.any { it.url == "https://team.example.com/" })
                assertEquals(committed.environmentId, draft.environmentId)
                activity.findViewById<android.view.View>(LibraryR.id.btnSave).performClick()
            }
            eventually { assertTrue(manager.snapshot().urls.any { it.url == "https://team.example.com/" }) }
        }
    }

    @Test fun outsideSaveInvalidatesPreviewWithoutReplacingDraft() {
        ActivityScenario.launch(BaseUrlManagerActivity::class.java).use { scenario ->
            waitForWindow()
            inspectFile(scenario, configuration())
            onView(withText(LibraryR.string.base_url_import_merge)).inRoot(isDialog()).perform(click())
            waitForPreview(scenario)
            val old = manager.snapshot()
            assertTrue(manager.save(old.copy(environments = old.environments.map { environment ->
                if (environment.id == "dev") environment.copy(name = "Changed externally") else environment
            })) is SaveResult.Success)
            onView(withText(LibraryR.string.base_url_import_apply)).inRoot(isDialog()).perform(click())
            scenario.onActivity { assertFalse(state(it).draft!!.urls.any { url -> url.url == "https://team.example.com/" }) }
            assertEquals("Changed externally", manager.snapshot().environments.first { it.id == "dev" }.name)
        }
    }

    @Test fun oversizedRestorationRejectsImportAndKeepsDraft() {
        val committed = manager.snapshot()
        ActivityScenario.launch(BaseUrlManagerActivity::class.java).use { scenario ->
            waitForWindow()
            inspectFile(scenario, configuration("a".repeat(140_000)))
            onView(withText(LibraryR.string.base_url_import_merge)).inRoot(isDialog()).perform(click())
            waitForPreview(scenario)
            onView(withText(LibraryR.string.base_url_import_apply)).inRoot(isDialog()).perform(click())
            scenario.onActivity { assertEquals(committed, state(it).draft) }
            assertEquals(committed, manager.snapshot())
        }
    }

    @Test fun importBudgetAlsoCoversSavedConfigurationReopening() {
        val committed = manager.snapshot()
        ActivityScenario.launch(BaseUrlManagerActivity::class.java).use { scenario ->
            waitForWindow()
            // 当前 original 很小，90k 字符草稿单独可放入；保存后 original + draft 超出预算。
            inspectFile(scenario, configuration("a".repeat(90_000)))
            onView(withText(LibraryR.string.base_url_import_merge)).inRoot(isDialog()).perform(click())
            waitForPreview(scenario)
            onView(withText(LibraryR.string.base_url_import_apply)).inRoot(isDialog()).perform(click())
            scenario.onActivity { assertEquals(committed, state(it).draft) }
            assertEquals(committed, manager.snapshot())
        }
    }

    @Test fun importCannotBypassHostUrlRule() {
        val committed = manager.snapshot()
        val intent = Intent(InstrumentationRegistry.getInstrumentation().targetContext, BaseUrlManagerActivity::class.java)
            .putExtra(BaseUrlManagerActivity.KEY_REGEX, "https://allowed\\.example\\.com/.*")
        ActivityScenario.launch<BaseUrlManagerActivity>(intent).use { scenario ->
            waitForWindow()
            inspectFile(scenario, configuration())
            onView(withText(LibraryR.string.base_url_import_merge)).inRoot(isDialog()).perform(click())
            eventually {
                scenario.onActivity { activity ->
                    val dialogs = BaseUrlManagerActivity::class.java.getDeclaredField("dialogs").apply { isAccessible = true }
                        .get(activity) as Set<*>
                    assertTrue("显示的提示：${dialogMessages(activity)}", dialogs.filterIsInstance<androidx.appcompat.app.AlertDialog>().any { dialog ->
                        dialog.findViewById<android.widget.TextView>(android.R.id.message)?.text
                            ?.contains(activity.getString(LibraryR.string.base_url_import_custom_rule)) == true
                    })
                }
            }
            scenario.onActivity { assertEquals(committed, state(it).draft) }
            assertEquals(committed, manager.snapshot())
            waitForWindow()
            onView(withText(android.R.string.ok)).inRoot(isDialog()).perform(click())
            waitForWindow()
            onView(withText(LibraryR.string.base_url_import_merge)).inRoot(isDialog())
                .check(androidx.test.espresso.assertion.ViewAssertions.matches(
                    androidx.test.espresso.matcher.ViewMatchers.isDisplayed()))
        }
    }

    @Test fun cancelPreviewKeepsDraftAndCommittedConfiguration() {
        val committed = manager.snapshot()
        ActivityScenario.launch(BaseUrlManagerActivity::class.java).use { scenario ->
            waitForWindow()
            inspectFile(scenario, configuration())
            onView(withText(LibraryR.string.base_url_import_merge)).inRoot(isDialog()).perform(click())
            waitForPreview(scenario)
            onView(withText(android.R.string.cancel)).inRoot(isDialog()).perform(click())
            scenario.onActivity { assertEquals(committed, state(it).draft) }
            assertEquals(committed, manager.snapshot())
        }
    }

    @Test fun backCancelsEnvironmentSelectionAndAllowsEditing() {
        val committed = manager.snapshot()
        ActivityScenario.launch(BaseUrlManagerActivity::class.java).use { scenario ->
            waitForWindow()
            inspectFile(scenario, configuration())
            androidx.test.espresso.Espresso.pressBack()
            waitForWindow()
            scenario.onActivity { activity ->
                val transfer = requireNotNull(BaseUrlManagerActivity::class.java.getDeclaredMethod("getTransfer")
                    .apply { isAccessible = true }.invoke(activity))
                assertEquals("IDLE", transfer.javaClass.getMethod("getStage").invoke(transfer).toString())
                assertEquals(committed, state(activity).draft)
            }
            assertEquals(committed, manager.snapshot())
        }
    }

    @Test fun changedDraftInvalidatesPreviewWithoutReplacingNewerEdits() {
        val committed = manager.snapshot()
        ActivityScenario.launch(BaseUrlManagerActivity::class.java).use { scenario ->
            waitForWindow()
            inspectFile(scenario, configuration())
            onView(withText(LibraryR.string.base_url_import_merge)).inRoot(isDialog()).perform(click())
            waitForPreview(scenario)
            lateinit var newer: UrlSnapshot
            scenario.onActivity { activity ->
                val editor = state(activity)
                newer = editor.draft!!.copy(environments = editor.draft!!.environments.map { environment ->
                    if (environment.id == "dev") environment.copy(urls = environment.urls.map {
                        it.copy(remark = "newer local edit")
                    }) else environment
                })
                editor.draft = newer
            }
            onView(withText(LibraryR.string.base_url_import_apply)).inRoot(isDialog()).perform(click())
            scenario.onActivity { assertEquals(newer, state(it).draft) }
            assertEquals(committed, manager.snapshot())
        }
    }

    @Test fun pendingImportRestoredWithoutViewModelRequestsFileAgain() {
        val committed = manager.snapshot()
        ActivityScenario.launch(BaseUrlManagerActivity::class.java).use { scenario ->
            waitForWindow()
            inspectFile(scenario, configuration())
            // 清除保留实例，验证实际 Bundle 恢复不会假定文件预览已完成。
            scenario.onActivity { it.viewModelStore.clear() }
            scenario.recreate()
            waitForWindow()
            scenario.onActivity { activity ->
                assertTrue(dialogMessages(activity).any { it == activity.getString(LibraryR.string.base_url_transfer_restart) })
                assertEquals(committed, state(activity).draft)
            }
            assertEquals(committed, manager.snapshot())
        }
    }

    @Test fun deletingLastOptionalCandidateKeepsBusinessAvailableInForm() {
        val current = manager.snapshot()
        val scratch = UrlEnvironment("scratch", "Scratch", listOf(BaseUrl("order", "https://scratch.example.com/")))
        assertTrue(manager.save(current.copy(environmentId = scratch.id, environments = current.environments + scratch)) is SaveResult.Success)
        ActivityScenario.launch(BaseUrlManagerActivity::class.java).use { scenario ->
            waitForWindow()
            scenario.onActivity { activity ->
                val editor = state(activity)
                editor.draft = editor.draft!!.copy(environments = editor.draft!!.environments.map {
                    if (it.id == "scratch") it.copy(urls = emptyList()) else it
                })
                BaseUrlManagerActivity::class.java.getDeclaredMethod("render").apply { isAccessible = true }.invoke(activity)
                BaseUrlManagerActivity::class.java.getDeclaredMethod("showAddressForm", BaseUrl::class.java, Boolean::class.javaPrimitiveType)
                    .apply { isAccessible = true }.invoke(activity, null, false)
            }
            scenario.onActivity { activity ->
                val dialogs = BaseUrlManagerActivity::class.java.getDeclaredField("dialogs").apply { isAccessible = true }
                    .get(activity) as Set<*>
                val form = dialogs.filterIsInstance<androidx.appcompat.app.AlertDialog>().first { it.findViewById<Spinner>(LibraryR.id.configKeySpinner) != null }
                val spinner = form.findViewById<Spinner>(LibraryR.id.configKeySpinner)!!
                assertTrue((0 until spinner.count).any { spinner.getItemAtPosition(it).toString().contains("order") })
            }
        }
    }

    private fun configuration(remark: String = "Team candidate") = """
        {"schemaVersion":1,"environments":[{"id":"dev","name":"Development","urls":[
        {"configKey":"order","url":"https://team.example.com/","select":true,"remark":"$remark"}]}]}
    """.trimIndent()

    private fun state(activity: BaseUrlManagerActivity) = ViewModelProvider(activity)[BaseUrlManagerActivity.EditorState::class.java]

    /** 注入文件读取的真实校验结果，后续选择、后台预览、应用和保存仍走页面原路径。 */
    private fun inspectFile(scenario: ActivityScenario<BaseUrlManagerActivity>, json: String) {
        val inspection = manager.inspectImport(json) as ImportInspectionResult.Success
        scenario.onActivity { activity ->
            val transfer = requireNotNull(BaseUrlManagerActivity::class.java.getDeclaredMethod("getTransfer").apply { isAccessible = true }.invoke(activity))
            val outcomeType = Class.forName("com.logan.multiurlmanager.library.internal.ConfigurationTransferState\$Outcome")
            val loaded = Class.forName("com.logan.multiurlmanager.library.internal.ConfigurationTransferState\$Outcome\$Loaded")
                .getConstructor(String::class.java, List::class.java).newInstance(json, inspection.environments)
            transfer.javaClass.getMethod("setOutcome", outcomeType).invoke(transfer, loaded)
            BaseUrlManagerActivity::class.java.getDeclaredMethod("consumeTransfer").apply { isAccessible = true }.invoke(activity)
        }
        waitForWindow()
    }

    private fun waitForPreview(scenario: ActivityScenario<BaseUrlManagerActivity>) {
        eventually {
            scenario.onActivity { activity ->
                val transfer = BaseUrlManagerActivity::class.java.getDeclaredMethod("getTransfer").apply { isAccessible = true }.invoke(activity)
                assertEquals("显示的提示：${dialogMessages(activity)}", "PREVIEW", requireNotNull(transfer).javaClass.getMethod("getStage").invoke(transfer).toString())
            }
        }
        waitForWindow()
    }

    private fun dialogMessages(activity: BaseUrlManagerActivity): List<String> {
        val dialogs = BaseUrlManagerActivity::class.java.getDeclaredField("dialogs").apply { isAccessible = true }
            .get(activity) as Set<*>
        return dialogs.filterIsInstance<androidx.appcompat.app.AlertDialog>().mapNotNull {
            it.findViewById<android.widget.TextView>(android.R.id.message)?.text?.toString()?.take(200)
        }
    }

    private fun waitForWindow() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        // 厂商保留弹窗动画时，主线程空闲不代表新窗口已停止移动。
        instrumentation.uiAutomation.waitForIdle(500, 5000)
    }

    private fun eventually(check: () -> Unit) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        var failure: AssertionError? = null
        while (SystemClock.uptimeMillis() < deadline) {
            try { check(); return } catch (error: AssertionError) { failure = error }
            SystemClock.sleep(50)
        }
        throw requireNotNull(failure)
    }
}
