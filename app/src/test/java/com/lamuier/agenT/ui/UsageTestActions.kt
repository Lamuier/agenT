package com.lamuier.agenT.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule

/** Service cards live in the primary pane on wide windows and in the detail list on phones. */
internal fun ComposeContentTestRule.scrollToUsageSummary() {
    val tag = if (onAllNodesWithTag("usage-summary-list").fetchSemanticsNodes().isNotEmpty()) "usage-summary-list" else "usage-detail-list"
    onNodeWithTag(tag).performScrollToKey("usage-summary")
}
