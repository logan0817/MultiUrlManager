package com.logan.multiurlmanager.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Spinner
import android.widget.FrameLayout
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.longClick
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.logan.multiurlmanager.library.BaseUrlManager
import com.logan.multiurlmanager.library.BaseUrlManagerActivity
import com.logan.multiurlmanager.library.SaveResult
import com.logan.multiurlmanager.library.UrlSnapshot
import com.logan.multiurlmanager.library.adapter.BaseUrlAdapter
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.not
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import com.logan.multiurlmanager.library.R as LibraryR

@RunWith(AndroidJUnit4::class)
class EditorPresentationTest {
    private lateinit var original: UrlSnapshot
    private val manager get() = requireNotNull(BaseUrlManager.instance)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Before fun prepare() {
        original = manager.snapshot()
        val reset = original.copy(
            environmentId = "dev",
            environments = listOf("dev", "test", "production").map(manager::defaultEnvironment)
        )
        assertTrue(manager.save(reset) is SaveResult.Success)
    }

    @After fun restore() {
        if (::original.isInitialized) {
            assertTrue(manager.save(original.copy(revision = manager.snapshot().revision)) is SaveResult.Success)
        }
    }

    @Test fun namedGroupsKeepSelectedSummaryWhenCollapsedAndRecreated() {
        val before = manager.snapshot()
        val name = manager.businessName("order")
        val title = if (name == "order") name else "$name · order"
        instrumentation.runOnMainSync {
            val legacyHeader = FrameLayout(instrumentation.targetContext).apply {
                addView(TextView(context).apply { id = LibraryR.id.tvKey })
            }
            // 公共 ViewHolder 仍接受宿主只包含旧 tvKey 的自定义布局。
            assertNotNull(BaseUrlAdapter.HeaderViewHolder(legacyHeader).tvKey)
        }
        ActivityScenario.launch(BaseUrlManagerActivity::class.java).use { scenario ->
            waitForWindow()
            onView(withText(title)).perform(click())
            scenario.onActivity { activity ->
                val adapter = activity.findViewById<RecyclerView>(LibraryR.id.recyclerView).adapter as BaseUrlAdapter
                assertFalse(adapter.listData.any { it.baseUrl?.configKey == "order" })
            }
            onView(allOf(withId(LibraryR.id.tvGroupSummary), withText(containsString(manager.getUrl("order")!!))))
                .check(matches(isDisplayed()))
            scenario.onActivity { it.findViewById<Spinner>(LibraryR.id.environmentSpinner).setSelection(1) }
            onView(withText("https://order.test.example.com/")).check(matches(isDisplayed()))
            scenario.onActivity { it.findViewById<Spinner>(LibraryR.id.environmentSpinner).setSelection(0) }
            scenario.recreate()
            scenario.onActivity { activity ->
                val adapter = activity.findViewById<RecyclerView>(LibraryR.id.recyclerView).adapter as BaseUrlAdapter
                assertFalse(adapter.listData.any { it.baseUrl?.configKey == "order" })
            }
            onView(withText(title)).perform(click())
            onView(withText("https://order.dev.example.com/")).check(matches(isDisplayed()))
            assertEquals(before, manager.snapshot())
        }
    }

    @Test fun changePreviewIncludesRemarksInAnotherEnvironmentAndKeepsAppliedStateSeparate() {
        val before = manager.snapshot()
        ActivityScenario.launch(BaseUrlManagerActivity::class.java).use { scenario ->
            waitForWindow()
            onView(withText("https://order.dev.example.com/")).perform(longClick())
            onView(withText(LibraryR.string.base_url_edit)).inRoot(isDialog()).perform(click())
            onView(withId(LibraryR.id.etRemark)).inRoot(isDialog())
                .perform(replaceText("updated development remark"), closeSoftKeyboard())
            onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
            scenario.onActivity { it.findViewById<Spinner>(LibraryR.id.environmentSpinner).setSelection(1) }
            instrumentation.waitForIdleSync()
            val name = before.environments.first { it.id == "dev" }.name
            val applied = instrumentation.targetContext.getString(LibraryR.string.base_url_active_environment, name)
            onView(withId(LibraryR.id.activeEnvironmentText)).check(matches(withText(applied)))
            onView(withId(LibraryR.id.changeSummaryToggle)).perform(click())
            onView(withId(LibraryR.id.changeSummaryText)).check(matches(withText(containsString("updated development remark"))))
            onView(withId(LibraryR.id.changeSummaryText)).check(matches(withText(containsString("[Development]"))))
            scenario.recreate()
            onView(withId(LibraryR.id.changeSummaryScroll)).check(matches(isDisplayed()))
            onView(withId(LibraryR.id.changeSummaryText)).check(matches(withText(containsString("updated development remark"))))
            assertEquals(before, manager.snapshot())
        }
    }

    @Test fun changePreviewListsAddedRemovedAndSelectedCandidatesWithoutSaving() {
        val before = manager.snapshot()
        val added = "https://new.dev.example.com/"
        val removed = "https://order-backup.dev.example.com/"
        ActivityScenario.launch(BaseUrlManagerActivity::class.java).use { scenario ->
            waitForWindow()
            onView(withText(removed)).perform(longClick())
            onView(withText(LibraryR.string.base_url_delete)).inRoot(isDialog()).perform(click())
            onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
            onView(withId(LibraryR.id.actionAdd)).perform(click())
            onView(withId(LibraryR.id.etUrl)).inRoot(isDialog())
                .perform(replaceText(added), closeSoftKeyboard())
            onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
            onView(withText(added)).perform(click())
            onView(withId(LibraryR.id.changeSummaryToggle)).perform(click())
            val context = instrumentation.targetContext
            val location = context.getString(
                LibraryR.string.base_url_change_location,
                before.environments.first { it.id == "dev" }.name
            )
            val name = manager.businessName("order")
            val title = if (name == "order") name else "$name · order"
            val expected = listOf(
                context.getString(LibraryR.string.base_url_change_selection, location, title, manager.getUrl("order"), added),
                context.getString(LibraryR.string.base_url_change_added, location, title, added),
                context.getString(LibraryR.string.base_url_change_removed, location, title, removed)
            ).joinToString("\n\n")
            onView(withId(LibraryR.id.changeSummaryText)).check(matches(withText(expected)))
            scenario.recreate()
            onView(withId(LibraryR.id.changeSummaryText)).check(matches(withText(expected)))
            assertEquals(before, manager.snapshot())
        }
    }

    @Test fun savedStateRestoresPresentationAndDraftIntoANewViewModel() {
        val before = manager.snapshot()
        ActivityScenario.launch(BaseUrlManagerActivity::class.java).use { scenario ->
            waitForWindow()
            onView(withText("https://order-backup.dev.example.com/")).perform(click())
            onView(withId(LibraryR.id.changeSummaryToggle)).perform(click())
            lateinit var previous: BaseUrlManagerActivity.EditorState
            scenario.onActivity { activity ->
                previous = ViewModelProvider(activity)[BaseUrlManagerActivity.EditorState::class.java]
                // 强制移除内存保留项，专项验证 Bundle 恢复；真正的进程回收由外部设备驱动验收。
                activity.viewModelStore.clear()
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                val current = ViewModelProvider(activity)[BaseUrlManagerActivity.EditorState::class.java]
                assertNotSame(previous, current)
                assertEquals(previous.draft, current.draft)
            }
            onView(withId(LibraryR.id.changeSummaryScroll)).check(matches(isDisplayed()))
            onView(withId(LibraryR.id.changeSummaryText))
                .check(matches(withText(containsString("https://order-backup.dev.example.com/"))))
            assertEquals(before, manager.snapshot())
        }
    }

    @Test fun fixedEnvironmentDisablesAllEditingAndStillAllowsCopy() {
        val previous = manager
        val field = BaseUrlManager::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val context = instrumentation.targetContext
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        var previousClip: ClipData? = null
        field.set(null, null)
        try {
            val fixed = BaseUrlManager.builder(context)
                .setEnvironments(previous.snapshot().environments)
                .setBusinessNames(mapOf("order" to "Order service", "login" to "Sign-in service"))
                .setFixedEnvironment("production")
                .build()
            val before = fixed.snapshot()
            ActivityScenario.launch(BaseUrlManagerActivity::class.java).use { scenario ->
                waitForWindow()
                // 在测试页面获得焦点后读取，兼容系统对后台剪贴板访问的限制。
                scenario.onActivity { previousClip = clipboard.primaryClip }
                onView(withId(LibraryR.id.btnSave)).check(matches(not(isEnabled())))
                onView(withId(LibraryR.id.environmentSpinner)).check(matches(not(isEnabled())))
                onView(withId(LibraryR.id.actionAdd)).check(matches(not(isEnabled())))
                onView(withId(LibraryR.id.statusText)).check(matches(withText(LibraryR.string.base_url_read_only)))
                onView(withText("https://order.example.com/")).perform(click(), longClick())
                scenario.onActivity {
                    assertEquals("https://order.example.com/", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
                }
                scenario.recreate()
                onView(withId(LibraryR.id.btnSave)).check(matches(not(isEnabled())))
                assertEquals(before, fixed.snapshot())
            }
        } finally {
            field.set(null, previous)
            instrumentation.runOnMainSync {
                if (previousClip != null) clipboard.setPrimaryClip(requireNotNull(previousClip))
                else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) clipboard.clearPrimaryClip()
                else clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }
    }

    private fun waitForWindow() {
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(500, 5000)
    }
}
