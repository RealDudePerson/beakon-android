package com.beakon.companion.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordLocationRequestBuilderTest {

    @Test
    fun `headers carry username and secret`() {
        val headers = RecordLocationRequestBuilder.headers("alice", "s3cret")

        assertEquals("alice", headers["username"])
        assertEquals("s3cret", headers["secret"])
    }

    @Test
    fun `body contains every field when all are present`() {
        val json = RecordLocationRequestBuilder.body(
            LocationReport(lat = 40.7128, lon = -74.0060, acc = 8.0, batt = 77, isCharging = false),
        )
        val obj = Json.parseToJsonElement(json).jsonObject

        assertEquals(40.7128, obj["lat"]!!.jsonPrimitive.double, 0.0)
        assertEquals(-74.0060, obj["lon"]!!.jsonPrimitive.double, 0.0)
        assertEquals(8.0, obj["acc"]!!.jsonPrimitive.double, 0.0)
        assertEquals(77, obj["batt"]!!.jsonPrimitive.int)
        assertEquals(false, obj["ischarging"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `body omits absent battery and charging instead of sending null`() {
        val json = RecordLocationRequestBuilder.body(
            LocationReport(lat = 1.0, lon = 2.0, acc = 5.0),
        )
        val obj = Json.parseToJsonElement(json).jsonObject

        assertFalse("batt must be omitted when unknown", obj.containsKey("batt"))
        assertFalse("ischarging must be omitted when unknown", obj.containsKey("ischarging"))
        assertTrue(obj.containsKey("lat"))
        assertTrue(obj.containsKey("lon"))
        assertTrue(obj.containsKey("acc"))
    }

    @Test
    fun `body never emits a literal null for battery or charging`() {
        val json = RecordLocationRequestBuilder.body(
            LocationReport(lat = 1.0, lon = 2.0, batt = null, isCharging = null),
        )

        assertFalse(json.contains("\"batt\":null"))
        assertFalse(json.contains("\"ischarging\":null"))
        assertFalse(json.contains("null"))
    }

    @Test
    fun `charging true is serialized as ischarging true`() {
        val json = RecordLocationRequestBuilder.body(
            LocationReport(lat = 1.0, lon = 2.0, batt = 50, isCharging = true),
        )
        val obj = Json.parseToJsonElement(json).jsonObject

        assertEquals(true, obj["ischarging"]!!.jsonPrimitive.boolean)
        assertFalse(obj.containsKey("isCharging"))
    }
}
