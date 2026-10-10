package com.maggie.app.data.interruption

import com.maggie.app.BuildConfig
import com.maggie.app.data.fcm.PushChannels
import com.maggie.app.data.model.Notification
import com.maggie.app.data.model.PendingApproval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** What the API publishes becomes what Maggie says, with the same targets and words as the push (MAG-314). */
class InterruptionsTest {

    private val scheme = BuildConfig.DEEP_LINK_SCHEME
    private val now = Instant.parse("2026-10-08T10:00:00Z")

    @Test
    fun `a notification becomes a message with its channel`() {
        val payload = Interruptions.of(Notification(id = "n-1", type = "proaction", title = "Idée", body = "Un plat ce soir ?"))!!

        assertEquals("n-1", payload.notificationId)
        assertEquals("Idée", payload.title)
        assertEquals("Un plat ce soir ?", payload.text)
        assertEquals(PushChannels.CHAT, payload.channel)
        assertNull(payload.link)
    }

    @Test
    fun `a notification about an entity points at it and says what the button opens`() {
        val payload = Interruptions.of(
            Notification(id = "n-1", title = "RDV", relatedEntityIri = "/api/events/01HX"),
        )!!

        assertEquals("$scheme://event/01HX", payload.link)
        assertEquals("Voir l'événement", payload.actionLabel)
    }

    @Test
    fun `finance paths open the finance screen they name, an unknown one opens nothing`() {
        assertEquals(
            "$scheme://finance/banks",
            Interruptions.of(Notification(id = "n", title = "t", relatedEntityIri = "/finance/banks"))!!.link,
        )
        assertEquals(
            "Reconnecter la banque",
            Interruptions.of(Notification(id = "n", title = "t", relatedEntityIri = "/finance/banks"))!!.actionLabel,
        )
        assertNull(Interruptions.of(Notification(id = "n", title = "t", relatedEntityIri = "/finance/secret"))!!.link)
        assertNull(Interruptions.of(Notification(id = "n", title = "t", relatedEntityIri = "https://evil.example/api/events/1"))!!.link)
    }

    @Test
    fun `a reminder stores minutes, which are said as a sentence`() {
        val payload = Interruptions.of(Notification(id = "n", type = "reminder", title = "Dentiste", body = "15"))!!

        assertEquals("Dans 15 min", payload.text)
    }

    @Test
    fun `what was read, is held for an answer or says nothing is not an interruption`() {
        assertNull(Interruptions.of(Notification(id = "n", title = "t", readAt = "2026-10-08T09:00:00+00:00")))
        assertNull(Interruptions.of(Notification(id = "n", type = "approval", title = "t")))
        assertNull(Interruptions.of(Notification(id = "n", title = " ")))
    }

    @Test
    fun `a read notification can still be said again when the owner asks`() {
        assertNotNull(Interruptions.of(Notification(id = "n", title = "t", readAt = "2026-10-08T09:00:00+00:00"), again = true))
    }

    @Test
    fun `a pending approval is a question that Maggie waits on`() {
        val payload = Interruptions.of(
            PendingApproval(id = "ap-1", toolName = "create_event", summary = "Ajouter un RDV"),
            now,
        )!!

        assertEquals("approval:ap-1", payload.key)
        assertEquals("Ajouter un RDV", payload.text)
        assertTrue(payload.isQuestion)
        assertEquals(listOf("Autoriser", "Refuser"), payload.actions().map { it.label })
    }

    @Test
    fun `an approval already decided or expired is not asked`() {
        assertNull(Interruptions.of(PendingApproval(id = "a", toolName = "t", status = PendingApproval.STATUS_APPROVED), now))
        assertNull(Interruptions.of(PendingApproval(id = "a", toolName = "t", expiresAt = "2026-10-08T09:59:00Z"), now))
        assertNotNull(Interruptions.of(PendingApproval(id = "a", toolName = "t", expiresAt = "2026-10-08T10:01:00Z"), now))
    }

    @Test
    fun `a created notification is offered`() {
        val event = Interruptions.readNotification(
            """{"@id":"/api/notifications/n-1","id":"n-1","type":"reminder","title":"Rappel","body":"5","readAt":null}""",
        )

        assertEquals("Dans 5 min", (event as FeedEvent.Offer).payload.text)
    }

    @Test
    fun `a notification read or deleted is withdrawn`() {
        assertEquals(
            FeedEvent.Withdraw("n-1"),
            Interruptions.readNotification("""{"@id":"/api/notifications/n-1","id":"n-1","title":"t","readAt":"2026-10-08T10:00:00+00:00"}"""),
        )
        assertEquals(
            FeedEvent.Withdraw("n-1"),
            Interruptions.readNotification("""{"@id":"/api/notifications/n-1","deleted":true}"""),
        )
    }

    @Test
    fun `a message that cannot be read is ignored`() {
        assertNull(Interruptions.readNotification("not json"))
        assertNull(Interruptions.readNotification("""{"id":"n-1","title":"t"}"""))
        assertNull(Interruptions.readNotification("""{"@id":"/api/notifications/n-1"}"""))
        assertNull(Interruptions.readApproval("[]"))
    }

    @Test
    fun `an approval decided elsewhere is withdrawn`() {
        assertEquals(
            FeedEvent.Withdraw("approval:ap-1"),
            Interruptions.readApproval("""{"id":"ap-1","toolName":"t","status":"approved"}""", now),
        )
        assertEquals(
            "approval:ap-1",
            (Interruptions.readApproval("""{"id":"ap-1","toolName":"t","status":"pending"}""", now) as FeedEvent.Offer).payload.key,
        )
    }
}
