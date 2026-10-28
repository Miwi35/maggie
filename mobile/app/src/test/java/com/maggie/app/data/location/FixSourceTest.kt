package com.maggie.app.data.location

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class FixSourceTest {

    @Test
    fun `the provider decides when it says gps or network, whatever the precision`() {
        assertEquals(FixSource.GPS, FixSource.of("gps", 120.0))
        assertEquals(FixSource.NETWORK, FixSource.of("network", 10.0))
    }

    @Test
    fun `the fused provider is read by its precision`() {
        assertEquals(FixSource.GPS, FixSource.of("fused", 20.0))
        assertEquals(FixSource.GPS, FixSource.of("fused", 50.0))
        assertEquals(FixSource.NETWORK, FixSource.of("fused", 50.1))
        assertEquals(FixSource.NETWORK, FixSource.of("fused", 500.0))
    }

    @Test
    fun `an unknown provider or a zero precision is a network fix`() {
        assertEquals(FixSource.NETWORK, FixSource.of(null, 0.0))
        assertEquals(FixSource.NETWORK, FixSource.of("passive", 0.0))
        assertEquals(FixSource.GPS, FixSource.of(null, 10.0))
    }

    @Test
    fun `a position without a stated source takes it from its precision`() {
        assertEquals(FixSource.GPS, Position(1.0, 2.0, 12.0, Instant.EPOCH).source)
        assertEquals(FixSource.NETWORK, Position(1.0, 2.0, 800.0, Instant.EPOCH).source)
    }

    @Test
    fun `the wire names are the API's`() {
        assertEquals(listOf("gps", "network"), FixSource.values().map { it.wire })
    }
}
