package com.lamuier.agenT.notification

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class XiaomiHyperIslandAdapterTest {
    @Test fun summaryContainsOnlyAppIconAndShortStatusWhileExpandedDetailsRemainIntact() {
        val param = JSONObject(XiaomiHyperIslandAdapter.buildJsonParam("Cursor + Codex · 已用",
            "Cursor 99.7%\nCodex 5 小时 20% · 22:00 重置\nCodex 服务异常", "服务异常",
            IslandMonitorState.ServiceIssue, false)).getJSONObject("param_v2")
        val island = param.getJSONObject("param_island")
        val big = island.getJSONObject("bigIslandArea")
        val left = big.getJSONObject("imageTextInfoLeft")
        assertFalse(left.has("textInfo"))
        val picture = "miui.focus.pic_agentt_formal_service_v7"
        assertEquals(picture, left.getJSONObject("picInfo").getString("pic"))
        assertEquals(picture, island.getJSONObject("smallIslandArea").getJSONObject("picInfo").getString("pic"))
        assertEquals("服务异常", big.getJSONObject("imageTextInfoRight").getJSONObject("textInfo").getString("title"))
        assertFalse(big.has("sameWidthDigitInfo"))
        assertFalse(big.has("fixedWidthDigitInfo"))
        assertFalse(big.toString().contains("%"))
        assertFalse(big.toString().contains("timer"))
        assertEquals("服务异常", param.getString("ticker"))
        assertEquals("服务异常", param.getString("aodTitle"))
        assertTrue(param.getJSONObject("baseInfo").getString("content").contains("99.7%"))
        assertTrue(param.getJSONObject("baseInfo").getString("content").contains("22:00 重置"))
    }

    @Test fun everyStyleAndStateUsesTheSamePictureAcrossAllIslandSurfaces() {
        for (cat in listOf(false, true)) for (state in IslandMonitorState.entries) for (more in listOf(0, 2)) {
            val caption = (if (cat) "等同步喵" else "等待同步") + if (more > 0) "+$more" else ""
            val param = JSONObject(XiaomiHyperIslandAdapter.buildJsonParam("Status", "Details", caption, state, cat, more))
                .getJSONObject("param_v2")
            val expected = IslandArtwork.pictureReference(state, cat, more)
            val island = param.getJSONObject("param_island")
            assertEquals(expected, island.getJSONObject("bigIslandArea").getJSONObject("imageTextInfoLeft")
                .getJSONObject("picInfo").getString("pic"))
            assertEquals(expected, island.getJSONObject("smallIslandArea").getJSONObject("picInfo").getString("pic"))
            assertEquals(expected, param.getJSONObject("baseInfo").getString("picFunction"))
            assertEquals(expected, param.getString("tickerPic"))
            assertEquals(expected, param.getString("aodPic"))
            assertEquals(caption, param.getString("ticker"))
        }
    }
}
