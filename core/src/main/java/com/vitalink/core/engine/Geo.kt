package com.vitalink.core.engine

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    private const val EARTH_RADIUS_M = 6_371_000.0

    fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return EARTH_RADIUS_M * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Desplaza un punto [meters] hacia el rumbo [bearingDeg] (0 = norte). */
    fun offset(lat: Double, lng: Double, meters: Double, bearingDeg: Double): Pair<Double, Double> {
        val bearing = Math.toRadians(bearingDeg)
        val dLat = meters * cos(bearing) / EARTH_RADIUS_M
        val dLng = meters * sin(bearing) / (EARTH_RADIUS_M * cos(Math.toRadians(lat)))
        return (lat + Math.toDegrees(dLat)) to (lng + Math.toDegrees(dLng))
    }

    /** Rumbo en grados desde el punto 1 hacia el punto 2. */
    fun bearing(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val y = sin(Math.toRadians(lng2 - lng1)) * cos(Math.toRadians(lat2))
        val x = cos(Math.toRadians(lat1)) * sin(Math.toRadians(lat2)) -
            sin(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * cos(Math.toRadians(lng2 - lng1))
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }
}
