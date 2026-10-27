package com.maggie.app.data.location

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.location.Priority
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class FusedPositionSourceTest {

    private class FakeFixes(private val byPriority: Map<Int, Position?>, private val failure: Exception? = null) : FixProvider {
        val priorities = mutableListOf<Int>()
        val timeouts = mutableListOf<Long>()

        override suspend fun fix(priority: Int, timeoutMs: Long, maxAgeMs: Long): Position? {
            priorities += priority
            timeouts += timeoutMs
            failure?.let { throw it }
            return byPriority[priority]
        }
    }

    private val gps = Position(48.8566, 2.3522, 8.0, Instant.parse("2026-10-27T10:00:00Z"))
    private val wifi = Position(48.87, 2.38, 500.0, Instant.parse("2026-10-27T09:59:30Z"))

    @Test
    fun `a position asked for before a departure uses the GPS and takes up to 30 seconds`() = runTest {
        val fixes = FakeFixes(mapOf(Priority.PRIORITY_HIGH_ACCURACY to gps, Priority.PRIORITY_BALANCED_POWER_ACCURACY to wifi))

        val position = FusedPositionSource(fixes).current(PositionAccuracy.HIGH)

        assertEquals(gps, position)
        assertEquals(listOf(Priority.PRIORITY_HIGH_ACCURACY), fixes.priorities)
        assertEquals(30_000L, fixes.timeouts.single())
    }

    @Test
    fun `the periodic position never wakes the GPS`() = runTest {
        val fixes = FakeFixes(mapOf(Priority.PRIORITY_BALANCED_POWER_ACCURACY to wifi))

        val position = FusedPositionSource(fixes).current(PositionAccuracy.BALANCED)

        assertEquals(wifi, position)
        assertEquals(listOf(Priority.PRIORITY_BALANCED_POWER_ACCURACY), fixes.priorities)
    }

    @Test
    fun `no GPS fix in time sends the best position available, with its true precision`() = runTest {
        val fixes = FakeFixes(mapOf(Priority.PRIORITY_HIGH_ACCURACY to null, Priority.PRIORITY_BALANCED_POWER_ACCURACY to wifi))

        val position = FusedPositionSource(fixes).current(PositionAccuracy.HIGH)

        assertEquals(wifi, position)
        assertEquals(500.0, position!!.accuracy, 0.0)
        assertEquals(listOf(Priority.PRIORITY_HIGH_ACCURACY, Priority.PRIORITY_BALANCED_POWER_ACCURACY), fixes.priorities)
    }

    @Test
    fun `nothing at all is no position, not a crash`() = runTest {
        val fixes = FakeFixes(emptyMap())

        assertNull(FusedPositionSource(fixes).current(PositionAccuracy.HIGH))
    }

    @Test
    fun `a provider that fails is no position, not a crash`() = runTest {
        val fixes = FakeFixes(emptyMap(), failure = SecurityException("permission taken back"))

        assertNull(FusedPositionSource(fixes).current(PositionAccuracy.HIGH))
        assertNull(FusedPositionSource(fixes).current(PositionAccuracy.BALANCED))
    }
}
