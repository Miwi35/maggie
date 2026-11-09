package com.maggie.app.ui.screens.grocery.run

import com.maggie.app.data.model.ActivityOrigin
import com.maggie.app.data.model.ActivityStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** The activity status' name for Courses (`GET /api/activity-status/courses`). */
const val GROCERY_RUN_ACTIVITY = "courses"

/** What launched the activity: the open list (`origin.kind` of the status). */
const val GROCERY_RUN_ORIGIN_KIND = "list"

private val stateJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * Courses' `state` in the activity status (MAG-762): the session's tour, the store being done, the stores left
 * out for today, the stores finished (MAG-765) and whether the tour is over. It never touches `Store.visitOrder`:
 * the habitual order stays the owner's.
 */
@Serializable
data class GroceryRunState(
    /** The stores visited today, in the order of the session. */
    val tour: List<String> = emptyList(),
    /** The store being done, one of [tour]; null while the tour is being chosen. */
    val current: String? = null,
    /** The stores of « pas aujourd'hui ». */
    val skipped: List<String> = emptyList(),
    /** The stores of [tour] finished (« Terminer le magasin »), in the order they were (MAG-765). */
    val done: List<String> = emptyList(),
    /** The last store is finished: the step « Manquants sans repli » is on (MAG-765). */
    val finished: Boolean = false,
) {
    fun toJson(): JsonObject = stateJson.encodeToJsonElement(serializer(), this).jsonObject

    /**
     * [tour] and [skipped] as they stand once the stores that hold articles are [habitual] (in the habitual order).
     * A finished store keeps its place in the tour, articles left or not, so « Magasin k sur N » does not move.
     */
    fun reconciledWith(habitual: List<String>): GroceryRunState {
        val known = habitual.toSet()
        val kept = tour.filter { it in known || it in done }.distinct()
        val left = skipped.filter { it in known && it !in kept }.distinct()
        var merged = kept
        // Once the tour is over, a store that holds articles again waits for the next one.
        if (!finished) habitual.filter { it !in kept && it !in left }.forEach { merged = merged.insertedInHabitualOrder(it, habitual) }
        return GroceryRunState(merged, current?.takeIf { it in merged }, left, done.filter { it in merged }, finished)
    }

    /** The store opened once [current] is finished: the next one of the tour not finished yet, null after the last. */
    fun nextAfter(storeId: String): String? {
        val over = done + storeId
        val at = tour.indexOf(storeId)
        return tour.drop(at + 1).firstOrNull { it !in over } ?: tour.firstOrNull { it !in over }
    }

    companion object {
        /** The state a status holds; null when it is not a Courses one or this version cannot read it. */
        fun from(status: ActivityStatus?): GroceryRunState? {
            if (status == null || status.activity != GROCERY_RUN_ACTIVITY) return null
            return runCatching { stateJson.decodeFromJsonElement(serializer(), status.state) }.getOrNull()
        }

        fun origin(listId: String) = ActivityOrigin(GROCERY_RUN_ORIGIN_KIND, listId)
    }
}

/** [storeId] placed before the first store of the tour that comes after it in the [habitual] order. */
internal fun List<String>.insertedInHabitualOrder(storeId: String, habitual: List<String>): List<String> {
    val rank = habitual.indexOf(storeId)
    val at = indexOfFirst { habitual.indexOf(it) > rank }.let { if (it < 0) size else it }
    return take(at) + storeId + drop(at)
}
