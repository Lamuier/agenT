package com.lamuier.agenT.model

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element

class ShortcutActionTest {
    @Test fun unifiedTokenActionDoesNotRevealASingleProvider() {
        assertEquals(ShortcutAction.ManageTokens,
            ShortcutAction.fromIntentAction("com.lamuier.agenT.action.SHORTCUT_MANAGE_TOKENS"))
    }
    @Test fun legacyActionsAlsoOpenUnifiedManagement() {
        for (suffix in listOf("SHORTCUT_REVEAL_TOKEN", "SHORTCUT_MANAGE_ACCOUNTS")) {
            assertEquals(ShortcutAction.ManageTokens, ShortcutAction.fromIntentAction("com.lamuier.agenT.action.$suffix"))
        }
    }
    @Test fun settingsAndUnknownActionsKeepTheirOwnBehavior() {
        assertEquals(ShortcutAction.Settings, ShortcutAction.fromIntentAction(ShortcutAction.Settings.intentAction))
        assertNull(ShortcutAction.fromIntentAction(null))
        assertNull(ShortcutAction.fromIntentAction("other.app.action.SHORTCUT_MANAGE_TOKENS"))
    }
    @Test fun launcherPublishesOverviewTasksUnifiedTokenEntryAndSettings() {
        val root = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val shortcuts = factory.newDocumentBuilder().parse(File(root, "xml/shortcuts.xml")).getElementsByTagName("shortcut")
        val actions = (0 until shortcuts.length).map {
            val node = shortcuts.item(it) as Element
            (node.getElementsByTagName("intent").item(0) as Element)
                .getAttributeNS("http://schemas.android.com/apk/res/android", "action")
        }
        assertEquals(listOf(ShortcutAction.Overview.intentAction, ShortcutAction.Tasks.intentAction, ShortcutAction.ManageTokens.intentAction, ShortcutAction.Settings.intentAction), actions)
    }
}
