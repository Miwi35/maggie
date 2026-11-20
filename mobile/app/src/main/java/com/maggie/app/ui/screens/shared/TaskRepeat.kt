package com.maggie.app.ui.screens.shared

import com.maggie.app.data.model.Task
import com.maggie.app.util.RruleUtils
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** The six choices of the « Répéter » menu, in the menu's order (MAG-887). */
enum class RepeatPreset(val key: String, val label: String) {
    NEVER("never", "Jamais"),
    DAILY("daily", "Chaque jour"),
    WEEKLY("weekly", "Chaque semaine"),
    BIWEEKLY("biweekly", "Toutes les deux semaines"),
    MONTHLY("monthly", "Chaque mois"),
    CUSTOM("custom", "Personnalisé…"),
}

enum class RepeatUnit { DAY, WEEK, MONTH }

/** A rule the form can write: every [interval] [unit]s; [days] for weeks, [monthDay] for months (empty / null: the anchor's). */
data class RepeatRule(
    val unit: RepeatUnit,
    val interval: Int,
    val days: Set<DayOfWeek> = emptySet(),
    val monthDay: Int? = null,
)

/**
 * « Répéter » and « Jusqu’au » of the task form (MAG-887). A rule nobody touched is sent as [stored], even
 * when the day moves; once touched, the rule is written from the choice and the anchor day, so a preset
 * follows the day. [TaskRepeatText] reads any rule, whoever wrote it.
 */
data class TaskRepeat(
    val stored: String? = null,
    val choice: RepeatPreset = RepeatPreset.NEVER,
    val custom: RepeatRule? = null,
    val until: LocalDate? = null,
    val touched: Boolean = false,
) {
    /** The rule to send for this [anchor] day: null for « Jamais » and without an anchor. */
    fun toApi(anchor: LocalDate?): String? {
        if (anchor == null) return null
        if (!touched) return stored
        return when (choice) {
            RepeatPreset.NEVER -> null
            RepeatPreset.CUSTOM ->
                if (custom == null && stored != null) {
                    TaskRepeatText.withUntil(stored, until)
                } else {
                    TaskRepeatText.write(custom ?: TaskRepeatText.defaultRule(anchor), anchor, until)
                }
            else -> TaskRepeatText.write(TaskRepeatText.presetRule(choice, anchor), anchor, until)
        }
    }

    fun repeats(anchor: LocalDate?): Boolean = toApi(anchor) != null

    /** The menu's checked choice: the preset writing exactly the current rule, else « Personnalisé… ». */
    fun checked(anchor: LocalDate?): RepeatPreset = TaskRepeatText.presetOf(toApi(anchor), anchor)

    /** A preset other than « Personnalisé… »: « Jamais » clears the end day, the others keep it. */
    fun pick(preset: RepeatPreset): TaskRepeat =
        copy(choice = preset, until = if (preset == RepeatPreset.NEVER) null else until, touched = true)

    fun withCustom(rule: RepeatRule): TaskRepeat = copy(choice = RepeatPreset.CUSTOM, custom = rule, touched = true)

    fun withUntil(day: LocalDate?): TaskRepeat = copy(until = day, touched = true)

    companion object {
        fun of(task: Task?): TaskRepeat {
            val stored = task?.rrule ?: return TaskRepeat()
            val anchor = task.scheduledDay ?: task.dueDay
            return TaskRepeat(
                stored = stored,
                choice = TaskRepeatText.presetOf(stored, anchor),
                custom = TaskRepeatText.parse(stored),
                until = TaskRepeatText.untilDay(stored),
            )
        }
    }
}

/** Reads and writes the rules of the « Répéter » contract (MAG-887); the admin follows the same table. */
object TaskRepeatText {
    private val DAY_CODES = linkedMapOf(
        DayOfWeek.MONDAY to "MO", DayOfWeek.TUESDAY to "TU", DayOfWeek.WEDNESDAY to "WE",
        DayOfWeek.THURSDAY to "TH", DayOfWeek.FRIDAY to "FR", DayOfWeek.SATURDAY to "SA", DayOfWeek.SUNDAY to "SU",
    )
    private val CODE_DAYS = DAY_CODES.entries.associate { it.value to it.key }
    private val SHORT_DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.FRENCH)
    private val KNOWN_KEYS = setOf("FREQ", "INTERVAL", "BYDAY", "BYMONTHDAY", "UNTIL")

    fun dayName(day: DayOfWeek): String = day.getDisplayName(TextStyle.FULL, Locale.FRENCH)

    fun dayCode(day: DayOfWeek): String = DAY_CODES.getValue(day)

    fun defaultRule(anchor: LocalDate): RepeatRule = RepeatRule(RepeatUnit.WEEK, 1, setOf(anchor.dayOfWeek))

    fun presetRule(preset: RepeatPreset, anchor: LocalDate): RepeatRule = when (preset) {
        RepeatPreset.DAILY -> RepeatRule(RepeatUnit.DAY, 1)
        RepeatPreset.WEEKLY -> RepeatRule(RepeatUnit.WEEK, 1, setOf(anchor.dayOfWeek))
        RepeatPreset.BIWEEKLY -> RepeatRule(RepeatUnit.WEEK, 2, setOf(anchor.dayOfWeek))
        RepeatPreset.MONTHLY -> RepeatRule(RepeatUnit.MONTH, 1, monthDay = anchor.dayOfMonth)
        else -> defaultRule(anchor)
    }

    /** `FREQ;INTERVAL;BYDAY|BYMONTHDAY;UNTIL`, INTERVAL left out at 1, no `COUNT`. */
    fun write(rule: RepeatRule, anchor: LocalDate, until: LocalDate?): String {
        val parts = mutableListOf(
            when (rule.unit) {
                RepeatUnit.DAY -> "FREQ=DAILY"
                RepeatUnit.WEEK -> "FREQ=WEEKLY"
                RepeatUnit.MONTH -> "FREQ=MONTHLY"
            },
        )
        if (rule.interval > 1) parts += "INTERVAL=${rule.interval}"
        when (rule.unit) {
            RepeatUnit.DAY -> Unit
            RepeatUnit.WEEK -> {
                val days = rule.days.ifEmpty { setOf(anchor.dayOfWeek) }
                parts += "BYDAY=" + DAY_CODES.filterKeys { it in days }.values.joinToString(",")
            }
            RepeatUnit.MONTH -> parts += "BYMONTHDAY=${rule.monthDay ?: anchor.dayOfMonth}"
        }
        if (until != null) parts += "UNTIL=" + until.format(DateTimeFormatter.BASIC_ISO_DATE)
        return parts.joinToString(";")
    }

    /** [rrule] with its end day replaced by [until], or removed. */
    fun withUntil(rrule: String, until: LocalDate?): String =
        (rrule.split(";").filter { !it.startsWith("UNTIL=") } +
            listOfNotNull(until?.let { "UNTIL=" + it.format(DateTimeFormatter.BASIC_ISO_DATE) }))
            .joinToString(";")

    private fun parts(rrule: String): Map<String, String> =
        rrule.split(";").filter { it.contains("=") }.associate { it.substringBefore("=") to it.substringAfter("=") }

    fun untilDay(rrule: String?): LocalDate? {
        val raw = rrule?.let { parts(it)["UNTIL"] }?.substringBefore("T") ?: return null
        return runCatching { LocalDate.parse(raw, DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull()
    }

    /** The rule as the form can edit it; null when it falls outside the contract (the text falls back). */
    fun parse(rrule: String?): RepeatRule? {
        if (rrule == null) return null
        val p = parts(rrule)
        if (!KNOWN_KEYS.containsAll(p.keys)) return null
        val interval = if (p.containsKey("INTERVAL")) p["INTERVAL"]?.toIntOrNull()?.takeIf { it >= 1 } ?: return null else 1
        return when (p["FREQ"]) {
            "DAILY" -> if ("BYDAY" in p || "BYMONTHDAY" in p) null else RepeatRule(RepeatUnit.DAY, interval)
            "WEEKLY" -> {
                if ("BYMONTHDAY" in p) return null
                val days = p["BYDAY"]?.split(",")?.map { CODE_DAYS[it] ?: return null }?.toSet() ?: emptySet()
                RepeatRule(RepeatUnit.WEEK, interval, days)
            }
            "MONTHLY" -> {
                if ("BYDAY" in p) return null
                val day = if (p.containsKey("BYMONTHDAY")) p["BYMONTHDAY"]?.toIntOrNull()?.takeIf { it in 1..31 } ?: return null else null
                RepeatRule(RepeatUnit.MONTH, interval, monthDay = day)
            }
            else -> null
        }
    }

    /** The preset writing exactly [rrule] for [anchor], `UNTIL` aside; otherwise « Personnalisé… ». */
    fun presetOf(rrule: String?, anchor: LocalDate?): RepeatPreset {
        if (rrule == null) return RepeatPreset.NEVER
        if (anchor == null) return RepeatPreset.CUSTOM
        val bare = withUntil(rrule, null)
        return listOf(RepeatPreset.DAILY, RepeatPreset.WEEKLY, RepeatPreset.BIWEEKLY, RepeatPreset.MONTHLY)
            .firstOrNull { write(presetRule(it, anchor), anchor, null) == bare } ?: RepeatPreset.CUSTOM
    }

    /** The rule in plain words, `UNTIL` left out; [anchor] stands in for a missing `BYDAY` / `BYMONTHDAY`. */
    fun ruleText(rrule: String?, anchor: LocalDate?): String {
        if (rrule == null) return RepeatPreset.NEVER.label
        return plain(parse(rrule), anchor) ?: RruleUtils.rruleToFrenchText(withUntil(rrule, null))
    }

    fun ruleText(rule: RepeatRule, anchor: LocalDate?): String? = plain(rule, anchor)

    private fun plain(rule: RepeatRule?, anchor: LocalDate?): String? {
        rule ?: return null
        val n = rule.interval
        return when (rule.unit) {
            RepeatUnit.DAY -> if (n == 1) "Chaque jour" else "Tous les $n jours"
            RepeatUnit.WEEK -> {
                val days = rule.days.ifEmpty { listOfNotNull(anchor?.dayOfWeek).toSet() }
                if (days.isEmpty()) return null
                val names = DAY_CODES.keys.filter { it in days }.map(::dayName)
                val list = if (names.size == 1) names[0] else names.dropLast(1).joinToString(", ") + " et " + names.last()
                when {
                    n == 1 -> "Chaque $list"
                    n == 2 && names.size == 1 -> "Un $list sur deux"
                    else -> "Le $list, toutes les $n semaines"
                }
            }
            RepeatUnit.MONTH -> {
                val day = rule.monthDay ?: anchor?.dayOfMonth ?: return null
                val said = if (day == 1) "1er" else day.toString()
                if (n == 1) "Le $said de chaque mois" else "Le $said, tous les $n mois"
            }
        }
    }

    /** « , jusqu’au jeu. 31 déc. » on the sheet; null without an end day. */
    fun untilSuffix(rrule: String?): String? =
        untilDay(rrule)?.let { ", jusqu’au " + it.format(SHORT_DAY).lowercase(Locale.FRENCH) }

    /** The « Jusqu’au » field: « Aucune fin » or « Jeu. 31 déc. ». */
    fun untilLabel(day: LocalDate?): String =
        day?.format(SHORT_DAY)?.replaceFirstChar { it.titlecase(Locale.FRENCH) } ?: "Aucune fin"
}
