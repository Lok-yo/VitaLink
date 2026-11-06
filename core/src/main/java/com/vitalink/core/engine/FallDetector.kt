package com.vitalink.core.engine

import kotlin.math.sqrt

/**
 * Detector de caídas por acelerómetro: caída libre (magnitud < [freeFallG]) seguida de un
 * impacto (> [impactG]) en menos de [impactWindowMs]. Funciona con cualquier fuente de
 * muestras, así que se puede probar sin sensor real.
 */
class FallDetector(
    private val freeFallG: Float = 0.45f,
    private val minFreeFallMs: Long = 60,
    private val impactG: Float = 2.4f,
    private val impactWindowMs: Long = 1_000,
    private val cooldownMs: Long = 8_000,
) {
    private var freeFallStart = -1L
    private var freeFallConfirmedAt = -1L
    private var lastFallAt = -cooldownMs

    /** Devuelve true cuando la muestra completa el patrón de caída. */
    fun onSample(x: Float, y: Float, z: Float, timestampMs: Long): Boolean {
        val g = sqrt(x * x + y * y + z * z) / GRAVITY
        if (timestampMs - lastFallAt < cooldownMs) return false

        if (g < freeFallG) {
            if (freeFallStart < 0) freeFallStart = timestampMs
            if (timestampMs - freeFallStart >= minFreeFallMs) freeFallConfirmedAt = timestampMs
            return false
        }
        freeFallStart = -1L

        if (freeFallConfirmedAt > 0) {
            if (timestampMs - freeFallConfirmedAt > impactWindowMs) {
                freeFallConfirmedAt = -1L
            } else if (g > impactG) {
                freeFallConfirmedAt = -1L
                lastFallAt = timestampMs
                return true
            }
        }
        return false
    }

    companion object {
        const val GRAVITY = 9.80665f
    }
}
