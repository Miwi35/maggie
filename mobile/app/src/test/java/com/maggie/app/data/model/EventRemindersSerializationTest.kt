package com.maggie.app.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/** 7 Oct.: an event with a reminder was refused by the API, `method` never left the phone (the app's Json drops defaults). */
class EventRemindersSerializationTest {

    // The app's client configuration (MaggieApp): defaults are not encoded unless the field says so.
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `a reminder is sent with its method and useDefault`() {
        assertEquals(
            """{"useDefault":false,"overrides":[{"method":"popup","minutes":5}]}""",
            json.encodeToString(EventReminders.serializer(), remindersFrom(listOf(5))!!),
        )
    }
}
