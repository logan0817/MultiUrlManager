package com.logan.multiurlmanager.consumer

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConsumerTest {
    @Test fun minifiedPublishedAarPersistsAndLoads() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, ConsumerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ConsumerActivity
        try {
            assertEquals("https://consumer.example.com/", activity.roundTrip())
            val editor = instrumentation.startActivitySync(Intent().setClassName(
                instrumentation.targetContext.packageName,
                "com.logan.multiurlmanager.library.BaseUrlManagerActivity"
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            try { assertNotNull(editor) }
            finally { instrumentation.runOnMainSync { editor.finish() } }
        }
        finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
