package com.maggie.app.data.model

import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * The owner's health profile (MAG-707), read from `GET /api/health/profile` and pushed whole on the `health-profile`
 * topic. [id] is null until the profile is first written. [bmrKcal], [needKcal] and [factor] are the API's figures:
 * null while sex, age, height or weight is missing ([needKcal] is the typed need in [MODE_ENTERED]). Enum-like values
 * stay the API's strings (`male`, `sedentary`, `computed`, `health_connect`…); [weightKg] is the last kept weighing.
 */
@Serializable
data class HealthProfile(
    val id: String? = null,
    val sex: String? = null,
    val birthYear: Int? = null,
    val age: Int? = null,
    val heightCm: Int? = null,
    val weightKg: Double? = null,
    val activityLevel: String = ActivityLevel.SEDENTARY.key,
    val mode: String = MODE_COMPUTED,
    val enteredNeedKcal: Int? = null,
    val bmrKcal: Int? = null,
    val needKcal: Int? = null,
    val factor: Double? = null,
    val stepGoal: Int = 8000,
    val suggestionWindowStart: String = "08:00",
    val suggestionWindowEnd: String = "20:00",
    val sessionMinutes: Int = 20,
    val stepSource: String = STEP_SOURCE_NONE,
) {
    val isEntered: Boolean get() = mode == MODE_ENTERED

    /**
     * The figures as the API will compute them after a local edit (Mifflin-St Jeor, the activity's factor, the need
     * rounded to ten kcal), so a stepper moves the card at once; the API's answer replaces them.
     */
    fun recomputed(): HealthProfile {
        val bmr = if (sex != null && age != null && heightCm != null && weightKg != null) {
            Math.round(10 * weightKg + 6.25 * heightCm - 5 * age + if (sex == SEX_MALE) 5 else -161).toInt()
        } else {
            null
        }
        val activity = ActivityLevel.byKey(activityLevel)
        val need = if (isEntered) enteredNeedKcal else bmr?.let { (Math.round(it * activity.factor / 10) * 10).toInt() }
        val factor = when {
            !isEntered -> activity.factor
            bmr == null || need == null || bmr <= 0 -> null
            else -> Math.round(need * 100.0 / bmr) / 100.0
        }
        return copy(bmrKcal = bmr, needKcal = need, factor = factor)
    }

    companion object {
        const val SEX_MALE = "male"
        const val SEX_FEMALE = "female"
        const val MODE_COMPUTED = "computed"
        const val MODE_ENTERED = "entered"
        const val STEP_SOURCE_HEALTH_CONNECT = "health_connect"
        const val STEP_SOURCE_NONE = "none"
        val SESSION_MINUTES = listOf(15, 20, 30)
    }
}

/** The five activity levels, their Mifflin-St Jeor factor and the chip's label. */
enum class ActivityLevel(val key: String, val label: String, val factor: Double) {
    SEDENTARY("sedentary", "Sédentaire", 1.2),
    LIGHT("light", "Légère", 1.375),
    MODERATE("moderate", "Modérée", 1.55),
    ACTIVE("active", "Active", 1.725),
    VERY_ACTIVE("very_active", "Très active", 1.9),
    ;

    companion object {
        fun byKey(key: String?): ActivityLevel = entries.firstOrNull { it.key == key } ?: SEDENTARY
    }
}

private const val THIN_NBSP = ' '

/** « 1 980 » — thousands apart by a thin no-break space, as amounts are (`Money.kt`). */
fun formatThousands(value: Int): String =
    (if (value < 0) "−" else "") + kotlin.math.abs(value).toString().reversed().chunked(3).joinToString(THIN_NBSP.toString()).reversed()

/** « 1 980 kcal », « — kcal » without a figure. */
fun formatKcal(kcal: Int?): String = "${kcal?.let(::formatThousands) ?: "—"} kcal"

/** « × 1,20 »: two decimals, as the phone mockup writes it. */
fun formatFactor(factor: Double): String = "× " + String.format(Locale.FRANCE, "%.2f", factor)

/** « 72 kg », « 72,4 kg », « — kg ». */
fun formatProfileWeight(weightKg: Double?): String {
    if (weightKg == null) return "— kg"
    val tenths = Math.round(weightKg * 10)
    val figure = if (tenths % 10 == 0L) (tenths / 10).toString() else String.format(Locale.FRANCE, "%.1f", tenths / 10.0)
    return "$figure kg"
}
