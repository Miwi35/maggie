package com.maggie.app.data.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration

class TrackingPolicyTest {

    @Test
    fun `standing still asks for no GPS`() {
        assertEquals(TrackingMode.IDLE, TrackingPolicy.modeFor(Motion.STILL, carConnected = false, batteryPercent = 80))
        assertEquals(TrackingMode.IDLE, TrackingPolicy.modeFor(Motion.UNKNOWN, carConnected = false, batteryPercent = 80))
        assertNull(TrackingPolicy.planFor(TrackingMode.IDLE))
    }

    @Test
    fun `walking, running or cycling asks for the GPS every 2 minutes`() {
        assertEquals(TrackingMode.MOVING, TrackingPolicy.modeFor(Motion.MOVING, carConnected = false, batteryPercent = 80))
        val plan = TrackingPolicy.planFor(TrackingMode.MOVING)!!
        assertEquals(Duration.ofMinutes(2).toMillis(), plan.intervalMs)
        assertEquals(Duration.ofMinutes(2).toMillis(), plan.sendEveryMs)
    }

    @Test
    fun `connected to Android Auto, the GPS is continuous and the position goes out every minute`() {
        assertEquals(TrackingMode.DRIVING, TrackingPolicy.modeFor(Motion.STILL, carConnected = true, batteryPercent = 80))
        val plan = TrackingPolicy.planFor(TrackingMode.DRIVING)!!
        assertEquals(Duration.ofSeconds(10).toMillis(), plan.intervalMs)
        assertEquals(Duration.ofMinutes(1).toMillis(), plan.sendEveryMs)
    }

    @Test
    fun `detected in a vehicle without Android Auto is the same`() {
        assertEquals(TrackingMode.DRIVING, TrackingPolicy.modeFor(Motion.IN_VEHICLE, carConnected = false, batteryPercent = 16))
    }

    @Test
    fun `a battery at 15 percent or less asks for the GPS every 5 minutes at the wheel`() {
        assertEquals(TrackingMode.DRIVING_SAVER, TrackingPolicy.modeFor(Motion.IN_VEHICLE, carConnected = false, batteryPercent = 15))
        assertEquals(TrackingMode.DRIVING_SAVER, TrackingPolicy.modeFor(Motion.MOVING, carConnected = true, batteryPercent = 5))
        val plan = TrackingPolicy.planFor(TrackingMode.DRIVING_SAVER)!!
        assertEquals(Duration.ofMinutes(5).toMillis(), plan.intervalMs)
        assertEquals(Duration.ofMinutes(5).toMillis(), plan.sendEveryMs)
    }

    @Test
    fun `the car wins over walking`() {
        assertEquals(TrackingMode.DRIVING, TrackingPolicy.modeFor(Motion.MOVING, carConnected = true, batteryPercent = 50))
    }
}
