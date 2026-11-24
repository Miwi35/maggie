package com.maggie.app.ui.screens.finance

import com.maggie.app.data.model.PlannedProject
import com.maggie.app.data.model.Project
import com.maggie.app.data.model.ProjectDateMode
import com.maggie.app.data.model.projectDependenciesLabel
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ROW_DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRANCE)
private val ROW_DEADLINE = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.FRANCE)
private val ROW_MONTH = DateTimeFormatter.ofPattern("MMM yyyy", Locale.FRANCE)

/** The tint of a row's date pill: orange for a fixed date, blue for a deadline, grey without a date. */
internal enum class DatePillTone { WARNING, INFO, NEUTRAL }

private fun datedMode(project: Project): Pair<ProjectDateMode, LocalDate>? {
    val mode = ProjectDateMode.of(project.dateMode)
    val date = project.targetOn?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
    return if (mode == ProjectDateMode.NONE || date == null) null else mode to date
}

/** « Date fixe · 1 mars 2027 », « Au plus tard · août 2027 », « Sans date », « Sans date · après Déménagement ». */
internal fun projectRowDateLabel(project: Project, all: List<Project>): String {
    val dated = datedMode(project)
    val base = when (dated?.first) {
        ProjectDateMode.FIXED -> "Date fixe · ${ROW_DATE.format(dated.second)}"
        ProjectDateMode.DEADLINE -> "Au plus tard · ${ROW_DEADLINE.format(dated.second)}"
        else -> "Sans date"
    }
    val after = projectDependenciesLabel(project, all) ?: return base
    return "$base · $after"
}

internal fun projectDatePillTone(project: Project): DatePillTone = when (datedMode(project)?.first) {
    ProjectDateMode.FIXED -> DatePillTone.WARNING
    ProjectDateMode.DEADLINE -> DatePillTone.INFO
    else -> DatePillTone.NEUTRAL
}

/** « mars 2027 » from the planned purchase month; null past the horizon or without planning. */
internal fun projectRowMonthLabel(planned: PlannedProject?): String? =
    planned?.purchaseMonth?.let { month ->
        runCatching { YearMonth.parse(month.take(7)).format(ROW_MONTH) }.getOrNull()
    }

/** What is gathered over the amount, between 0 and 1; null without planning or for an amount of 0. */
internal fun projectGatheredShare(planned: PlannedProject?, amountCents: Int): Float? {
    if (planned == null || amountCents <= 0) return null
    return (planned.gatheredCents.toFloat() / amountCents).coerceIn(0f, 1f)
}
