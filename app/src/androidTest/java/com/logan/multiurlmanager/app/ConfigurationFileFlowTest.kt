package com.logan.multiurlmanager.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.SystemClock
import android.widget.PopupMenu
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.logan.multiurlmanager.library.BaseUrlManager
import com.logan.multiurlmanager.library.BaseUrlManagerActivity
import com.logan.multiurlmanager.library.SaveResult
import com.logan.multiurlmanager.library.UrlSnapshot
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import com.logan.multiurlmanager.library.R as LibraryR

/** 只拦截系统文件选择 UI；结果回调、ContentResolver、后台任务和页面交互使用生产路径。 */
@RunWith(AndroidJUnit4::class)
class ConfigurationFileFlowTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val manager get() = requireNotNull(BaseUrlManager.instance)
    private lateinit var original: UrlSnapshot

    @Before fun prepare() {
        original = manager.snapshot()
        assertTrue(manager.save(original.copy(
            environmentId = "dev",
            environments = listOf("dev", "test", "production").map(manager::defaultEnvironment)
        )) is SaveResult.Success)
        instrumentation.targetContext.contentResolver.delete(fileUri("exported"), null, null)
    }

    @After fun restore() {
        instrumentation.targetContext.contentResolver.delete(fileUri("exported"), null, null)
        if (::original.isInitialized) {
            assertTrue(manager.save(original.copy(revision = manager.snapshot().revision)) is SaveResult.Success)
        }
    }

    @Test fun cancelledFileSelectionKeepsDraftAndCommittedState() {
        val committed = manager.snapshot()
        pickerResult(Intent.ACTION_OPEN_DOCUMENT, null) { monitor ->
            ActivityScenario.launch(BaseUrlManagerActivity::class.java).use { scenario ->
                menu(scenario, LibraryR.id.actionImport)
                notice(LibraryR.string.base_url_transfer_cancelled)
                assertTrue(monitor.hits > 0)
                scenario.onActivity { assertEquals(committed, editor(it).draft) }
                assertEquals(committed, manager.snapshot())
            }
        }
    }

    @Test fun unreadableDocumentShowsFailureWithoutApplyingDraft() {
        val committed = manager.snapshot()
        pickerResult(Intent.ACTION_OPEN_DOCUMENT, fileUri("read-failure")) { monitor ->
            ActivityScenario.launch(BaseUrlManagerActivity::class.java).use { scenario ->
                menu(scenario, LibraryR.id.actionImport)
                notice(LibraryR.string.base_url_import_read_error)
                assertTrue(monitor.hits > 0)
                scenario.onActivity { assertEquals(committed, editor(it).draft) }
                assertEquals(committed, manager.snapshot())
            }
        }
    }

    @Test fun unwritableDocumentDoesNotReportExportSuccess() {
        val committed = manager.snapshot()
        pickerResult(Intent.ACTION_CREATE_DOCUMENT, fileUri("write-failure")) { monitor ->
            ActivityScenario.launch(BaseUrlManagerActivity::class.java).use { scenario ->
                menu(scenario, LibraryR.id.actionExport)
                onView(withText(LibraryR.string.base_url_export)).inRoot(isDialog()).perform(click())
                notice(LibraryR.string.base_url_export_write_error)
                assertTrue(monitor.hits > 0)
                scenario.onActivity { assertEquals(committed, editor(it).draft) }
                assertEquals(committed, manager.snapshot())
            }
        }
    }

    @Test fun exportWritesCommittedSnapshotAndLeavesUnsavedDraftIntact() {
        val committed = manager.snapshot()
        val expected = manager.exportConfiguration(setOf("dev"))
        pickerResult(Intent.ACTION_CREATE_DOCUMENT, fileUri("exported")) { monitor ->
            ActivityScenario.launch(BaseUrlManagerActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val state = editor(activity)
                    state.draft = state.draft!!.copy(environments = state.draft!!.environments.map { environment ->
                        if (environment.id == "dev") environment.copy(urls = environment.urls.map {
                            it.copy(remark = "unsaved draft marker")
                        }) else environment
                    })
                }
                menu(scenario, LibraryR.id.actionExport)
                notice(LibraryR.string.base_url_export_committed_only)
                onView(withText(android.R.string.ok)).inRoot(isDialog()).perform(click())
                onView(withText(LibraryR.string.base_url_export)).inRoot(isDialog()).perform(click())
                notice(LibraryR.string.base_url_export_success)
                assertTrue(monitor.hits > 0)
                val actual = instrumentation.targetContext.contentResolver.openInputStream(fileUri("exported"))!!
                    .bufferedReader(Charsets.UTF_8).use { it.readText() }
                assertEquals(expected, actual)
                assertFalse(actual.contains("unsaved draft marker"))
                scenario.onActivity { activity ->
                    assertTrue(editor(activity).draft!!.urls.all { it.remark == "unsaved draft marker" })
                }
                assertEquals(committed, manager.snapshot())
            }
        }
    }

    @Test fun readableDocumentUsesRealResolverAndOnlyAppliesAfterPreviewConfirmation() {
        val committed = manager.snapshot()
        pickerResult(Intent.ACTION_OPEN_DOCUMENT, fileUri("incoming")) { monitor ->
            ActivityScenario.launch(BaseUrlManagerActivity::class.java).use { scenario ->
                menu(scenario, LibraryR.id.actionImport)
                notice(LibraryR.string.base_url_import_merge)
                scenario.onActivity { assertEquals(committed, editor(it).draft) }
                onView(withText(LibraryR.string.base_url_import_merge)).inRoot(isDialog()).perform(click())
                notice(LibraryR.string.base_url_import_apply)
                scenario.onActivity { assertEquals(committed, editor(it).draft) }
                onView(withText(LibraryR.string.base_url_import_apply)).inRoot(isDialog()).perform(click())
                notice(LibraryR.string.base_url_import_applied)
                assertTrue(monitor.hits > 0)
                scenario.onActivity { activity ->
                    assertTrue(editor(activity).draft!!.urls.any { it.url == "https://file-team.example.com/" })
                }
                assertEquals(committed, manager.snapshot())
            }
        }
    }

    private fun fileUri(name: String): Uri {
        val testPackage = instrumentation.context.packageName
        val candidates = listOf(testPackage, instrumentation.targetContext.packageName)
            .map { "$it.configuration-files" }
        @Suppress("DEPRECATION")
        val authority = candidates.single { candidate ->
            instrumentation.context.packageManager.resolveContentProvider(candidate, 0)?.packageName == testPackage
        }
        return Uri.parse("content://$authority/$name")
    }

    private fun editor(activity: BaseUrlManagerActivity) =
        ViewModelProvider(activity)[BaseUrlManagerActivity.EditorState::class.java]

    private fun menu(scenario: ActivityScenario<BaseUrlManagerActivity>, itemId: Int) {
        instrumentation.waitForIdleSync()
        scenario.onActivity { activity ->
            val item = PopupMenu(activity, activity.window.decorView).menu.add(0, itemId, 0, "file action")
            assertTrue(activity.onOptionsItemSelected(item))
        }
        instrumentation.uiAutomation.waitForIdle(500, 5000)
    }

    private fun pickerResult(action: String, uri: Uri?, block: (Instrumentation.ActivityMonitor) -> Unit) {
        val filter = IntentFilter(action).apply { addCategory(Intent.CATEGORY_OPENABLE); addDataType("*/*") }
        val result = Instrumentation.ActivityResult(
            if (uri == null) Activity.RESULT_CANCELED else Activity.RESULT_OK,
            uri?.let { Intent().setData(it) }
        )
        val monitor = instrumentation.addMonitor(filter, result, true)
        try { block(monitor) } finally { instrumentation.removeMonitor(monitor) }
    }

    // 本组验证真实文件回调；直接激活可见按钮，避免厂商弹窗动画干扰屏幕坐标点击。
    private fun click(): ViewAction = object : ViewAction {
        override fun getDescription() = "activate visible file operation button"
        override fun getConstraints(): org.hamcrest.Matcher<android.view.View> =
            org.hamcrest.Matchers.allOf(isDisplayed(), androidx.test.espresso.matcher.ViewMatchers.isEnabled())
        override fun perform(controller: UiController, view: android.view.View) {
            assertTrue(view.performClick())
            controller.loopMainThreadUntilIdle()
        }
    }

    private fun notice(resource: Int) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        var last: Throwable? = null
        while (SystemClock.uptimeMillis() < deadline) {
            try {
                onView(withText(resource)).inRoot(isDialog()).check(matches(isDisplayed()))
                instrumentation.uiAutomation.waitForIdle(500, 5000)
                return
            } catch (error: AssertionError) {
                last = error
            } catch (error: androidx.test.espresso.NoMatchingViewException) {
                last = error
            } catch (error: androidx.test.espresso.NoMatchingRootException) {
                last = error
            }
            SystemClock.sleep(50)
        }
        throw AssertionError("Expected file operation notice", last)
    }
}
