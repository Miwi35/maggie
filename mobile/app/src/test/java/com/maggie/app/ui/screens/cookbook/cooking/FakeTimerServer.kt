package com.maggie.app.ui.screens.cookbook.cooking

import io.ktor.http.HttpMethod
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

/**
 * `/api/cooking/timers` as the API answers it (MAG-582, `CookingTimerController`), on a clock the test moves:
 * what a rotation reads back, what `add`, `pause`, `dismiss` and `cancel` do, and what went on the wire.
 */
class FakeTimerServer(private val now: () -> Long) {

    class Row(
        val id: String,
        val label: String,
        val recipe: String,
        val stepPosition: Int,
        val durationSeconds: Int,
        var endsAt: Long?,
        var rest: Int,
        var status: String,
        var finishedAt: String? = null,
    )

    val rows = linkedMapOf<String, Row>()
    val posts = mutableListOf<String>()
    val patches = mutableListOf<Pair<String, String>>()
    var gets = 0
    var refuseStart = false
    private var next = 1

    fun remaining(row: Row): Int = when (row.status) {
        "running" -> (((row.endsAt ?: now()) - now() + 999) / 1000).toInt().coerceAtLeast(0)
        "paused" -> row.rest
        else -> 0
    }

    fun json(row: Row): String =
        """{"id":"${row.id}","label":"${row.label}","recipe":"${row.recipe}","stepPosition":${row.stepPosition},""" +
            """"durationSeconds":${row.durationSeconds},"endsAt":${row.endsAt?.let { "\"${Instant.ofEpochMilli(it)}\"" } ?: "null"},""" +
            """"remainingSeconds":${remaining(row)},"status":"${row.status}","finishedAt":${row.finishedAt?.let { "\"$it\"" } ?: "null"}}"""

    /** The worker: a running timer whose time is up rings. */
    fun ring(id: String): Row = rows.getValue(id).also {
        it.status = "done"
        it.endsAt = now()
        it.finishedAt = Instant.ofEpochMilli(now()).toString()
    }

    /** A timer already going before the activity opened (another session, before a rotation). */
    fun seed(label: String, recipeId: String, step: Int, durationSeconds: Int, status: String = "running"): Row {
        val row = Row("t-${next++}", label, "/api/recipes/$recipeId", step, durationSeconds, now() + durationSeconds * 1000L, durationSeconds, status)
        rows[row.id] = row
        return row
    }

    fun handle(method: HttpMethod, path: String, body: String?): Pair<Int, String> {
        val fields = body?.let { Json.parseToJsonElement(it).jsonObject }
        return when {
            method == HttpMethod.Get -> {
                gets++
                val member = rows.values.filter { it.status in setOf("running", "paused", "done") }
                200 to """{"member":[${member.joinToString(",") { json(it) }}],"totalItems":${member.size}}"""
            }
            method == HttpMethod.Post -> {
                posts += body.orEmpty()
                if (refuseStart) return 400 to """{"error":"durationSeconds is required, a whole number."}"""
                val recipe = fields!!["recipe"]!!.jsonPrimitive.content
                val duration = fields["durationSeconds"]!!.jsonPrimitive.intOrNull!!
                val row = Row(
                    "t-${next++}",
                    fields["label"]!!.jsonPrimitive.content,
                    if (recipe.startsWith("/")) recipe else "/api/recipes/$recipe",
                    fields["stepPosition"]!!.jsonPrimitive.intOrNull!!,
                    duration,
                    now() + duration * 1000L,
                    duration,
                    "running",
                )
                rows[row.id] = row
                201 to json(row)
            }
            else -> {
                val id = path.substringAfterLast('/')
                val row = rows[id] ?: return 404 to """{"error":"Cooking timer not found: $id"}"""
                val action = fields?.get("action")?.jsonPrimitive?.contentOrNull.orEmpty()
                patches += id to action
                val refused = when (action) {
                    "pause" -> if (row.status != "running") true else {
                        row.rest = remaining(row); row.endsAt = null; row.status = "paused"; false
                    }
                    "resume" -> if (row.status != "paused") true else {
                        row.endsAt = now() + row.rest * 1000L; row.status = "running"; false
                    }
                    "add" -> if (row.status != "running" && row.status != "paused") true else {
                        val left = (remaining(row) + (fields?.get("seconds")?.jsonPrimitive?.intOrNull ?: 60)).coerceAtLeast(0)
                        row.rest = left
                        if (row.status == "running") row.endsAt = now() + left * 1000L
                        if (left == 0) ring(id)
                        false
                    }
                    "dismiss" -> if (row.status != "done") true else { row.status = "dismissed"; false }
                    "cancel" -> if (row.status !in setOf("running", "paused", "done")) true else { row.status = "cancelled"; false }
                    else -> true
                }
                if (refused) 400 to """{"error":"Cannot $action a ${row.status} timer","timer":${json(row)}}""" else 200 to json(row)
            }
        }
    }
}
