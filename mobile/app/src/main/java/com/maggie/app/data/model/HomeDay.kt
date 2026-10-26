package com.maggie.app.data.model

/** The day the Accueil shows (MAG-560): the selector's value, handed to every card. */
enum class HomeDay(val apiValue: String) {
    TODAY("today"),
    TOMORROW("tomorrow"),
}
