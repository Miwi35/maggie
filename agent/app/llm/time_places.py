"""Place name → IANA timezone, for the date_time tool.

Resolution order: « here », an IANA name, a curated name (French or English city, region or country),
then the last component of an IANA name (« tokyo », « new york »). Nothing here stores an offset: the
offset and the daylight saving time of a date always come from zoneinfo.

A country that spans several timezones is not guessed: the tool says so and lists the candidates, so
the model asks the user for a city rather than answering with the wrong hour.
"""

import re
import unicodedata
from functools import lru_cache
from zoneinfo import ZoneInfo, available_timezones

# Legacy fixed-offset zones: « EST » in July is New York's UTC-4, not the fixed UTC-5 these would give.
_FIXED_OFFSET_ABBREVIATIONS = {"est": "America/New_York", "mst": "America/Denver", "hst": "Pacific/Honolulu"}
HERE_WORDS = {"ici", "chez moi", "local", "locale", "maison", "here", "home", "moi"}
_LEADING_WORDS = {"a", "au", "aux", "en", "le", "la", "les", "l", "de", "du", "des", "d", "the", "in", "at"}

# Places whose name is not an IANA city. One zone each.
_SINGLE: dict[str, tuple[str, ...]] = {
    "Europe/Paris": ("paris", "france", "lyon", "marseille", "bordeaux", "lille", "toulouse", "nantes", "nice"),
    "America/Martinique": ("fort de france", "martinique", "schoelcher", "le lamentin"),
    "America/Guadeloupe": ("pointe a pitre", "guadeloupe", "basse terre"),
    "America/Cayenne": ("cayenne", "guyane", "guyane francaise"),
    "Indian/Reunion": ("reunion", "ile de la reunion", "saint denis de la reunion"),
    "Indian/Mayotte": ("mayotte", "mamoudzou"),
    "Pacific/Noumea": ("noumea", "nouvelle caledonie"),
    "Pacific/Tahiti": ("papeete", "tahiti", "polynesie", "polynesie francaise"),
    "Pacific/Wallis": ("wallis", "wallis et futuna"),
    "America/Miquelon": ("saint pierre et miquelon", "miquelon"),
    "UTC": ("utc", "gmt", "temps universel", "heure universelle"),
    "Europe/London": ("londres", "royaume uni", "angleterre", "ecosse", "uk", "grande bretagne"),
    "Europe/Dublin": ("dublin", "irlande"),
    "Europe/Lisbon": ("lisbonne", "portugal"),
    "Atlantic/Canary": ("canaries", "iles canaries", "tenerife"),
    "Europe/Madrid": ("madrid", "espagne", "barcelone", "seville", "valence"),
    "Europe/Berlin": ("berlin", "allemagne", "munich", "francfort", "hambourg"),
    "Europe/Brussels": ("bruxelles", "belgique", "anvers"),
    "Europe/Amsterdam": ("amsterdam", "pays bas", "hollande"),
    "Europe/Luxembourg": ("luxembourg",),
    "Europe/Zurich": ("zurich", "geneve", "berne", "suisse"),
    "Europe/Vienna": ("vienne", "autriche"),
    "Europe/Rome": ("rome", "italie", "milan", "naples", "venise"),
    "Europe/Athens": ("athenes", "grece"),
    "Europe/Istanbul": ("istanbul", "turquie", "ankara"),
    "Europe/Moscow": ("moscou", "saint petersbourg"),
    "Europe/Kyiv": ("kiev", "kyiv", "ukraine"),
    "Europe/Stockholm": ("stockholm", "suede"),
    "Europe/Oslo": ("oslo", "norvege"),
    "Europe/Copenhagen": ("copenhague", "danemark"),
    "Europe/Helsinki": ("helsinki", "finlande"),
    "Europe/Warsaw": ("varsovie", "pologne"),
    "Europe/Prague": ("prague", "tchequie", "republique tcheque"),
    "Europe/Budapest": ("budapest", "hongrie"),
    "Europe/Bucharest": ("bucarest", "roumanie"),
    "Europe/Sofia": ("sofia", "bulgarie"),
    "Atlantic/Reykjavik": ("reykjavik", "islande"),
    "Africa/Casablanca": ("casablanca", "rabat", "maroc", "marrakech"),
    "Africa/Algiers": ("alger", "algerie"),
    "Africa/Tunis": ("tunis", "tunisie"),
    "Africa/Cairo": ("le caire", "caire", "egypte"),
    "Africa/Dakar": ("dakar", "senegal"),
    "Africa/Abidjan": ("abidjan", "cote d ivoire"),
    "Africa/Lagos": ("lagos", "nigeria"),
    "Africa/Nairobi": ("nairobi", "kenya"),
    "Africa/Johannesburg": ("johannesbourg", "afrique du sud", "le cap"),
    "Africa/Kinshasa": ("kinshasa",),
    "Indian/Antananarivo": ("antananarivo", "tananarive", "madagascar"),
    "Indian/Mauritius": ("maurice", "ile maurice", "port louis"),
    "America/New_York": ("new york", "new york city", "nyc", "boston", "miami", "atlanta"),
    "America/Chicago": ("chicago", "houston", "dallas", "la nouvelle orleans"),
    "America/Denver": ("denver", "salt lake city"),
    "America/Los_Angeles": ("los angeles", "san francisco", "californie", "seattle", "las vegas", "san diego"),
    "America/Phoenix": ("phoenix", "arizona"),
    "Pacific/Honolulu": ("honolulu", "hawai", "hawaii"),
    "America/Anchorage": ("anchorage", "alaska"),
    "America/Toronto": ("montreal", "quebec", "toronto", "ottawa"),
    "America/Vancouver": ("vancouver", "colombie britannique"),
    "America/Halifax": ("halifax", "nouvelle ecosse"),
    "America/Mexico_City": ("mexico", "ville de mexico", "mexique"),
    "America/Havana": ("la havane", "havane", "cuba"),
    "America/Port-au-Prince": ("port au prince", "haiti"),
    "America/Santo_Domingo": ("saint domingue", "republique dominicaine"),
    "America/Bogota": ("bogota", "colombie"),
    "America/Lima": ("lima", "perou"),
    "America/Santiago": ("santiago", "chili"),
    "America/Argentina/Buenos_Aires": ("buenos aires", "argentine"),
    "America/Sao_Paulo": ("sao paulo", "rio", "rio de janeiro", "brasilia"),
    "America/Caracas": ("caracas", "venezuela"),
    "America/Montevideo": ("montevideo", "uruguay"),
    "America/Panama": ("panama",),
    "America/Costa_Rica": ("costa rica",),
    "Asia/Tokyo": ("tokyo", "japon", "osaka", "kyoto"),
    "Asia/Seoul": ("seoul", "coree du sud", "coree"),
    "Asia/Shanghai": ("pekin", "beijing", "shanghai", "chine", "canton", "shenzhen"),
    "Asia/Hong_Kong": ("hong kong",),
    "Asia/Singapore": ("singapour",),
    "Asia/Bangkok": ("bangkok", "thailande"),
    "Asia/Ho_Chi_Minh": ("hanoi", "ho chi minh ville", "saigon", "vietnam"),
    "Asia/Jakarta": ("jakarta", "indonesie"),
    "Asia/Manila": ("manille", "philippines"),
    "Asia/Kuala_Lumpur": ("kuala lumpur", "malaisie"),
    "Asia/Kolkata": ("delhi", "new delhi", "mumbai", "bombay", "calcutta", "inde", "bangalore"),
    "Asia/Dubai": ("dubai", "emirats arabes unis", "emirats", "abu dhabi"),
    "Asia/Qatar": ("doha", "qatar"),
    "Asia/Riyadh": ("riyad", "arabie saoudite"),
    "Asia/Tehran": ("teheran", "iran"),
    "Asia/Jerusalem": ("tel aviv", "jerusalem", "israel"),
    "Asia/Beirut": ("beyrouth", "liban"),
    "Asia/Karachi": ("karachi", "pakistan"),
    "Asia/Dhaka": ("dacca", "bangladesh"),
    "Asia/Kathmandu": ("katmandou", "nepal"),
    "Asia/Colombo": ("colombo", "sri lanka"),
    "Asia/Taipei": ("taipei", "taiwan"),
    "Australia/Sydney": ("sydney", "melbourne", "canberra"),
    "Australia/Brisbane": ("brisbane", "queensland"),
    "Australia/Perth": ("perth",),
    "Australia/Adelaide": ("adelaide",),
    "Pacific/Auckland": ("auckland", "wellington", "nouvelle zelande"),
    "Pacific/Fiji": ("fidji",),
}

# Countries that span several timezones: no guess, the candidates go back to the model.
_MULTI: dict[str, tuple[str, ...]] = {
    "etats unis": ("America/New_York", "America/Chicago", "America/Denver", "America/Los_Angeles"),
    "usa": ("America/New_York", "America/Chicago", "America/Denver", "America/Los_Angeles"),
    "us": ("America/New_York", "America/Chicago", "America/Denver", "America/Los_Angeles"),
    "united states": ("America/New_York", "America/Chicago", "America/Denver", "America/Los_Angeles"),
    "canada": ("America/Toronto", "America/Winnipeg", "America/Edmonton", "America/Vancouver", "America/Halifax"),
    "russie": ("Europe/Moscow", "Asia/Yekaterinburg", "Asia/Novosibirsk", "Asia/Vladivostok"),
    "bresil": ("America/Sao_Paulo", "America/Manaus", "America/Noronha"),
    "washington": ("America/New_York", "America/Los_Angeles"),
    "san jose": ("America/Costa_Rica", "America/Los_Angeles"),
    "floride": ("America/New_York", "America/Chicago"),
    "australie": ("Australia/Sydney", "Australia/Perth", "Australia/Adelaide", "Australia/Brisbane"),
}


class PlaceError(Exception):
    """The place does not name one timezone. `candidates` lists the IANA names it could be, if any."""

    def __init__(self, message: str, candidates: tuple[str, ...] = ()):
        super().__init__(message)
        self.candidates = candidates


def normalize(text: str) -> str:
    """Lowercase, no accents, no punctuation, no leading article: « À Fort-de-France » → « fort de france »."""
    text = unicodedata.normalize("NFKD", text).encode("ascii", "ignore").decode()
    words = re.sub(r"[^a-z0-9]+", " ", text.lower()).split()
    while len(words) > 1 and words[0] in _LEADING_WORDS:
        words.pop(0)
    return " ".join(words)


@lru_cache(maxsize=1)
def _iana_by_lowercase() -> dict[str, str]:
    return {name.lower(): name for name in available_timezones() if not name.startswith(("Etc/", "posix/", "right/"))}


@lru_cache(maxsize=1)
def _iana_by_city() -> dict[str, str]:
    cities: dict[str, str] = {}
    for name in sorted(_iana_by_lowercase().values()):
        if "/" in name:
            cities.setdefault(normalize(name.rsplit("/", 1)[1]), name)
    return cities


@lru_cache(maxsize=1)
def _curated() -> dict[str, str]:
    return {normalize(place): zone for zone, places in _SINGLE.items() for place in places}


def resolve_place(place: str, here: ZoneInfo) -> ZoneInfo:
    """The timezone a place name stands for; `here` is the user's own. Raises PlaceError when it is not one zone."""
    raw = (place or "").strip()
    if not raw or normalize(raw) in HERE_WORDS:
        return here

    if raw.lower() in _FIXED_OFFSET_ABBREVIATIONS:
        city = _FIXED_OFFSET_ABBREVIATIONS[raw.lower()]
        raise PlaceError(f"'{raw}' is a fixed offset that ignores summer time: give a city instead.", (city,))

    by_name = _iana_by_lowercase().get(raw.lower())
    if by_name:
        return ZoneInfo(by_name)

    key = normalize(raw)
    if key in _MULTI:
        raise PlaceError(f"'{raw}' spans several timezones: ask for a city.", _MULTI[key])

    zone = _curated().get(key) or _iana_by_city().get(key)
    if zone is None:
        raise PlaceError(f"Unknown place '{raw}': give a city or an IANA timezone name (e.g. Europe/Paris).")
    try:
        return ZoneInfo(zone)
    except Exception as e:
        raise PlaceError(f"Timezone '{zone}' for '{raw}' is not available here: {e}") from e
