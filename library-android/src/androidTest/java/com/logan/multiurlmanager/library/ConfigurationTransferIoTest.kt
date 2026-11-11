package com.logan.multiurlmanager.library

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.logan.multiurlmanager.library.internal.ConfigurationTransferUi
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream

@RunWith(AndroidJUnit4::class)
class ConfigurationTransferIoTest {
    @Test fun boundedReadUsesUtf8ByteLimitAndRejectsMalformedEncoding() {
        val exact = ByteArray(ConfigurationTransferLimits.MAX_BYTES) { 'a'.code.toByte() }
        assertEquals(exact.size, ConfigurationTransferUi.readUtf8(ByteArrayInputStream(exact)).length)
        assertThrows(IllegalArgumentException::class.java) {
            ConfigurationTransferUi.readUtf8(ByteArrayInputStream(exact + byteArrayOf(1)))
        }
        assertThrows(java.nio.charset.CharacterCodingException::class.java) {
            ConfigurationTransferUi.readUtf8(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28)))
        }
        assertEquals("中文配置", ConfigurationTransferUi.readUtf8(ByteArrayInputStream("中文配置".toByteArray())))
    }

    @Test fun parcelBudgetIncludesOriginalDraftAndFormTogether() {
        val bundle = Bundle().apply {
            putString("original", "a".repeat(40_000))
            putBundle("draft", Bundle().apply { putString("url", "https://example.com/" + "b".repeat(40_000)) })
            putBundle("form", Bundle().apply { putString("remark", "备".repeat(40_000)) })
        }
        assertTrue(ConfigurationTransferUi.fitsRestorationBudget(bundle))
        bundle.getBundle("form")!!.putString("remark", "备".repeat(60_000))
        assertFalse(ConfigurationTransferUi.fitsRestorationBudget(bundle))
    }
}
