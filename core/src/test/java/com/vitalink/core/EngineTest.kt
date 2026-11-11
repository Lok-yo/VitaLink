package com.vitalink.core

import com.vitalink.core.engine.AnomalyDetector
import com.vitalink.core.engine.FallDetector
import com.vitalink.core.engine.Geo
import com.vitalink.core.engine.VitalsEngine
import com.vitalink.core.engine.VitalsReading
import com.vitalink.core.model.AlertType
import com.vitalink.core.model.SimCommand
import com.vitalink.core.model.VitalStatus
import com.vitalink.core.util.Times
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class EngineTest {
    private val homeLat = 29.0892
    private val homeLng = -110.9613

    private fun reading(bpm: Int = 72, inZone: Boolean = true, battery: Int = 80, distance: Int = 0) = VitalsReading(
        bpm = bpm, steps = 0, battery = battery, charging = false, lat = homeLat, lng = homeLng,
        distanceM = distance, inZone = inZone, activity = "", sensorMode = "simulado", status = VitalStatus.OK,
    )

    @Test
    fun fallDetector_detectsFreeFallFollowedByImpact() {
        val d = FallDetector()
        var t = 0L
        repeat(10) { assertFalse(d.onSample(0f, 0f, 9.8f, t)); t += 20 }
        repeat(6) { assertFalse(d.onSample(0.5f, 0.3f, 1.0f, t)); t += 20 }
        assertTrue(d.onSample(12f, 25f, 18f, t))
    }

    @Test
    fun fallDetector_ignoresImpactWithoutFreeFall() {
        val d = FallDetector()
        assertFalse(d.onSample(0f, 0f, 9.8f, 0))
        assertFalse(d.onSample(15f, 25f, 18f, 20))
    }

    @Test
    fun fallDetector_ignoresSlowImpactAfterWindow() {
        val d = FallDetector()
        var t = 0L
        repeat(6) { d.onSample(0.2f, 0.2f, 0.2f, t); t += 20 }
        assertFalse(d.onSample(15f, 25f, 18f, t + 2_000))
    }

    @Test
    fun anomaly_requiresSustainedTachycardiaAndFiresOnce() {
        val a = AnomalyDetector(sustainMs = 4_000)
        assertTrue(a.evaluate(reading(bpm = 130), 0, "Rosa").isEmpty())
        assertTrue(a.evaluate(reading(bpm = 132), 2_000, "Rosa").isEmpty())
        val fired = a.evaluate(reading(bpm = 140), 4_100, "Rosa")
        assertEquals(AlertType.TACHYCARDIA, fired.single().type)
        assertTrue(a.evaluate(reading(bpm = 141), 9_000, "Rosa").isEmpty())
        a.evaluate(reading(bpm = 80), 10_000, "Rosa")
        a.evaluate(reading(bpm = 130), 11_000, "Rosa")
        assertEquals(1, a.evaluate(reading(bpm = 130), 15_500, "Rosa").size)
    }

    @Test
    fun anomaly_geofenceAndBattery() {
        val a = AnomalyDetector()
        a.evaluate(reading(inZone = false, distance = 300), 0, "Rosa")
        val geo = a.evaluate(reading(inZone = false, distance = 320), 2_000, "Rosa")
        assertEquals(AlertType.GEOFENCE, geo.single().type)
        val battery = a.evaluate(reading(battery = 8), 3_000, "Rosa")
        assertEquals(AlertType.BATTERY, battery.single().type)
    }

    @Test
    fun engine_tachycardiaRampsFrom75To145InSeconds() {
        val e = VitalsEngine(homeLat, homeLng, 250, random = Random(7))
        repeat(20) { e.tick(1.0) }
        e.apply(SimCommand.TACHYCARDIA)
        var r = e.tick(1.0)
        repeat(9) { r = e.tick(1.0) }
        assertTrue("bpm=${r.bpm}", r.bpm >= 135)
        assertEquals(VitalStatus.EMERGENCY, r.status)
        e.apply(SimCommand.NORMAL)
        repeat(40) { r = e.tick(1.0) }
        assertTrue("bpm=${r.bpm}", r.bpm in 60..85)
    }

    @Test
    fun engine_leaveZoneCrossesGeofenceAndReturns() {
        val e = VitalsEngine(homeLat, homeLng, 250, random = Random(3))
        e.apply(SimCommand.LEAVE_ZONE)
        var r = e.tick(1.0)
        repeat(10) { r = e.tick(1.0) }
        assertFalse("distance=${r.distanceM}", r.inZone)
        e.apply(SimCommand.NORMAL)
        repeat(60) { r = e.tick(1.0) }
        assertTrue("distance=${r.distanceM}", r.inZone)
    }

    @Test
    fun geo_offsetAndDistanceAgree() {
        val (lat, lng) = Geo.offset(homeLat, homeLng, 300.0, 45.0)
        assertEquals(300.0, Geo.distanceMeters(homeLat, homeLng, lat, lng), 1.0)
    }

    @Test
    fun times_parsesPostgrestAndRealtimeFormats() {
        assertNotNull(Times.parse("2026-11-06T19:40:00.123456+00:00"))
        assertNotNull(Times.parse("2026-11-06T19:40:00Z"))
        assertNotNull(Times.parse("2026-11-06 19:40:00+00"))
        assertNotNull(Times.parse("2026-11-06T19:40:00.123456"))
        assertEquals(Times.millis("2026-11-06T19:40:00+00:00"), Times.millis("2026-11-06T12:40:00-07:00"))
        assertEquals("12:40", Times.hour("2026-11-06T19:40:00+00:00", "America/Hermosillo"))
    }
}
