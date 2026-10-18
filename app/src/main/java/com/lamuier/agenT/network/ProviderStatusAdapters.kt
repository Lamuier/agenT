package com.lamuier.agenT.network

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Convert official public feeds before generic parsing drops group/component IDs. */
internal object ProviderStatusAdapters {
    fun kimi(summary: JSONObject, incidents: JSONObject?, incomplete: Boolean): Pair<JSONObject, JSONObject?> {
        val page = summary.getJSONObject("page")
        require(page.getString("url").trimEnd('/') == "https://status.moonshot.cn") { "Unexpected Kimi status page" }
        val components = summary.getJSONArray("components").objects()
        val group = components.singleOrNull { it.optBoolean("group") && it.optString("name").trim().equals("Kimi Code", true) }
            ?: throw IllegalArgumentException("Kimi Code component group missing")
        val ids = mutableSetOf(group.getString("id"))
        group.optJSONArray("components")?.let { a -> for (i in 0 until a.length()) ids += a.getString(i) }
        var changed: Boolean
        do {
            changed = false
            components.filter { it.optString("group_id") in ids }.forEach { if (ids.add(it.getString("id"))) changed = true }
        } while (changed)
        val scoped = components.filter { it.getString("id") in ids && !it.optBoolean("group") }
        require(scoped.isNotEmpty()) { "Empty Kimi Code group" }
        fun relevant(event: JSONObject) = event.optJSONArray("components")?.objects()?.any { it.optString("id") in ids } == true
        val active = summary.getJSONArray("incidents").objects().filter(::relevant)
        val scheduled = summary.optJSONArray("scheduled_maintenances")?.objects().orEmpty().filter(::relevant)
        val normalized = JSONObject(summary.toString()).put("components", JSONArray(scoped))
            .put("incidents", JSONArray(active)).put("scheduled_maintenances", JSONArray(scheduled))
        normalized.put("status", indicator(scoped.map { it.optString("status") }, active, incomplete || ids.any { id -> components.none { it.optString("id") == id } }))
        val history = incidents?.let { JSONObject().put("incidents", JSONArray(it.optJSONArray("incidents")?.objects().orEmpty().filter(::relevant))) }
        return normalized to history
    }

    fun deepSeek(summary: JSONObject, history: JSONObject?): Pair<JSONObject, JSONObject?> {
        val data = summary.getJSONObject("data")
        val page = data.getJSONObject("page")
        require(page.getLong("page_id") == DEEPSEEK_PAGE_ID && page.getString("custom_domain") == "status.deepseek.com" &&
            page.getString("url_name") == "deepseek" && page.getString("type") == "public") { "Unexpected DeepSeek status page" }
        val components = page.getJSONArray("components").objects().filter {
            Regex("(?<![A-Za-z])API(?![A-Za-z])", RegexOption.IGNORE_CASE).containsMatchIn(it.optString("name"))
        }
        require(components.isNotEmpty()) { "Missing API components" }
        val ids = components.map { it.getString("component_id") }.toSet()
        fun relevant(event: JSONObject) = event.optJSONArray("affected_components")?.objects()?.any { it.optString("component_id") in ids } == true
        val active = data.getJSONArray("active_changes").objects().filter(::relevant)
        val statuses = ids.associateWith { "operational" }.toMutableMap()
        active.forEach { event ->
            if (event.optString("type") == "maintenance" && event.optString("status") == "scheduled") return@forEach
            event.optJSONArray("affected_components")?.objects().orEmpty().forEach { c ->
                val id = c.optString("component_id")
                if (id in ids) statuses[id] = worse(statuses.getValue(id), componentStatus(c.optString("status")))
            }
        }
        val normalizedComponents = components.map { c -> JSONObject().put("id", c.getString("component_id"))
            .put("name", c.getString("name")).put("position", c.optInt("order_id")).put("status", statuses[c.getString("component_id")]) }
        val incidents = active.filter { it.optString("type") != "maintenance" }.map { deepSeekEvent(it, ids) }
        val maintenance = active.filter { it.optString("type") == "maintenance" }.map { deepSeekEvent(it, ids) }
        val normalized = JSONObject().put("page", JSONObject().put("id", DEEPSEEK_PAGE_ID.toString())
            .put("name", "DeepSeek API").put("url", DEEPSEEK_URL))
            .put("status", indicator(statuses.values.toList(), incidents, false))
            .put("components", JSONArray(normalizedComponents)).put("incidents", JSONArray(incidents))
            .put("scheduled_maintenances", JSONArray(maintenance))
        val normalizedHistory = history?.let { payload ->
            val items = payload.getJSONObject("data").getJSONArray("items").objects()
            require(items.all { it.optLong("page_id", DEEPSEEK_PAGE_ID) == DEEPSEEK_PAGE_ID })
            JSONObject().put("incidents", JSONArray(items.filter(::relevant).sortedByDescending { it.optLong("start_at_seconds") }
                .map { deepSeekEvent(it, ids) }))
        }
        return normalized to normalizedHistory
    }

    private fun deepSeekEvent(event: JSONObject, ids: Set<String>): JSONObject {
        fun stamp(seconds: Long) = seconds.takeIf { it > 0 }?.let { Instant.ofEpochSecond(it).toString() }
        val affected = event.optJSONArray("affected_components")?.objects().orEmpty().filter { it.optString("component_id") in ids }
        val updates = event.optJSONArray("updates")?.objects().orEmpty().sortedByDescending { it.optLong("at_seconds") }.map {
            JSONObject().put("id", it.opt("update_id")?.toString().orEmpty()).put("status", it.optString("status"))
                .put("body", it.optString("description")).put("display_at", stamp(it.optLong("at_seconds")))
        }
        val impact = indicator(affected.map { componentStatus(it.optString("status")) }, emptyList(), false).getString("indicator")
        return JSONObject().put("id", event.get("change_id").toString()).put("name", event.optString("title"))
            .put("status", event.optString("status")).put("impact", impact)
            .put("created_at", stamp(event.optLong("start_at_seconds"))).put("updated_at", updates.firstOrNull()?.optString("display_at"))
            .put("resolved_at", stamp(event.optLong("close_at_seconds"))).put("shortlink", DEEPSEEK_URL)
            .put("components", JSONArray(affected.map { JSONObject().put("id", it.optString("component_id")).put("name", it.optString("name")) }))
            .put("incident_updates", JSONArray(updates))
    }
    private fun componentStatus(value: String): String = when (value.lowercase()) {
        "operational" -> "operational"
        "degraded", "degraded_performance" -> "degraded_performance"
        "partial_outage" -> "partial_outage"
        "full_outage", "major_outage" -> "major_outage"
        "maintenance", "under_maintenance" -> "under_maintenance"
        else -> "unknown"
    }
    private fun worse(a: String, b: String): String = if (rank(b) > rank(a)) b else a
    private fun rank(value: String) = when (value) { "operational" -> 0; "under_maintenance" -> 1; "degraded_performance" -> 2; "partial_outage" -> 3; "major_outage" -> 4; else -> 5 }
    private fun indicator(statuses: List<String>, events: List<JSONObject>, incomplete: Boolean): JSONObject {
        val indicator = when {
            incomplete || statuses.isEmpty() || statuses.any { componentStatus(it) == "unknown" } -> "unknown"
            "major_outage" in statuses || events.any { it.optString("impact") == "critical" } -> "critical"
            "partial_outage" in statuses || events.any { it.optString("impact") == "major" } -> "major"
            "degraded_performance" in statuses || events.any { it.optString("impact") == "minor" } -> "minor"
            "under_maintenance" in statuses -> "maintenance"
            events.isNotEmpty() -> "minor"
            else -> "none"
        }
        val description = when (indicator) {
            "none" -> "All Systems Operational"
            "minor" -> "Degraded Performance"
            "major" -> "Partial Outage"
            "critical" -> "Major Outage"
            "maintenance" -> "Under Maintenance"
            else -> "Status Unknown"
        }
        return JSONObject().put("indicator", indicator).put("description", description)
    }
    private fun JSONArray.objects() = (0 until length()).mapNotNull { optJSONObject(it) }
    const val DEEPSEEK_PAGE_ID = 6410630422455L
    const val DEEPSEEK_URL = "https://status.deepseek.com"
}
