package com.maggie.app.ui.screens.tasks

/**
 * The entries of the tasks menu (MAG-934), beside « Routines »: « Tâches » is « À venir » and « Un jour » read
 * together, « Archives » is « Faites ». [key] is the menu entry's key (`breadcrumb_menu_<key>`).
 */
enum class TasksSection(val key: String, val label: String) {
    TODAY("today", "Aujourd'hui"),
    STOCK("stock", "Tâches"),
    ARCHIVES("done", "Archives"),
}
