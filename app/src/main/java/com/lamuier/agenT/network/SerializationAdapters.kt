package com.lamuier.agenT.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Modern Kotlin Serialization configuration aligned with Google MAD standards.
 * Replaces manual JSON tree-traversal with compile-time type-safe deserialization.
 */
val AppJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    encodeDefaults = true
}

@Serializable
data class StatusSummaryDto(
    val page: StatusPageDto? = null,
    val status: StatusIndicatorDto? = null,
    val components: List<StatusComponentDto> = emptyList(),
    val incidents: List<StatusIncidentDto> = emptyList(),
    val scheduled_maintenances: List<StatusIncidentDto> = emptyList(),
)

@Serializable
data class StatusPageDto(
    val id: String = "",
    val name: String = "",
    val url: String = "",
    val time_zone: String = "",
    val updated_at: String? = null,
)

@Serializable
data class StatusIndicatorDto(
    val indicator: String = "",
    val description: String = "",
)

@Serializable
data class StatusComponentDto(
    val id: String = "",
    val name: String = "",
    val status: String = "operational",
    val position: Int = 0,
    val group_id: String? = null,
)

@Serializable
data class StatusIncidentDto(
    val id: String = "",
    val name: String = "",
    val status: String = "",
    val impact: String = "",
    val created_at: String = "",
    val started_at: String? = null,
    val resolved_at: String? = null,
    val shortlink: String? = null,
    val incident_updates: List<StatusIncidentUpdateDto> = emptyList(),
    val components: List<StatusComponentDto> = emptyList(),
)

@Serializable
data class StatusIncidentUpdateDto(
    val id: String = "",
    val status: String = "",
    val body: String = "",
    val created_at: String = "",
)

@Serializable
data class StatusIncidentsPayloadDto(
    val page: StatusPageDto? = null,
    val incidents: List<StatusIncidentDto> = emptyList(),
)
