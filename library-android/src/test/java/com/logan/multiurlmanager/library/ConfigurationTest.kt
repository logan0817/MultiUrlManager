package com.logan.multiurlmanager.library

import com.logan.multiurlmanager.library.utils.BaseUrlConfigLoader
import org.junit.Assert.*
import org.junit.Test

class ConfigurationTest {
    private val environment = """{"id":"production","name":"生产","urls":[{"configKey":"order","url":"https://order.example.com/","select":true}]}"""

    @Test fun readmeExamplesUseTheActualConfigurationParser() {
        val root = java.io.File(requireNotNull(System.getProperty("multiurlmanager.projectRoot")))
        val examples = listOf("README.md", "README_EN.md").map { name ->
            val text = root.resolve(name).readText()
            val source = requireNotNull(Regex("(?ms)^```json\\s*\\n(.*?)^```\\s*$").find(text)).groupValues[1]
            BaseUrlConfigLoader.parseConfiguration(source)
        }
        assertEquals(examples[0], examples[1])
        assertEquals("production", examples[0].defaultEnvironment)
        assertEquals(setOf("production", "dev"), examples[0].environments.map { it.id }.toSet())
        examples[0].environments.forEach { environment ->
            assertEquals(setOf("order", "login"), environment.requiredKeys)
            assertEquals(2, environment.urls.count { it.select })
        }
    }

    @Test fun jsonDeclaresDefaultWithoutCodeSelectingAnEnvironment() {
        val config = BaseUrlConfigLoader.parseConfiguration("""{"schemaVersion":1,"defaultEnvironment":"production","environments":[$environment]}""")
        assertEquals("production", config.defaultEnvironment)
        assertEquals("https://order.example.com/", config.environments.single().urls.single().url)
    }

    @Test fun unknownOrInvalidDefaultIsRejected() {
        for (value in listOf("\"missing\"", "\"\"", "null", "1", "true")) {
            assertThrows(Exception::class.java) {
                BaseUrlConfigLoader.parseConfiguration("""{"schemaVersion":1,"defaultEnvironment":$value,"environments":[$environment]}""")
            }
        }
    }

    @Test fun malformedSchemaVersionIsNeverCoercedToVersionOne() {
        for (version in listOf("1.5", "\"1\"", "true", "null", "2")) {
            val source = """{"schemaVersion":$version,"environments":[$environment]}"""
            assertThrows(IllegalArgumentException::class.java) {
                BaseUrlConfigLoader.parseConfiguration(source)
            }
            val state = """{"schemaVersion":$version,"revision":0,"environmentId":"production","environments":[$environment]}"""
            assertThrows(IllegalArgumentException::class.java) {
                com.logan.multiurlmanager.library.internal.StateCodec.decode(state)
            }
        }
    }

    @Test fun legacyFixedArraysAndExistingModernFilesRemainSupported() {
        val legacy = BaseUrlConfigLoader.parseConfiguration("""{"debug_defaults":[],"release_defaults":[{"configKey":"order","url":"https://order.example.com/","select":true}]}""")
        assertNull(legacy.defaultEnvironment)
        assertEquals(listOf("debug_defaults", "release_defaults"), legacy.environments.map { it.id })
        assertNull(BaseUrlConfigLoader.parseConfiguration("""{"schemaVersion":1,"environments":[$environment]}""").defaultEnvironment)
    }
}
