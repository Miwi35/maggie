package com.maggie.app.data.fcm

import com.maggie.app.data.interruption.InterruptionCenter
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
}
