"""« Partir à l'heure » on the agent's side (MAG-657): the morning card of the day's trips and the
replan. The API computes and keeps the trips (estimate_trip, set_trip, the departure alert, and since
MAG-730 the checks before each departure); the model chooses origin and mode, and these texts say how."""

# The proactions « Check départ · <titre> » the planning used to post (MAG-730): the API's cron checks
# the trips now, the ones still scheduled run without the model and do nothing.
RETIRED_CHECK_SUMMARY_PREFIX = "Check départ · "
REPLAN_SUMMARY = "Replanifier les trajets"
QUESTION_SUMMARY_PREFIX = "Où partez-vous · "
PARKING_SUMMARY_PREFIX = "Où se garer · "
ARRIVAL_SUMMARY_PREFIX = "Arrivée · "
RETURN_SUMMARY_PREFIX = "Retour · "

# A departAt that moves by less than this is not worth a new set_trip.
DEPART_TOLERANCE_MINUTES = 2


def is_retired_trip_check(summary: str | None) -> bool:
    return bool(summary) and summary.startswith(RETIRED_CHECK_SUMMARY_PREFIX)


def is_trip_replan(summary: str | None) -> bool:
    return summary == REPLAN_SUMMARY


def is_silent_trip_run(summary: str | None) -> bool:
    """A replan works on the trips and says nothing in the chat: the departure alert is the API's."""
    return is_retired_trip_check(summary) or is_trip_replan(summary)


def planning_trips_rules(day: str) -> str:
    """Step 5 of the daily planning: the know-how is the shipped `trajets` skill."""
    return (
        f'5. Trajets du jour (`list_trips` avec date="{day}") : suis la compétence trajets '
        "(charge-la avec get_skill si elle n'est pas plus bas). "
        "Ne programme aucun check : l'API vérifie avant chaque départ."
    )


def trip_run_rules() -> str:
    """The preamble of a replan, which replaces the execution preamble: no message for the chat."""
    return (
        "\n\nTu es en mode proaction sur les trajets. "
        "L'API a déjà recalculé les trajets du jour qui tiennent encore. Le prompt cite les événements qui "
        "restent à traiter : replanifie ceux-là, et seulement ceux-là. "
        "Ton output est un log interne : il n'est pas envoyé à l'utilisateur, l'alerte de départ est celle de l'API. "
        "1. Lis les trajets enregistrés (list_trips) et les événements du jour. "
        "2. Pour chaque événement cité dans le prompt (sans trajet, l'origine et le mode se choisissent comme dit "
        "la compétence trajets, get_skill), recalcule-le depuis la position réelle : estimate_trip avec "
        "l'origine `position` — sauf si originDecidedBy=user, dont l'origine reste celle de l'utilisateur "
        "(list_trips la donne en kind et id : `position`, `place:<id>` ou `event:<id>`). "
        f"3. Si le departAt recalculé a bougé de plus de {DEPART_TOLERANCE_MINUTES} minutes, "
        "ou si le mode choisi n'est plus le meilleur, "
        "enregistre-le avec set_trip, et pour un trajet d'origine choisie par l'utilisateur avec la même origine et "
        "decidedBy=user (sans quoi son choix serait perdu) ; sinon ne touche à rien. "
        "Un trajet de retour (leg=return) garde le departAt que l'utilisateur a fixé : l'API recalcule seule sa durée, "
        "n'y touche pas. "
        "Ne demande pas de confirmation : agis directement."
    )
