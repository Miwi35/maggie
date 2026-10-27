"""« Partir à l'heure » on the agent's side (MAG-657): the morning card of the day's trips, the
checks before each departure, and the replan. The API computes and keeps the trips (estimate_trip,
set_trip, the departure alert); the model chooses origin and mode, and these texts say how."""

CHECK_SUMMARY_PREFIX = "Check départ · "
REPLAN_SUMMARY = "Replanifier les trajets"
QUESTION_SUMMARY_PREFIX = "Où pars-tu · "
PARKING_SUMMARY_PREFIX = "Où se garer · "

# Minutes before departAt at which Maggie looks again.
CHECK_OFFSETS = (30, 10)
# A departAt that moves by less than this is not worth a new set_trip.
DEPART_TOLERANCE_MINUTES = 2
# Time on the spot, after the round trip, from which going back home beats chaining.
HOME_STAY_MINUTES = 30


def check_summary(title: str) -> str:
    return f"{CHECK_SUMMARY_PREFIX}{title}"


def is_trip_check(summary: str | None) -> bool:
    return bool(summary) and summary.startswith(CHECK_SUMMARY_PREFIX)


def is_trip_replan(summary: str | None) -> bool:
    return summary == REPLAN_SUMMARY


def is_silent_trip_run(summary: str | None) -> bool:
    """A check or a replan works on the trips and says nothing in the chat: the departure alert is the API's."""
    return is_trip_check(summary) or is_trip_replan(summary)


def planning_trips_rules(day: str) -> str:
    """The morning card, appended to the daily planning prompt."""
    return (
        f"5. Trajets du jour. Pour chaque événement du {day} qui a un lieu et qui n'a pas encore de trajet "
        f'(list_trips avec date="{day}"), dans l\'ordre des heures : '
        "a) Origine. Si l'utilisateur a répondu la veille (un trajet `originDecidedBy=user`), "
        "garde son origine : même origine et decidedBy=user dans set_trip. "
        "Sinon suis ses habitudes (list_instructions, ta mémoire). "
        "Sinon : le premier trajet de la journée part de `position` (l'endroit où le téléphone était en dernier) ; "
        f"pour les suivants, `place:<id>` de la maison (list_places) s'il reste au moins {HOME_STAY_MINUTES} minutes "
        "sur place après l'aller-retour, sinon `event:<id>` de l'événement précédent (tu enchaînes). "
        "b) Mode. Appelle estimate_trip avec l'origine et les modes plausibles (drive, transit, walk, bicycle : "
        "pas bicycle sous la pluie, pas walk à plus de 3 km), compare les durées et choisis. "
        "c) Enregistre le choix avec set_trip (eventId, mode, origin) ; ne mets decidedBy=user que pour une origine "
        "que l'utilisateur a lui-même choisie. "
        "d) En voiture, si le trajet répond parkingKnown=false, dis-le à l'utilisateur en lui demandant où il se gare "
        f"(proaction non silencieuse programmée dans quelques minutes, résumé « {PARKING_SUMMARY_PREFIX}<titre> »), "
        "puis enregistre sa réponse avec save_place. "
        "e) Si le doute est réel (rentrer à la maison ou enchaîner, deux modes à égalité), estime chaque choix "
        "(estimate_trip pour chaque origine) pour connaître son departAt, enregistre quand même "
        "le meilleur choix par défaut, puis pose une question avec schedule_proaction dans quelques minutes (résumé "
        f"« {QUESTION_SUMMARY_PREFIX}<titre> ») : « Je repasse à la maison ou j'enchaîne ? » avec, pour chaque choix, "
        "l'heure de départ qu'il donne ; il peut répondre « Plus tard ». "
        "f) Pose deux checks avec schedule_proaction, à departAt moins "
        f"{CHECK_OFFSETS[0]} minutes et à departAt moins {CHECK_OFFSETS[1]} minutes (heure de Paris), "
        f"avec exactement le résumé « {CHECK_SUMMARY_PREFIX}<titre de l'événement> » et pour prompt : "
        "le titre, l'id de l'événement et la consigne de recalculer le trajet depuis la position réelle. "
        "Un check dont l'heure est déjà passée ne se pose pas. "
        "Ne repose ni trajet ni check qui existent déjà."
    )


def trip_run_rules(summary: str) -> str:
    """The preamble of a check or a replan, which replaces the execution preamble: no message for the chat."""
    if is_trip_replan(summary):
        goal = (
            "Les trajets du jour ne tiennent plus (un événement a bougé, l'utilisateur ou sa voiture est ailleurs). "
            "Replanifie-les. "
        )
    else:
        goal = f"C'est un {summary.split(' · ')[0].lower()} : le départ approche, vérifie que le trajet tient encore. "

    return (
        "\n\nTu es en mode proaction sur les trajets. "
        + goal
        + "Ton output est un log interne : il n'est pas envoyé à l'utilisateur, l'alerte de départ est celle de l'API. "
        "1. Lis les trajets enregistrés (list_trips) et les événements du jour. "
        "2. Pour chaque trajet qui n'est pas passé, recalcule-le depuis la position réelle : estimate_trip avec "
        "l'origine `position` — sauf si originDecidedBy=user, dont l'origine reste celle de l'utilisateur "
        "(list_trips la donne en kind et id : `position`, `place:<id>` ou `event:<id>`). "
        f"3. Si le departAt recalculé a bougé de plus de {DEPART_TOLERANCE_MINUTES} minutes, "
        "ou si le mode choisi n'est plus le meilleur, "
        "enregistre-le avec set_trip, et pour un trajet d'origine choisie par l'utilisateur avec la même origine et "
        "decidedBy=user (sans quoi son choix serait perdu) ; sinon ne touche à rien. "
        "4. Si departAt a bougé, les checks de départ de cet événement ne sont plus à la bonne heure : "
        "annule-les (list_proactions, puis cancel_proaction sur ceux à venir dont le résumé est "
        f"« {CHECK_SUMMARY_PREFIX}<titre> » et dont le prompt cite l'id de cet événement) "
        "et reprogramme-les avec schedule_proaction, "
        f"à departAt moins {CHECK_OFFSETS[0]} et moins {CHECK_OFFSETS[1]} minutes, même résumé. "
        "Ne programme pas de nouveau check pour un événement qui en a déjà à la bonne heure. "
        "Ne demande pas de confirmation : agis directement."
    )
