"""The facts a digest owes, and the check that the text states them (MAG-807)."""

import json

from app.digest_facts import Fact, format_hour, missing_facts, required_facts

DAY = "2030-11-14"


def call(name: str, result: dict | str) -> dict:
    return {"name": name, "input": {}, "result": result if isinstance(result, str) else json.dumps(result)}


def events(*items: dict) -> dict:
    return call("get_events_by_date", {"date": DAY, "events": list(items)})


def event(summary: str, start: str = f"{DAY}T09:15:00+01:00", **extra) -> dict:
    return {"summary": summary, "startAt": start, "allDay": False, "status": "confirmed", "agenda": "Défaut", **extra}


def test_a_timed_event_is_a_fact_with_its_hour():
    [fact] = required_facts([events(event("Kiné"))], DAY)

    assert (fact.kind, fact.label, fact.hour) == ("event", "Kiné", "09:15")
    assert fact.fallback == "Vous avez aussi Kiné à 9 h 15."


def test_the_events_of_maggie_and_repas_and_all_day_ones_are_not_facts():
    result = events(
        event("Rappel", agenda="Maggie"),
        event("Dîner", agenda="Repas"),
        event("Férié", start=DAY, allDay=True),
        event("Annulé", status="cancelled"),
        event("Hier", start="2030-11-13T09:00:00+01:00"),
    )

    assert required_facts([result], DAY) == []


def test_a_task_scheduled_today_is_a_fact_and_its_hour_when_timed():
    tasks = call(
        "get_tasks",
        {
            "tasks": [
                {"title": "Appeler le garage", "scheduledDate": DAY, "scheduledMoment": "time", "scheduledTime": "18:00"},
                {"title": "Préparer le weekend", "scheduledDate": DAY, "scheduledMoment": "evening"},
                {"title": "Faite", "scheduledDate": DAY, "isDone": True},
                {"title": "Demain", "scheduledDate": "2030-11-15"},
                {"title": "En retard", "scheduledDate": None},
            ]
        },
    )

    garage, weekend = required_facts([tasks], DAY)

    assert (garage.hour, garage.fallback) == ("18:00", "Vous avez aussi Appeler le garage à 18 h.")
    assert (weekend.hour, weekend.fallback) == (None, "Il reste aussi : Préparer le weekend.")


def test_a_meal_is_a_fact_named_by_its_recipes():
    meals = call(
        "manage_meals",
        {"meals": [{"date": DAY, "slot": "lunch", "recipes": [{"name": "Soupe de potiron"}, {"name": "Pain"}]}]},
    )

    [fact] = required_facts([meals], DAY)

    assert (fact.kind, fact.label) == ("meal", "Soupe de potiron et Pain")
    assert fact.fallback == "Au déjeuner : Soupe de potiron et Pain."


def test_a_weather_in_error_is_no_fact():
    ok = call("get_weather", {"days": [{"temperature_min_c": 9.5, "temperature_max_c": 18.5}]})

    assert required_facts([call("get_weather", {"error": "down"})], DAY) == []
    assert [f.fallback for f in required_facts([ok], DAY)] == ["Il fera entre 10 et 18 degrés."]


def test_a_fact_is_named_with_half_of_its_words_and_its_hour_in_the_same_sentence():
    fact = Fact("event", "Rendez-vous chez le dentiste", "09:15", "")

    assert missing_facts("Vous voyez le dentiste à 9 h 15.", [fact]) == []
    assert missing_facts("Rendez-vous dentiste à 9h15, puis le déjeuner.", [fact]) == []
    assert missing_facts("Le dentiste vous attend à 09:15", [fact]) == []
    assert missing_facts("Le dentiste, puis rien d'autre.", [fact]) == [fact]
    meal = Fact("meal", "Soupe de potiron", None, "")
    assert missing_facts("Au dîner, une soupe.", [meal]) == []
    assert missing_facts("Au dîner, des pâtes.", [meal]) == [meal]
    weather = Fact("weather", "météo", None, "")
    assert missing_facts("Il fera 12 degrés.", [weather]) == []
    assert missing_facts("Il fera 12 °C.", [weather]) == []
    assert missing_facts("Il fera beau.", [weather]) == [weather]


def test_an_hour_in_another_sentence_does_not_count():
    fact = Fact("event", "Kiné", "09:15", "")

    assert missing_facts("Vous avez Kiné. Le café est à 9 h 15.", [fact]) == [fact]
    assert missing_facts("Vous avez Kiné à 9 h 150.", [fact]) == [fact]
    assert missing_facts("Kiné à 9 h.", [Fact("event", "Kiné", "09:00", "")]) == []


def test_format_hour():
    assert format_hour("09:15") == "9 h 15"
    assert format_hour("18:00") == "18 h"
    assert format_hour("07:05") == "7 h 05"
