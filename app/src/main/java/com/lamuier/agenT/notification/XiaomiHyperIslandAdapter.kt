package com.lamuier.agenT.notification

import android.app.Notification
import android.content.Context
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject

/**
 * Adds Xiaomi HyperIsland OS3 extras while retaining the normal Android notification.
 *
 * 与 ScheduleTimeline 真机验证过的做法一致：build() 之后直接往 notification.extras
 * 注入 miui.focus.* payload（param_v2 / protocol=3，对齐 HyperIsland-ToolKit 模板）。
 * 摘要态只保留 App 图标与关注状态；额度、重置时间及服务详情放在展开态 baseInfo。
 */
object XiaomiHyperIslandAdapter {
    private const val BUSINESS_NAME = "cursor_pulse"
    private const val PARAM_KEY = "miui.focus.param"
    // Keep the payload protocol aligned with HyperIsland-ToolKit's OS3 template.
    private const val PARAM_V2_PROTOCOL = 3

    internal fun applyIfSupported(
        context: Context,
        notification: Notification,
        title: String,
        content: String,
        monitorText: String,
        monitorState: IslandMonitorState = IslandMonitorState.Running,
        catLanguageEnabled: Boolean = false,
        additionalCount: Int = 0,
    ): Boolean {
        if (!XiaomiHyperIslandCapabilityReader.isSupported(context)) {
            return false
        }

        return runCatching {
            val appContext = context.applicationContext
            val actions = notification.actions.orEmpty().take(2)
            notification.extras.putAll(buildResourceBundle(appContext, monitorState, catLanguageEnabled, additionalCount, actions))
            notification.extras.putString(
                PARAM_KEY,
                buildJsonParam(
                    title = title,
                    content = content,
                    monitorText = monitorText,
                    monitorState = monitorState,
                    catLanguageEnabled = catLanguageEnabled,
                    additionalCount = additionalCount,
                    actionReferences = actions.indices.map(::actionReference),
                ),
            )
            true
        }.getOrDefault(false)
    }

    private fun buildResourceBundle(context: Context, state: IslandMonitorState, catLanguageEnabled: Boolean,
        additionalCount: Int, actions: List<Notification.Action>): Bundle = Bundle().apply {
        // Standard template mode expects image and action bundles under these keys.
        // Only register pictures the payload actually references; unreferenced
        // entries are dead weight the system never reads. The island renders this
        // App artwork in the collapsed capsule; ongoing notifications use its monochrome silhouette.
        putBundle("miui.focus.actions", buildActionBundle(actions))
        putBundle("miui.focus.pics", Bundle().apply {
            val icon = IslandArtwork.createIcon(context, state, catLanguageEnabled, additionalCount)
            putParcelable(IslandArtwork.pictureReference(state, catLanguageEnabled, additionalCount), icon)
        })
    }

    internal fun actionReference(index: Int): String = "miui.focus.action_agentt_$index"

    internal fun buildActionBundle(actions: List<Notification.Action>): Bundle = Bundle().apply {
        actions.take(2).forEachIndexed { index, action -> putParcelable(actionReference(index), action) }
    }

    /**
     * Minimal ParamV2 payload matching HyperIsland-ToolKit's standard template output.
     * Official summary template: icon in area A, a short status in area B; no quota digits or timer.
     */
    internal fun buildJsonParam(
        title: String,
        content: String,
        monitorText: String,
        monitorState: IslandMonitorState = IslandMonitorState.Running,
        catLanguageEnabled: Boolean = false,
        additionalCount: Int = 0,
        actionReferences: List<String> = emptyList(),
    ): String {
        val pictureRef = IslandArtwork.pictureReference(monitorState, catLanguageEnabled, additionalCount)
        val picInfo = JSONObject().apply {
            put("type", 1)
            put("pic", pictureRef)
            put("loop", false)
            put("autoplay", false)
            put("number", 0)
        }
        val paramIsland = JSONObject().apply {
            put("islandProperty", 1)
            put("islandPriority", 2)
            put("islandOrder", false)
            put("dismissIsland", false)
            put("maxSize", false)
            put("needCloseAnimation", true)
            put("bigIslandArea", JSONObject().apply {
                // Official capsule template "图文组件1 + 文本组件": icon only in A, short status in B.
                put("imageTextInfoLeft", JSONObject().apply {
                    put("type", 1)
                    put("picInfo", picInfo)
                })
                put("imageTextInfoRight", JSONObject().apply {
                    put("type", 2)
                    put("textInfo", JSONObject().apply {
                        put("title", monitorText)
                        put("showHighlightColor", false)
                    })
                })
            })
            put("smallIslandArea", JSONObject().apply {
                put("picInfo", picInfo)
            })
        }
        val baseInfo = JSONObject().apply {
            put("type", 1)
            put("title", title)
            put("subTitle", monitorText)
            put("content", content)
            put("picFunction", pictureRef)
        }
        val paramV2 = JSONObject().apply {
            put("protocol", PARAM_V2_PROTOCOL)
            put("business", BUSINESS_NAME)
            put("updatable", true)
            put("ticker", monitorText)
            put("tickerPic", pictureRef)
            put("aodTitle", monitorText)
            put("aodPic", pictureRef)
            put("enableFloat", false)
            put("isShowNotification", true)
            put("islandFirstFloat", false)
            put("param_island", paramIsland)
            put("baseInfo", baseInfo)
            if (actionReferences.isNotEmpty()) put("textButton", JSONArray().apply {
                actionReferences.take(2).forEach { reference ->
                    put(JSONObject().put("action", reference).put("clickWithCollapse", true))
                }
            })
        }
        return JSONObject().apply {
            put("param_v2", paramV2)
            put("isShowNotification", true)
        }.toString()
    }
}
