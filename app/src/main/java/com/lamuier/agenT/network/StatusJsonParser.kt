package com.lamuier.agenT.network

import com.lamuier.agenT.model.ComponentStatus
import com.lamuier.agenT.model.ServiceStatus
import com.lamuier.agenT.model.StatusComponent
import com.lamuier.agenT.model.StatusIncident
import com.lamuier.agenT.model.StatusIncidentUpdate
import com.lamuier.agenT.model.StatusIndicator
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 解析 Cursor / OpenAI 官方 Statuspage 兼容 JSON。
 *
 * 选用 `/api/v2/summary.json` + `/api/v2/incidents.json`，而不是 HTML 或 RSS：
 * - 网页结构会变，抓取脆弱，也拿不到结构化的组件状态；
 * - `history.rss` 只有事件标题与 HTML 正文，没有总览指示灯、组件列表或计划维护；
 * - Statuspage 公开 v2 JSON 是官方、稳定、无需鉴权的接口，正好覆盖状态页展示所需字段。
 */
object StatusJsonParser {
    fun parse(
        summary: JSONObject,
        incidentsPayload: JSONObject?,
        fetchedAt: String = nowStamp(),
        fromCache: Boolean = false,
        cacheAgeSeconds: Int = 0,
        partialHistory: Boolean = incidentsPayload == null,
        defaultStatusUrl: String = DEFAULT_STATUS_URL,
        partialComponents: Boolean = false,
        maxRecentIncidents: Int = MAX_RECENT_INCIDENTS,
    ): ServiceStatus {
        require(summary.optJSONObject("status")?.optString("indicator")?.isNotBlank() == true) {
            "Official status response is missing its status indicator"
        }
        val page = summary.optJSONObject("page") ?: JSONObject()
        val status = summary.optJSONObject("status") ?: JSONObject()
        val pageUrl = page.optString("url").ifBlank { defaultStatusUrl }.trimEnd('/')
        val components = parseComponents(summary.optJSONArray("components"))
        val activeIncidents = parseIncidents(summary.optJSONArray("incidents"), pageUrl)
            .filter { it.status !in RESOLVED_STATUSES }
        val scheduledMaintenances = parseIncidents(summary.optJSONArray("scheduled_maintenances"), pageUrl)
        val activeIds = activeIncidents.map { it.id }.toHashSet()
        val recentIncidents = parseIncidents(incidentsPayload?.optJSONArray("incidents"), pageUrl)
            .filter { it.id !in activeIds && it.status in RESOLVED_STATUSES }
            .take(maxRecentIncidents)
        return ServiceStatus(
            description = status.optString("description").ifBlank { "All Systems Operational" },
            indicator = parseIndicator(status.optString("indicator")),
            pageUpdatedAt = page.nullableString("updated_at"),
            pageUrl = pageUrl,
            components = components,
            activeIncidents = activeIncidents,
            scheduledMaintenances = scheduledMaintenances,
            recentIncidents = recentIncidents,
            fetchedAt = fetchedAt,
            fromCache = fromCache,
            cacheAgeSeconds = cacheAgeSeconds,
            partialHistory = partialHistory,
            partialComponents = partialComponents,
        )
    }

    fun toCacheJson(
        summary: JSONObject,
        incidentsPayload: JSONObject?,
        fetchedAt: String,
        defaultStatusUrl: String = DEFAULT_STATUS_URL,
        partialComponents: Boolean = false,
    ): String = JSONObject()
        .put("schema", CACHE_SCHEMA)
        .put("fetched_at", fetchedAt)
        .put("status_url", defaultStatusUrl)
        .put("partial_components", partialComponents)
        .put("summary", summary)
        .put("incidents", incidentsPayload ?: JSONObject.NULL)
        .toString()

    fun parseCache(rawJson: String, cacheAgeSeconds: Int): ServiceStatus {
        val root = JSONObject(rawJson)
        val summary = root.optJSONObject("summary") ?: throw IllegalArgumentException("状态缓存缺少 summary")
        val incidents = if (root.has("incidents") && !root.isNull("incidents")) {
            root.optJSONObject("incidents")
        } else {
            null
        }
        return parse(
            summary = summary,
            incidentsPayload = incidents,
            fetchedAt = root.optString("fetched_at").ifBlank { nowStamp() },
            fromCache = true,
            cacheAgeSeconds = cacheAgeSeconds,
            partialHistory = incidents == null,
            defaultStatusUrl = root.optString("status_url").ifBlank { DEFAULT_STATUS_URL },
            partialComponents = root.optBoolean("partial_components"),
            maxRecentIncidents = if (root.optString("status_url") == "https://status.openai.com") Int.MAX_VALUE else MAX_RECENT_INCIDENTS,
        )
    }

    fun nowStamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

    private fun parseComponents(array: JSONArray?): List<StatusComponent> {
        if (array == null) return emptyList()
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                if (item.optBoolean("group")) continue
                val status = parseComponentStatus(item.optString("status"))
                if (item.optBoolean("only_show_if_degraded") && status == ComponentStatus.Operational) continue
                add(
                    StatusComponent(
                        id = item.optString("id"),
                        name = item.optString("name"),
                        status = status,
                        position = item.optInt("position", index),
                    ),
                )
            }
        }
            .filter { it.id.isNotBlank() && it.name.isNotBlank() }
            .sortedBy { it.position }
    }

    private fun parseIncidents(array: JSONArray?, pageUrl: String): List<StatusIncident> {
        if (array == null) return emptyList()
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id")
                val name = item.optString("name")
                if (id.isBlank() || name.isBlank()) continue
                add(
                    StatusIncident(
                        id = id,
                        name = name,
                        status = item.optString("status"),
                        impact = item.optString("impact"),
                        createdAt = item.nullableString("created_at") ?: item.nullableString("started_at"),
                        updatedAt = item.nullableString("updated_at"),
                        resolvedAt = item.nullableString("resolved_at"),
                        scheduledFor = item.nullableString("scheduled_for"),
                        scheduledUntil = item.nullableString("scheduled_until"),
                        shortlink = item.nullableString("shortlink") ?: item.nullableString("url")
                            ?: "$pageUrl/incidents/$id",
                        affectedComponents = parseAffectedNames(item.optJSONArray("components")),
                        updates = parseUpdates(item.optJSONArray("incident_updates")),
                    ),
                )
            }
        }
    }

    private fun parseUpdates(array: JSONArray?): List<StatusIncidentUpdate> {
        if (array == null) return emptyList()
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val body = item.optString("body").trim()
                add(
                    StatusIncidentUpdate(
                        status = item.optString("status"),
                        body = body,
                        displayAt = item.nullableString("display_at") ?: item.nullableString("created_at"),
                    ),
                )
            }
        }
    }

    private fun parseAffectedNames(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index)
                val name = item?.optString("name")?.trim().orEmpty()
                if (name.isNotBlank()) add(name)
            }
        }.distinct()
    }

    fun parseIndicator(raw: String?): StatusIndicator = when (raw?.lowercase(Locale.US)) {
        "none" -> StatusIndicator.None
        "minor" -> StatusIndicator.Minor
        "major" -> StatusIndicator.Major
        "critical" -> StatusIndicator.Critical
        "maintenance" -> StatusIndicator.Maintenance
        else -> StatusIndicator.Unknown
    }

    fun parseComponentStatus(raw: String?): ComponentStatus = when (raw?.lowercase(Locale.US)) {
        "operational" -> ComponentStatus.Operational
        "degraded_performance" -> ComponentStatus.DegradedPerformance
        "partial_outage" -> ComponentStatus.PartialOutage
        "major_outage" -> ComponentStatus.MajorOutage
        "under_maintenance" -> ComponentStatus.UnderMaintenance
        else -> ComponentStatus.Unknown
    }

    private fun JSONObject.nullableString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).takeIf { it.isNotBlank() }
    }

    private const val CACHE_SCHEMA = 1
    private const val MAX_RECENT_INCIDENTS = 12
    private const val DEFAULT_STATUS_URL = "https://status.cursor.com"
    private val RESOLVED_STATUSES = setOf("resolved", "postmortem", "completed")
}
