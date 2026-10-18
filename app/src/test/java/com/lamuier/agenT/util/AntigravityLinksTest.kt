package com.lamuier.agenT.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AntigravityLinksTest {
    @Test fun onlyFixedOfficialDestinationsAreAllowed() {
        listOf(AntigravityLinks.REMOTE_CONTROL, AntigravityLinks.REMOTE_CONTROL_HELP,
            AntigravityLinks.USAGE_HELP, AntigravityLinks.SUPPORT).forEach { assertTrue(AntigravityLinks.isAllowed(it)) }
        listOf(
            "http://antigravity.google.com/", "https://antigravity.google.com.evil.test/",
            "https://antigravity.google.com@evil.test/", "https://user@antigravity.google.com/",
            "https://antigravity.google.com:444/", "https://antigravity.google.com/?token=private",
            "https://antigravity.google.com/#token=private", "https://antigravity.google.com/redirect",
            "https://antigravity.google/", "javascript:alert(1)", "file:///private/token",
        ).forEach { assertFalse(it, AntigravityLinks.isAllowed(it)) }
    }
}
