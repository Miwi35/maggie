"""Auto-generate a capability summary from loaded tool definitions."""

import re

_VERB_PREFIXES = re.compile(
    r"^(create|get|search|list|update|delete|add|remove|store|schedule|set|cancel|complete|check|end|move|manage|assign|generate)_"
)

# Maps a domain noun to (category_label, description)
_CATEGORY_MAP: dict[str, tuple[str, str]] = {
    "event": ("Calendrier", "consulter, créer, modifier et supprimer des événements"),
    "task": ("Calendrier", "gérer les tâches"),
    "recipe": ("Cuisine", "chercher, créer et modifier des recettes"),
    "ingredient": ("Cuisine", "gérer les ingrédients"),
    "product": ("Cuisine", "gérer les produits"),
    "meal": ("Cuisine", "planifier les repas"),
    "grocery": ("Cuisine", "gérer la liste de courses"),
    "errand": ("Cuisine", "gérer la liste de courses"),
    "fallback": ("Cuisine", "gérer la liste de courses"),
    "store": ("Cuisine", "gérer les magasins"),
    "stores": ("Cuisine", "gérer les magasins"),
    "memory": ("Mémoire", "retenir et retrouver des informations sur l'utilisateur"),
    "instruction": ("Directives", "enregistrer des règles de planification et des préférences de ton"),
    "skill": ("Compétences", "apprendre de nouvelles procédures"),
    "proaction": ("Proactions", "programmer des rappels et tâches autonomes"),
    "delegate": ("Délégation", "confier une tâche de recherche à un sous-agent"),
    "search": ("Recherche", "recherche plein texte dans toutes les données"),
}


def _extract_noun(tool_name: str) -> str:
    """Strip verb prefix to get the domain noun.

    Examples:
        create_event -> event
        get_events_by_date -> event
        search -> search
        add_recurring_grocery -> recurring_grocery
    """
    noun = _VERB_PREFIXES.sub("", tool_name)
    # Strip trailing _by_* qualifiers (e.g. events_by_date -> events)
    noun = re.sub(r"_by_\w+$", "", noun)
    return noun


def _normalize_noun(noun: str) -> str:
    """Normalize a noun to match a known category.

    Handles plurals (events -> event) and compound nouns
    (recurring_grocery -> grocery) by checking components against known keys.
    """
    # Direct match
    if noun in _CATEGORY_MAP:
        return noun

    # Try stripping trailing 's' for plurals
    if noun.endswith("s") and noun[:-1] in _CATEGORY_MAP:
        return noun[:-1]

    # For compound nouns, check each component against known categories
    parts = noun.split("_")
    for part in reversed(parts):
        if part in _CATEGORY_MAP:
            return part
        if part.endswith("s") and part[:-1] in _CATEGORY_MAP:
            return part[:-1]

    # Return as-is for unknown domains
    return noun


def generate_capability_summary(tools: list[dict] | None) -> str:
    """Group tools by domain category and return a concise French summary.

    Unknown tools (from future modules) auto-label from the noun — zero-edit scaling.
    """
    if not tools:
        return ""

    # Group tools by category
    categories: dict[str, dict] = {}  # label -> {descriptions: set, count: int}

    for tool in tools:
        name = tool.get("name", "")
        noun = _normalize_noun(_extract_noun(name))

        if noun in _CATEGORY_MAP:
            label, desc = _CATEGORY_MAP[noun]
        else:
            # Auto-label from the noun
            label = noun.replace("_", " ").title()
            desc = f"gérer {noun.replace('_', ' ')}"

        if label not in categories:
            categories[label] = {"descriptions": set(), "count": 0}
        categories[label]["descriptions"].add(desc)
        categories[label]["count"] += 1

    # Build summary lines
    lines = ["Capacités disponibles :"]
    for label, info in categories.items():
        n = info["count"]
        unit = "outil" if n == 1 else "outils"
        descs = ", ".join(sorted(info["descriptions"]))
        lines.append(f"- {label} ({n} {unit}) : {descs}")

    return "\n".join(lines)
