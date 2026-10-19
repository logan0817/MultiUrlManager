package com.logan.multiurlmanager.app

import android.app.Activity
import androidx.appcompat.app.AppCompatDelegate
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.longClick
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
import androidx.test.espresso.matcher.ViewMatchers.Visibility
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.logan.multiurlmanager.MainActivity
import com.logan.multiurlmanager.library.BaseUrlManager
import com.logan.multiurlmanager.library.BaseUrlManagerActivity
import com.logan.multiurlmanager.library.SaveResult
import com.logan.multiurlmanager.library.UrlSnapshot
import com.logan.multiurlmanager.library.R as LibraryR
import com.logan.multiurlmanager.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    private lateinit var original: UrlSnapshot
    private var originalNightMode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    private val manager get() = requireNotNull(BaseUrlManager.instance)

    @Before fun prepare() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            // 未指定模式等价于跟随系统；setDefaultNightMode 不接受 UNSPECIFIED。
            originalNightMode = AppCompatDelegate.getDefaultNightMode().let {
                if (it == AppCompatDelegate.MODE_NIGHT_UNSPECIFIED) AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM else it
            }
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }
        original = manager.snapshot()
        val reset = original.copy(environmentId = "dev", environments = listOf(manager.defaultEnvironment("dev"), manager.defaultEnvironment("test"), manager.defaultEnvironment("production")))
        assertTrue(manager.save(reset) is SaveResult.Success)
    }

    @After fun restore() {
        try {
            if (::original.isInitialized) {
                assertTrue(manager.save(original.copy(revision = manager.snapshot().revision)) is SaveResult.Success)
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                AppCompatDelegate.setDefaultNightMode(originalNightMode)
            }
        }
    }

    @Test fun sampleShowsCurrentConfigurationAndOpensEditor() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.tvConfigInfo)).check(matches(isDisplayed()))
            onView(withId(R.id.btnSettings)).perform(click())
            onView(withId(LibraryR.id.environmentSpinner)).check(matches(isDisplayed()))
        }
    }

    @Test fun selectedDraftSurvivesAddingAndRecreation_cancelDoesNotPersist() {
        val baseline = manager.snapshot()
        ActivityScenario.launch(BaseUrlManagerActivity::class.java).use { scenario ->
            onView(withText("https://order-backup.dev.example.com/")).perform(click())
            addAddress("https://new.dev.example.com")
            scenario.recreate()
            onView(withText("https://new.dev.example.com/")).check(matches(withEffectiveVisibility(Visibility.VISIBLE)))
            assertEquals(baseline, manager.snapshot())
            pressBack()
            onView(withText(LibraryR.string.base_url_discard)).perform(click())
            assertEquals(baseline, manager.snapshot())
        }
    }

    @Test fun savingDraftCommitsSelectionAndAddedCandidate() {
        ActivityScenario.launchActivityForResult<BaseUrlManagerActivity>(android.content.Intent(
            InstrumentationRegistry.getInstrumentation().targetContext, BaseUrlManagerActivity::class.java
        )).use { scenario ->
            onView(withText("https://order-backup.dev.example.com/")).perform(click())
            addAddress("https://new.dev.example.com")
            scenario.recreate()
            onView(withId(LibraryR.id.btnSave)).perform(click())
            assertEquals(Activity.RESULT_OK, scenario.result.resultCode)
            assertEquals("https://order-backup.dev.example.com/", manager.getUrl("order"))
            assertTrue(manager.snapshot().urls.any { it.url == "https://new.dev.example.com/" })
        }
    }

    @Test fun externalWriteMakesDraftConflict() {
        ActivityScenario.launch(BaseUrlManagerActivity::class.java).use {
            val current = manager.snapshot()
            assertTrue(manager.save(current.copy(environmentId = "test")) is SaveResult.Success)
            onView(withId(LibraryR.id.btnSave)).perform(click())
            eventually { onView(withText(LibraryR.string.base_url_conflict)).check(matches(isDisplayed())) }
            assertEquals("test", manager.snapshot().environmentId)
            onView(withText(LibraryR.string.base_url_reload)).perform(click())
        }
    }

    @Test fun environmentSwitchIsDraftUntilSave() {
        val baseline = manager.snapshot()
        ActivityScenario.launchActivityForResult<BaseUrlManagerActivity>(android.content.Intent(
            InstrumentationRegistry.getInstrumentation().targetContext, BaseUrlManagerActivity::class.java
        )).use { scenario ->
            scenario.onActivity { activity -> activity.findViewById<android.widget.Spinner>(LibraryR.id.environmentSpinner).setSelection(1) }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            onView(withText("https://order.test.example.com/")).check(matches(isDisplayed()))
            assertEquals(baseline, manager.snapshot())
            onView(withId(LibraryR.id.btnSave)).perform(click())
            assertEquals(Activity.RESULT_OK, scenario.result.resultCode)
            assertEquals("test", manager.snapshot().environmentId)
            assertEquals("https://login.test.example.com/", manager.getUrl("login"))
        }
    }

    @Test fun productionSelectionUsesProductionAddressesAndKeepsOtherEnvironments() {
        val baseline = manager.snapshot()
        ActivityScenario.launchActivityForResult<BaseUrlManagerActivity>(android.content.Intent(
            InstrumentationRegistry.getInstrumentation().targetContext, BaseUrlManagerActivity::class.java
        )).use { scenario ->
            scenario.onActivity { activity -> activity.findViewById<android.widget.Spinner>(LibraryR.id.environmentSpinner).setSelection(2) }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            onView(withText("https://order.example.com/")).check(matches(isDisplayed()))
            assertEquals(baseline, manager.snapshot())
            onView(withId(LibraryR.id.btnSave)).perform(click())
            assertEquals(Activity.RESULT_OK, scenario.result.resultCode)
            assertEquals("production", manager.snapshot().environmentId)
            assertEquals("https://order.example.com/", manager.getUrl("order"))
            assertEquals("https://login.example.com/", manager.getUrl("login"))
            assertEquals(baseline.environments.first { it.id == "dev" }, manager.snapshot().environments.first { it.id == "dev" })
        }
    }

    @Test fun switchingEnvironmentsKeepsCandidatesVisibleEveryFrame() {
        val baseline = manager.snapshot()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(BaseUrlManagerActivity::class.java).use { scenario ->
            for (position in listOf(1, 2, 0)) {
                val finished = java.util.concurrent.CountDownLatch(1)
                val fadedFrames = java.util.concurrent.atomic.AtomicInteger()
                scenario.onActivity { activity ->
                    val list = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(LibraryR.id.recyclerView)
                    activity.findViewById<android.widget.Spinner>(LibraryR.id.environmentSpinner).setSelection(position)
                    var frames = 0
                    val callback = object : android.view.Choreographer.FrameCallback {
                        override fun doFrame(frameTimeNanos: Long) {
                            val visibleCandidates = (0 until list.childCount).count { index ->
                                val child = list.getChildAt(index)
                                child.findViewById<android.view.View>(LibraryR.id.tvUrl) != null &&
                                    child.visibility == android.view.View.VISIBLE && child.alpha >= 0.99f && child.height > 0
                            }
                            if (visibleCandidates < 3) fadedFrames.incrementAndGet()
                            if (++frames < 30) android.view.Choreographer.getInstance().postFrameCallback(this)
                            else finished.countDown()
                        }
                    }
                    android.view.Choreographer.getInstance().postFrameCallback(callback)
                }
                assertTrue("Frame sampling timed out", finished.await(5, java.util.concurrent.TimeUnit.SECONDS))
                assertEquals("Environment switch faded or removed visible candidates", 0, fadedFrames.get())
                instrumentation.waitForIdleSync()
                val target = baseline.environments[position]
                onView(withText(target.urls.first { it.configKey == "order" && it.select }.url))
                    .check(matches(isDisplayed()))
                assertEquals(baseline, manager.snapshot())
            }
        }
    }

    @Test fun deletingRequiredSelectionSurvivesRotationAndBlocksSave() {
        val baseline = manager.snapshot()
        ActivityScenario.launch(BaseUrlManagerActivity::class.java).use { scenario ->
            onView(withText("https://order.dev.example.com/")).perform(longClick())
            onView(withText(LibraryR.string.base_url_delete)).perform(click())
            onView(withText(android.R.string.ok)).perform(click())
            scenario.recreate()
            onView(withId(LibraryR.id.btnSave)).perform(click())
            eventually { onView(withText(LibraryR.string.base_url_validation_error)).check(matches(isDisplayed())) }
            assertEquals(baseline, manager.snapshot())
        }
    }

    @Test fun selectingCandidatesKeepsRowOrderAndMetadata() {
        ActivityScenario.launch(BaseUrlManagerActivity::class.java).use { scenario ->
            var order: List<String> = emptyList()
            scenario.onActivity { activity ->
                val adapter = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(LibraryR.id.recyclerView).adapter
                    as com.logan.multiurlmanager.library.adapter.BaseUrlAdapter
                order = adapter.listData.map { it.headerText ?: it.baseUrl!!.url }
            }
            repeat(3) {
                onView(withText("https://order-backup.dev.example.com/")).perform(click())
                onView(withText("https://order.dev.example.com/")).perform(click())
            }
            scenario.onActivity { activity ->
                val adapter = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(LibraryR.id.recyclerView).adapter
                    as com.logan.multiurlmanager.library.adapter.BaseUrlAdapter
                assertEquals(order, adapter.listData.map { it.headerText ?: it.baseUrl!!.url })
                assertEquals(manager.defaultEnvironment("dev").urls.first { it.url == "https://order.dev.example.com/" }.remark,
                    adapter.listData.first { it.baseUrl?.url == "https://order.dev.example.com/" }.baseUrl!!.remark)
            }
        }
    }

    @Test fun addressFormSurvivesRecreationAndRemembersBusinessKey() {
        ActivityScenario.launch(BaseUrlManagerActivity::class.java).use { scenario ->
            onView(withId(LibraryR.id.actionAdd)).perform(click())
            onView(withId(LibraryR.id.configKeySpinner)).perform(click())
            androidx.test.espresso.Espresso.onData(org.hamcrest.Matchers.equalTo("login"))
                .inRoot(androidx.test.espresso.matcher.RootMatchers.isPlatformPopup()).perform(click())
            onView(withId(LibraryR.id.etUrl)).perform(replaceText("https://login-new.example.com"), closeSoftKeyboard())
            onView(withId(LibraryR.id.etRemark)).perform(replaceText("kept remark"), closeSoftKeyboard())
            scenario.recreate()
            onView(withId(LibraryR.id.etUrl)).check(matches(withText("https://login-new.example.com")))
            onView(withId(LibraryR.id.etRemark)).check(matches(withText("kept remark")))
            onView(withId(android.R.id.button1)).perform(click())
            onView(withText("https://order-backup.dev.example.com/")).perform(click())
            onView(withId(LibraryR.id.actionAdd)).perform(click())
            onView(withId(LibraryR.id.configKeySpinner)).check(matches(
                androidx.test.espresso.matcher.ViewMatchers.withSpinnerText("login")))
            // 新弹窗的输入法可能尚在展开，先收起以免取消按钮坐标落到输入法窗口。
            onView(withId(LibraryR.id.etUrl)).perform(closeSoftKeyboard())
            onView(withId(android.R.id.button2)).perform(click())
            scenario.onActivity { activity ->
                val adapter = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(LibraryR.id.recyclerView).adapter
                    as com.logan.multiurlmanager.library.adapter.BaseUrlAdapter
                assertEquals("login", adapter.listData.first { it.baseUrl?.url == "https://login-new.example.com/" }.baseUrl!!.configKey)
            }
        }
    }

    @Test fun editingExistingAddressKeepsPositionAndRejectsDuplicates() {
        ActivityScenario.launch(BaseUrlManagerActivity::class.java).use { scenario ->
            onView(withText("https://order.dev.example.com/")).perform(longClick())
            onView(withText(LibraryR.string.base_url_edit)).perform(click())
            onView(withId(LibraryR.id.etUrl)).perform(replaceText("https://order-backup.dev.example.com/"), closeSoftKeyboard())
            onView(withId(android.R.id.button1)).perform(click())
            onView(withId(LibraryR.id.etUrl)).check(matches(
                androidx.test.espresso.matcher.ViewMatchers.hasErrorText(
                    InstrumentationRegistry.getInstrumentation().targetContext.getString(LibraryR.string.base_url_duplicate))))
            onView(withId(LibraryR.id.etUrl)).perform(replaceText("https://order-edited.example.com/"), closeSoftKeyboard())
            scenario.recreate()
            onView(withId(android.R.id.button1)).perform(click())
            scenario.onActivity { activity ->
                val adapter = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(LibraryR.id.recyclerView).adapter
                    as com.logan.multiurlmanager.library.adapter.BaseUrlAdapter
                assertEquals("https://order-edited.example.com/", adapter.listData[1].baseUrl!!.url)
                assertTrue(adapter.listData[1].baseUrl!!.select)
            }
        }
    }

    @Test fun lightSystemBarsUseReadableIcons() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView).isAppearanceLightStatusBars)
            }
            capture("home-light")
            onView(withId(R.id.btnSettings)).perform(click())
            onView(withId(LibraryR.id.environmentSpinner)).check(matches(isDisplayed()))
        }
        ActivityScenario.launch(BaseUrlManagerActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView).isAppearanceLightStatusBars)
            }
            assertEditorTopSurface(scenario)
            onView(withId(LibraryR.id.btnSave)).check(matches(withText(LibraryR.string.base_url_save)))
            onView(withId(LibraryR.id.actionAdd)).check(matches(withText(LibraryR.string.base_url_add)))
            scenario.onActivity { activity ->
                val add = activity.findViewById<android.view.View>(LibraryR.id.actionAdd)
                val save = activity.findViewById<android.view.View>(LibraryR.id.btnSave)
                val addPosition = IntArray(2)
                val savePosition = IntArray(2)
                add.getLocationOnScreen(addPosition)
                save.getLocationOnScreen(savePosition)
                assertTrue(addPosition[1] < savePosition[1])
            }
            capture("editor-light")
        }
    }

    @Test fun nightThemeUsesLightIconsAndRestoresFormAcrossThemeChange() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    assertTrue(!androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView).isAppearanceLightStatusBars)
                }
                capture("home-dark")
            }
            ActivityScenario.launch(BaseUrlManagerActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    assertTrue(!androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView).isAppearanceLightStatusBars)
                }
                assertEditorTopSurface(scenario)
                capture("editor-dark")
                onView(withId(LibraryR.id.actionAdd)).perform(click())
                onView(withId(LibraryR.id.etUrl)).perform(replaceText("https://night.example.com/"), closeSoftKeyboard())
                capture("form-dark")
                instrumentation.runOnMainSync {
                    androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO)
                }
                onView(withId(LibraryR.id.etUrl)).check(matches(withText("https://night.example.com/")))
                onView(withId(android.R.id.button2)).perform(click())
            }
        } finally {
            instrumentation.runOnMainSync {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            }
        }
    }

    @Test fun editorTopSurfaceStaysContinuousInLandscape() {
        ActivityScenario.launch(BaseUrlManagerActivity::class.java).use { scenario ->
            scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            eventually {
                scenario.onActivity { assertEquals(android.content.res.Configuration.ORIENTATION_LANDSCAPE, it.resources.configuration.orientation) }
            }
            onView(withId(LibraryR.id.environmentSpinner)).check(matches(isDisplayed()))
            assertEditorTopSurface(scenario)
            capture("editor-landscape")
        }
    }

    private fun assertEditorTopSurface(scenario: ActivityScenario<BaseUrlManagerActivity>) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(500, 5000)
        val point = IntArray(2)
        var statusY = 0
        var toolbarY = 0
        var surface = 0
        scenario.onActivity { activity ->
            val root = activity.findViewById<android.view.View>(LibraryR.id.root_layout)
            val top = activity.findViewById<android.view.View>(LibraryR.id.topBarContainer)
            val toolbar = activity.findViewById<android.view.View>(LibraryR.id.toolbar)
            val bars = requireNotNull(androidx.core.view.ViewCompat.getRootWindowInsets(root))
                .getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            assertEquals(0, root.paddingTop)
            assertEquals(bars.top, top.paddingTop)
            assertEquals(bars.left, root.paddingLeft)
            assertEquals(bars.right, root.paddingRight)
            assertTrue(top.paddingTop > 0)
            surface = (toolbar.background as android.graphics.drawable.ColorDrawable).color
            assertEquals(surface, (top.background as android.graphics.drawable.ColorDrawable).color)
            top.getLocationOnScreen(point)
            point[0] += 2
            statusY = point[1] + top.paddingTop / 2
            toolbarY = point[1] + top.paddingTop + toolbar.height / 2
            val controller = androidx.core.view.WindowCompat.getInsetsController(activity.window, root)
            assertEquals(androidx.core.graphics.ColorUtils.calculateLuminance(surface) > 0.5, controller.isAppearanceLightStatusBars)
        }
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        try {
            assertEquals("状态栏应显示 Toolbar 背景", surface, bitmap.getPixel(point[0], statusY))
            assertEquals("Toolbar 与顶部安全区应连续同色", surface, bitmap.getPixel(point[0], toolbarY))
        } finally { bitmap.recycle() }
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        // 等待窗口过渡与首帧绘制，避免采集到前一个 Activity 的系统栏或空白 Surface。
        instrumentation.uiAutomation.waitForIdle(500, 5000)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        java.io.File(instrumentation.targetContext.cacheDir, "ui-$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private fun addAddress(url: String) {
        onView(withId(LibraryR.id.actionAdd)).perform(click())
        onView(withId(LibraryR.id.etUrl)).perform(replaceText(url), closeSoftKeyboard())
        onView(withId(android.R.id.button1)).perform(click())
    }

    private fun eventually(check: () -> Unit) {
        val deadline = android.os.SystemClock.uptimeMillis() + 5000
        while (true) {
            try { check(); return }
            catch (error: AssertionError) {
                if (android.os.SystemClock.uptimeMillis() >= deadline) throw error
                android.os.SystemClock.sleep(50)
            }
            catch (error: androidx.test.espresso.NoMatchingViewException) {
                if (android.os.SystemClock.uptimeMillis() >= deadline) throw error
                android.os.SystemClock.sleep(50)
            }
        }
    }
}
