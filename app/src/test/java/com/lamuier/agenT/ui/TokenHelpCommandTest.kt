package com.lamuier.agenT.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenHelpCommandTest {
    @Test
    fun powershellCommandDecryptsBeforeCopying() {
        val command = cursorTokenPowerShellCommand
        assertTrue(command.startsWith("\$ErrorActionPreference = 'Stop'"))
        assertTrue(command.contains("cursor-safe-storage:v1:"))
        assertTrue(command.contains("Local State"))
        assertTrue(command.contains("ProtectedData"))
        assertTrue(command.contains("ChainingModeGCM"))
        assertTrue(command.contains("Set-Clipboard -Value \$token"))
        assertTrue(command.contains("+\$')"))
        assertFalse(command.contains("| Set-Clipboard"))
        assertFalse(command.contains("Out-File"))
        assertFalse(command.contains("Set-Content"))
    }

    @Test
    fun pythonCommandDecryptsBeforeCopying() {
        val command = cursorTokenPythonCommand
        assertTrue(command.startsWith("@'"))
        assertTrue(command.trimEnd().endsWith("| python -"))
        assertTrue(command.contains("cursor-safe-storage:v1:"))
        assertTrue(command.contains("Local State"))
        assertTrue(command.contains("CryptUnprotectData"))
        assertTrue(command.contains("ChainingModeGCM"))
        assertTrue(command.contains("clip.exe"))
        assertFalse(command.contains("Out-File"))
        assertFalse(command.contains("Set-Content"))
    }

    @Test
    fun helpCopyMentionsSealedStorageInBothLocales() {
        val root = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
        for (locale in listOf("values", "values-en")) {
            val copy = strings(root, locale)
            assertTrue(copy.getValue("token_help_step3_body").contains("cursor-safe-storage:v1:"))
            assertTrue(copy.getValue("error_token_sealed").isNotBlank())
        }
    }

    private fun strings(root: File, locale: String): Map<String, String> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val entries = factory.newDocumentBuilder().parse(File(root, "$locale/strings.xml")).getElementsByTagName("string")
        return (0 until entries.length).associate { index ->
            val item = entries.item(index)
            item.attributes.getNamedItem("name").nodeValue to item.textContent
        }
    }
}
