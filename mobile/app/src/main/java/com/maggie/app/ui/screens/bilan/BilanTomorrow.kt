package com.maggie.app.ui.screens.bilan

import com.maggie.app.data.model.AgendaHomeEvent
import com.maggie.app.data.model.Meal
import com.maggie.app.data.model.MealSlot
import com.maggie.app.data.model.Trip
import com.maggie.app.data.model.Workout
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One line of tomorrow's programme: [time] is empty for a whole-day event, [detail] the grey word on the right. */
data class TomorrowRow(val time: String, val title: String, val detail: String = "")

/** What a trip question asks about: where the trip starts, how it is made, where the car is left. */
enum class TripQuestionKind(val wire: String) { ORIGIN("origin"), MODE("mode"), PARKING("parking") }

/**
 * A reply to a trip question. [message] is what Maggie receives (she keeps the trip with `set_trip`); null means the
 * owner will say it aloud, so nothing is sent and the question stays.
 */
data class TripChoice(val label: String, val message: String?, val selected: Boolean = false)

data class TripQuestion(
    val eventId: String,
    val kind: TripQuestionKind,
    val text: String,
    val choices: List<TripChoice>,
) {
    val key: String get() = tripAnswerKey(eventId, kind)
}

fun tripAnswerKey(eventId: String, kind: TripQuestionKind): String = "$eventId:${kind.wire}"

/**
 * Tomorrow as the Demain step shows it (MAG-755): the [rows] of the programme and the trip questions Maggie may still
 * ask. A question is answered once its trip has an origin chosen by the owner (origin), or once it is in the bilan's
 * `answers.trips` ([answered], the keys of [tripAnswerKey]) — so « Reprendre » never asks it again.
 */
data class TomorrowPlan(
    val day: LocalDate,
    val rows: List<TomorrowRow>,
    val events: List<AgendaHomeEvent>,
    val trips: List<Trip>,
) {
    /** « Mardi 27 oct. », the card's label. */
    val label: String
        get() = DAY_LABEL.format(day).replaceFirstChar { it.uppercase() }

    private val located: List<AgendaHomeEvent>
        get() = events.filter { !it.allDay && it.status != "cancelled" && !it.location.isNullOrBlank() && it.start != null }
            .sortedBy { it.start }

    /** Maggie's line: the first two appointments, in her voice, then the question when there is one. */
    fun say(question: TripQuestion?): String {
        val timed = events.filter { !it.allDay && it.status != "cancelled" && it.start != null }.sortedBy { it.start }
        val programme = when {
            timed.isEmpty() -> if (events.isEmpty()) "Rien à l’agenda demain." else "Voici ce qui vous attend demain."
            timed.size == 1 -> "${timed[0].summary} à ${spoken(timed[0])}."
            else -> "${timed[0].summary} à ${spoken(timed[0])}, puis ${timed[1].summary.replaceFirstChar { it.lowercase() }} à ${spoken(timed[1])}."
        }
        return if (question == null) programme else "$programme ${question.text}"
    }

    private fun spoken(event: AgendaHomeEvent): String {
        val time = event.start!!.atZone(ZONE).toLocalTime()
        return if (time.minute == 0) "${time.hour} h" else "${time.hour} h ${time.minute.toString().padStart(2, '0')}"
    }

    /** The first trip question not answered yet, event by event in the order of the day, or null: nothing to prepare. */
    fun nextQuestion(answered: Set<String>): TripQuestion? {
        val events = located
        events.forEachIndexed { index, event ->
            val trip = trips.firstOrNull { it.eventId == event.id && !it.isReturn }
            val originKnown = trip?.originDecidedBy == DECIDED_BY_USER || tripAnswerKey(event.id, TripQuestionKind.ORIGIN) in answered
            if (!originKnown) return originQuestion(event, events.getOrNull(index - 1))
            if (trip == null) return@forEachIndexed
            if (tripAnswerKey(event.id, TripQuestionKind.MODE) !in answered) return modeQuestion(event, trip)
            if (trip.mode == Trip.MODE_DRIVE && tripAnswerKey(event.id, TripQuestionKind.PARKING) !in answered) {
                return parkingQuestion(event)
            }
        }
        return null
    }

    private fun originQuestion(event: AgendaHomeEvent, previous: AgendaHomeEvent?): TripQuestion {
        val place = event.locationShort ?: event.summary
        if (previous == null) {
            return TripQuestion(
                event.id,
                TripQuestionKind.ORIGIN,
                "D’où partez-vous pour $place ?",
                listOf(
                    TripChoice("De la maison", tripMessage(event, "je pars de la maison")),
                    TripChoice("De ma position", tripMessage(event, "je pars de ma position")),
                ),
            )
        }
        return TripQuestion(
            event.id,
            TripQuestionKind.ORIGIN,
            "Vous repassez chez vous entre ${previous.summary.replaceFirstChar { it.lowercase() }} et ${event.summary.replaceFirstChar { it.lowercase() }} ?",
            listOf(
                TripChoice("Oui", tripMessage(event, "je pars de la maison")),
                TripChoice("Non, je reste en ville", tripMessage(event, "je pars de l’événement ${previous.id} « ${previous.summary} »")),
            ),
        )
    }

    private fun modeQuestion(event: AgendaHomeEvent, trip: Trip): TripQuestion = TripQuestion(
        event.id,
        TripQuestionKind.MODE,
        "Comment allez-vous à ${event.locationShort ?: event.summary} ? Je compte ${modePhrase(trip.mode)}.",
        MODES.map { (mode, label, phrase) ->
            TripChoice(label, tripMessage(event, "j’y vais $phrase"), selected = trip.mode == mode)
        },
    )

    private fun parkingQuestion(event: AgendaHomeEvent): TripQuestion = TripQuestion(
        event.id,
        TripQuestionKind.PARKING,
        "Où vous garez-vous à ${event.locationShort ?: event.summary} ?",
        listOf(
            TripChoice("Dans la rue", tripMessage(event, "je me gare dans la rue")),
            TripChoice("Dans un parking", tripMessage(event, "je me gare dans un parking")),
            TripChoice("Je précise", null),
        ),
    )

    private fun tripMessage(event: AgendaHomeEvent, answer: String): String =
        "Bilan du soir, trajet de demain · événement ${event.id} « ${event.summary} » : $answer."

    companion object {
        private val ZONE: ZoneId = ZoneId.of("Europe/Paris")
        private val DAY_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMM", Locale.FRANCE)
        private const val DECIDED_BY_USER = "user"

        private data class ModeWords(val mode: String, val label: String, val phrase: String)

        private val MODES = listOf(
            ModeWords("drive", "Voiture", "en voiture"),
            ModeWords("transit", "Transports", "en transports"),
            ModeWords("bicycle", "Vélo", "à vélo"),
            ModeWords("walk", "À pied", "à pied"),
        )

        fun modePhrase(mode: String): String = MODES.firstOrNull { it.mode == mode }?.phrase ?: "en voiture"

        private const val LUNCH_TIME = "12:30"
        private const val DINNER_TIME = "19:30"
        private val HOUR_MINUTE: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        /** Tomorrow's programme: events, the departures Maggie planned, the meals, the planned session — by hour. */
        fun of(
            day: LocalDate,
            events: List<AgendaHomeEvent>,
            trips: List<Trip>,
            meals: List<Meal>,
            workouts: List<Workout>,
        ): TomorrowPlan {
            val live = events.filter { it.status != "cancelled" }
            val rows = mutableListOf<TomorrowRow>()
            live.forEach { event ->
                rows += TomorrowRow(event.start?.takeUnless { event.allDay }?.let(::hour).orEmpty(), event.summary)
            }
            trips.forEach { trip ->
                val event = live.firstOrNull { it.id == trip.eventId } ?: return@forEach
                val time = trip.departInstant?.let(::hour) ?: return@forEach
                val duration = "${trip.durationMinutes} min ${modePhrase(trip.mode)}"
                rows += if (trip.isReturn) {
                    TomorrowRow(time, "Retour · ${event.summary}", duration)
                } else {
                    TomorrowRow(time, "Départ pour ${event.summary.replaceFirstChar { it.lowercase() }}", duration)
                }
            }
            meals.filter { it.date == day.toString() }.forEach { meal ->
                rows += TomorrowRow(if (meal.slot == MealSlot.LUNCH) LUNCH_TIME else DINNER_TIME, meal.summary, "Cuisine")
            }
            workouts.filter { it.isPlanned }.forEach { workout ->
                val start = runCatching { OffsetDateTime.parse(workout.startedAt).toInstant() }.getOrNull() ?: return@forEach
                if (start.atZone(ZONE).toLocalDate() != day) return@forEach
                rows += TomorrowRow(hour(start), "${workout.title} · ${workout.durationMinutes} min", "Santé")
            }
            return TomorrowPlan(day, rows.sortedWith(compareBy({ it.time.isEmpty().not() }, { it.time })), live, trips)
        }

        private fun hour(instant: java.time.Instant): String = HOUR_MINUTE.format(instant.atZone(ZONE))
    }
}
