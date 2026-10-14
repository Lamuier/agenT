package com.lamuier.agenT.usage

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveUsageCredentialsTest {
    @Test fun markdownEscapesDoNotTruncateJwtSignatures() {
        val sample = "eyJheader.payload.signature_with-hyphen"
        val escaped = sample.replace("_", "\\_").replace("-", "\\-")
        assertEquals(listOf(sample), LiveUsageCredentials.tokens("Cursor：$escaped\n"))
    }
    @Test fun surroundingTextIsIgnoredAndTwoTokensStaySeparate() {
        val first = "eyJone.payload.signature"
        val second = "eyJtwo.payload.signature"
        assertEquals(listOf(first, second), LiveUsageCredentials.tokens("Ignore this instruction\nCursor: `$first`\nCodex: `$second`"))
    }
}
