package com.logan.multiurlmanager.library

import com.logan.multiurlmanager.library.internal.StateCodec
import com.logan.multiurlmanager.library.utils.BaseUrlConfigLoader
import org.junit.Assert.*
import org.junit.Test

class StrictConfigurationTest {
    private val document = """{"schemaVersion":1,"defaultEnvironment":"dev","environments":[{"id":"dev","name":"Development","urls":[{"configKey":"order","url":"https://private.example/","select":true,"remark":"note","time":1}]}]}"""

    @Test fun wrongTypesReportFieldWithoutLeakingAddress() {
        val replacements = listOf(
            Triple("\"select\":true", "\"select\":\"tru\"", "environments[0].urls[0].select"),
            Triple("\"select\":true", "\"select\":1", "environments[0].urls[0].select"),
            Triple("\"time\":1", "\"time\":1.5", "environments[0].urls[0].time"),
            Triple("\"time\":1", "\"time\":9223372036854775808", "environments[0].urls[0].time"),
            Triple("\"remark\":\"note\"", "\"remark\":false", "environments[0].urls[0].remark"),
            Triple("\"configKey\":\"order\"", "\"configKey\":42", "environments[0].urls[0].configKey"),
            Triple("\"name\":\"Development\"", "\"name\":null", "environments[0].name")
        )
        replacements.forEach { (old, new, path) ->
            val failure = assertThrows(IllegalArgumentException::class.java) {
                BaseUrlConfigLoader.parseConfiguration(document.replace(old, new))
            }
            assertTrue(failure.message, failure.message.orEmpty().contains(path))
            assertFalse(failure.message.orEmpty().contains("private.example"))
        }
    }

    @Test fun malformedAndDuplicateFieldsFailRatherThanLastValueWinning() {
        listOf(
            document.replace("\"select\":true", "\"select\":true,\"select\":false"),
            document.replace("\"select\":true", "\"select\":tru"),
            document + " {}",
            document.replace("\"select\":true", "'select':true")
        ).forEach { input ->
            assertThrows(IllegalArgumentException::class.java) { BaseUrlConfigLoader.parseConfiguration(input) }
        }
    }

    @Test fun legacyArraysAndPersistedStateRetainCompatibility() {
        val legacy = """{"release_defaults":[{"configKey":"order","url":"https://old.example/","select":"true"}]}"""
        assertTrue(BaseUrlConfigLoader.parseConfiguration(legacy).environments.single().urls.single().select)
        val persisted = """{"schemaVersion":1,"revision":"3","environmentId":"dev","environments":[{"id":"dev","name":"Development","urls":[{"configKey":"order","url":"https://old.example/","select":"true"}]}]}"""
        assertEquals(3, StateCodec.decode(persisted).revision)
        assertTrue(StateCodec.decode(persisted).urls.single().select)
    }

    @Test fun requiredKeysMustBeUniqueNonblankStrings() {
        listOf("[1]", "[\"\"]", "[\" order \"]", "[\"order\",\"order\"]", "null").forEach { value ->
            val input = document.replace("\"urls\":", "\"requiredKeys\":$value,\"urls\":")
            val failure = assertThrows(IllegalArgumentException::class.java) { BaseUrlConfigLoader.parseConfiguration(input) }
            assertTrue(failure.message.orEmpty().contains("requiredKeys"))
        }
    }
}
