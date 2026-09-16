package com.aeriva.core.model.measurement

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FreshnessTest {

    private val producedAt: Instant = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun isStaleAt_beforeExpiry_isNotStale() {
        val freshness = Freshness(producedAt, validUntil = producedAt.plusSeconds(60))

        assertFalse(freshness.isStaleAt(producedAt.plusSeconds(30)))
    }

    @Test
    fun isStaleAt_afterExpiry_isStale() {
        val freshness = Freshness(producedAt, validUntil = producedAt.plusSeconds(60))

        assertTrue(freshness.isStaleAt(producedAt.plusSeconds(90)))
    }

    @Test
    fun isStaleAt_exactlyAtExpiry_isNotStale() {
        val validUntil = producedAt.plusSeconds(60)
        val freshness = Freshness(producedAt, validUntil)

        assertFalse(freshness.isStaleAt(validUntil))
    }

    @Test
    fun isStaleAt_withoutExpiry_isNeverStale() {
        val freshness = Freshness.withoutExpiry(producedAt)

        assertFalse(freshness.isStaleAt(producedAt.plus(Duration.ofDays(365))))
    }

    @Test
    fun ageAt_computesElapsedDuration() {
        val freshness = Freshness.withoutExpiry(producedAt)

        val age = freshness.ageAt(producedAt.plusSeconds(120))

        assertEquals(Duration.ofSeconds(120), age)
    }
}
