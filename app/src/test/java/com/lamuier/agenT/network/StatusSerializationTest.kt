package com.lamuier.agenT.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class StatusSerializationTest {

    @Test
    fun testStatusSummaryDeserialization() {
        val sampleJson = """
            {
                "page": {
                    "id": "kctbh9vrtdwd",
                    "name": "Cursor",
                    "url": "https://status.cursor.com",
                    "time_zone": "Etc/UTC",
                    "updated_at": "2026-10-18T10:00:00Z"
                },
                "status": {
                    "indicator": "none",
                    "description": "All Systems Operational"
                },
                "components": [
                    {
                        "id": "comp-1",
                        "name": "Cloud Agents",
                        "status": "operational",
                        "position": 1
                    }
                ],
                "incidents": []
            }
        """.trimIndent()

        val dto = AppJson.decodeFromString<StatusSummaryDto>(sampleJson)
        assertNotNull(dto.page)
        assertEquals("Cursor", dto.page?.name)
        assertEquals("none", dto.status?.indicator)
        assertEquals(1, dto.components.size)
        assertEquals("Cloud Agents", dto.components[0].name)
        assertEquals("operational", dto.components[0].status)
    }

    @Test
    fun testStatusIncidentsDeserialization() {
        val sampleJson = """
            {
                "page": {
                    "url": "https://status.cursor.com"
                },
                "incidents": [
                    {
                        "id": "inc-1",
                        "name": "API Degradation",
                        "status": "resolved",
                        "impact": "minor",
                        "created_at": "2026-10-18T08:00:00Z",
                        "incident_updates": [
                            {
                                "id": "upd-1",
                                "status": "resolved",
                                "body": "Resolved",
                                "created_at": "2026-10-18T08:30:00Z"
                            }
                        ]
                    }
                ]
            }
        """.trimIndent()

        val dto = AppJson.decodeFromString<StatusIncidentsPayloadDto>(sampleJson)
        assertEquals(1, dto.incidents.size)
        assertEquals("API Degradation", dto.incidents[0].name)
        assertEquals("resolved", dto.incidents[0].status)
        assertEquals(1, dto.incidents[0].incident_updates.size)
    }
}
