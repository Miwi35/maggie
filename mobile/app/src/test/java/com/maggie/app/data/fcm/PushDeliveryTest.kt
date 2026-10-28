package com.maggie.app.data.fcm

import com.maggie.app.data.interruption.InterruptionCenter
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/** With the app open Maggie interrupts inside it and the system shows nothing; otherwise it is the notification (MAG-314). */
class PushDeliveryTest {

    private val center = InterruptionCenter()
    private val notifier = mockk<PushNotifier>(relaxed = true)
    private val payload = PushPayload.from(mapOf("notificationId" to "n-1", "title" to "Rappel", "type" to "reminder"))!!

    @Test
    fun `in the foreground it is an interruption and no system notification`() {
        PushDelivery(center, { notifier }, isForeground = { true }).deliver(payload)

        assertEquals(payload, center.current.value)
        verify(exactly = 0) { notifier.show(any(), any(), any()) }
    }

    @Test
    fun `in the background it is the system notification and no interruption`() {
        PushDelivery(center, { notifier }, isForeground = { false }).deliver(payload)

        assertNull(center.current.value)
        verify(exactly = 1) { notifier.show(payload, any(), any()) }
    }

    @Test
    fun `the same push arriving twice in the foreground is shown once`() {
        val delivery = PushDelivery(center, { notifier }, isForeground = { true })

        delivery.deliver(payload)
        center.close("n-1")
        delivery.deliver(payload)

        assertNull(center.current.value)
        verify(exactly = 0) { notifier.show(any(), any(), any()) }
    }

    @Test
    fun `a withdrawal takes the notification down from the tray and the interruption away`() {
        val delivery = PushDelivery(center, { notifier }, isForeground = { true })
        delivery.deliver(payload)

        delivery.withdraw("cooking-timer-t-1", "n-1")

        verify(exactly = 1) { notifier.close("cooking-timer-t-1") }
        assertNull(center.current.value)
    }

    @Test
    fun `a withdrawal without a notification id still clears the tray`() {
        PushDelivery(center, { notifier }, isForeground = { false }).withdraw("cooking-timer-t-1", null)

        verify(exactly = 1) { notifier.close("cooking-timer-t-1") }
    }

    @Test
    fun `a departure alert is the system's strong interruption, the app open or not (MAG-658)`() {
        val departure = PushPayload.from(mapOf("notificationId" to "n-2", "title" to "Pars maintenant pour Dentiste", "type" to "departure"))!!

        PushDelivery(center, { notifier }, isForeground = { true }).deliver(departure)

        assertNull("the toaster cannot say a link to Waze", center.current.value)
        verify(exactly = 1) { notifier.show(departure, any(), any()) }
    }

    // --- An alert that outlived what it announced (MAG-679)

    private val now = Instant.parse("2026-10-28T12:00:00Z")
    private fun departure(expiresAt: String?) = PushPayload.from(
        buildMap {
            put("notificationId", "n-3")
            put("title", "Pars maintenant pour Dentiste")
            put("type", "departure")
            expiresAt?.let { put("expiresAt", it) }
        },
    )!!

    @Test
    fun `a departure alert whose event has started is shown nowhere, delivered 18 hours late`() {
        PushDelivery(center, { notifier }, isForeground = { true }, now = { now }).deliver(departure("2026-10-27T18:00:00Z"))

        assertNull(center.current.value)
        verify(exactly = 0) { notifier.show(any(), any(), any()) }
    }

    @Test
    fun `a departure alert is shown until its event starts`() {
        val valid = departure("2026-10-28T12:30:00Z")

        PushDelivery(center, { notifier }, isForeground = { false }, now = { now }).deliver(valid)

        verify(exactly = 1) { notifier.show(valid, any(), any()) }
    }

    @Test
    fun `an alert without an expiry is always shown`() {
        val open = departure(null)

        PushDelivery(center, { notifier }, isForeground = { false }, now = { now }).deliver(open)

        verify(exactly = 1) { notifier.show(open, any(), any()) }
    }
}
