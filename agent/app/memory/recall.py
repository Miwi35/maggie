"""Level 2 of the recall (MAG-16): what comes back on its own at each turn.

A quick search over the **cartes** alone — never the text of the files — that brings back at most
`memory_recall_max` memories scoring at least `memory_recall_threshold`. What makes a memory come back
without a word in common is its `evoque` (the situations it is for), its aliases and its people:
« un bouquet pour la fête des mères » surfaces « Françoise n'aime pas les roses » because that fiche
evokes « fête des mères ». The title, the summary and the dates of the moments weigh less.

No embeddings, no model: normalised words (accents, case, plural, elision), a handful of weights, a
threshold. It runs on the answer's path — voice included — so it is bounded: `recall_for_turn` gives up
after `memory_recall_timeout_seconds` and the turn goes on without it.
"""

import asyncio
import logging
import re
import time
import unicodedata
from dataclasses import dataclass, field
from datetime import date, timedelta
from difflib import SequenceMatcher
from itertools import pairwise

from app.config import settings
from app.db.memory_index_repository import CardView, memory_index_repo
from app.memory import conversations, paths
from app.memory.service import memory_service
from app.metrics import MEMORY_RECALL_OUTCOMES, MEMORY_RECALL_SECONDS, MEMORY_RECALLED_CARTES

logger = logging.getLogger(__name__)

W_EVOQUE = 3.0  # a situation the carte is for is in the message
W_ALIAS = 3.0  # another name of its subject is in the message (« Lubus » for « l'UBU »)
W_PERSON = 2.5  # someone it concerns is named
W_DAY = 3.0  # a moment of the carte falls on the day the message is about
W_NAME = 2.5  # a proper name of the message is in the title or the summary
W_TITLE = 1.2  # any other word of the title
W_RESUME = 0.6  # any other word of the summary
CONVERSATION_FACTOR = 0.7  # a fiche is knowledge, a conversation is where it came from
MAX_PEOPLE = 2
FUZZY_RATIO = 0.85
FUZZY_MIN_LENGTH = 4

_WORD = re.compile(r"[^\W_]+", re.UNICODE)
_SENTENCE_END = re.compile(r"(^|[.!?…\n])\s*$")
_ISO_DAY = re.compile(r"\b(\d{4})-(\d{2})-(\d{2})\b")
_DAY_MONTH = re.compile(r"\b(\d{1,2})(?:er)?\s+([a-zéèêûîô]+)\.?(?:\s+(\d{4}))?", re.IGNORECASE)

MONTHS = {
    name: number
    for number, names in enumerate(
        (
            ("janvier", "janv"),
            ("fevrier", "fevr", "fev"),
            ("mars",),
            ("avril", "avr"),
            ("mai",),
            ("juin",),
            ("juillet", "juil"),
            ("aout",),
            ("septembre", "sept"),
            ("octobre", "oct"),
            ("novembre", "nov"),
            ("decembre", "dec"),
        ),
        start=1,
    )
    for name in names
}
WEEKDAYS = {"lundi": 0, "mardi": 1, "mercredi": 2, "jeudi": 3, "vendredi": 4, "samedi": 5, "dimanche": 6}

# Words that carry no subject: function words, the verbs everyone uses, and the commands given to an assistant.
STOPWORDS = frozenset(
    [
        "le",
        "la",
        "les",
        "un",
        "une",
        "des",
        "du",
        "de",
        "dans",
        "sur",
        "sous",
        "avec",
        "sans",
        "pour",
        "par",
        "chez",
        "vers",
        "entre",
        "contre",
        "selon",
        "et",
        "ou",
        "ni",
        "mais",
        "donc",
        "car",
        "que",
        "qui",
        "quoi",
        "dont",
        "quand",
        "comme",
        "si",
        "alors",
        "puis",
        "aussi",
        "encore",
        "deja",
        "toujours",
        "jamais",
        "je",
        "tu",
        "il",
        "elle",
        "on",
        "nous",
        "vous",
        "ils",
        "elles",
        "me",
        "te",
        "se",
        "moi",
        "toi",
        "lui",
        "eux",
        "leur",
        "leurs",
        "mon",
        "ton",
        "son",
        "ma",
        "ta",
        "sa",
        "mes",
        "tes",
        "ses",
        "notre",
        "votre",
        "nos",
        "vos",
        "ce",
        "cet",
        "cette",
        "ces",
        "ca",
        "cela",
        "ceci",
        "celui",
        "celle",
        "ici",
        "est",
        "es",
        "suis",
        "sommes",
        "etes",
        "sont",
        "etre",
        "ete",
        "etait",
        "serai",
        "sera",
        "seront",
        "soit",
        "ai",
        "as",
        "avons",
        "avez",
        "ont",
        "avoir",
        "eu",
        "avait",
        "aura",
        "auront",
        "fait",
        "fais",
        "faisons",
        "faites",
        "font",
        "faire",
        "fera",
        "va",
        "vas",
        "vais",
        "allons",
        "allez",
        "vont",
        "aller",
        "ira",
        "irai",
        "veux",
        "veut",
        "voulons",
        "voulez",
        "veulent",
        "vouloir",
        "voudrais",
        "voudrait",
        "peux",
        "peut",
        "pouvons",
        "pouvez",
        "peuvent",
        "pouvoir",
        "pourrais",
        "sais",
        "sait",
        "savons",
        "savez",
        "savent",
        "savoir",
        "dis",
        "dit",
        "dire",
        "dites",
        "disons",
        "aime",
        "aimes",
        "aimons",
        "aimez",
        "aiment",
        "aimer",
        "aimait",
        "aimerais",
        "faut",
        "falloir",
        "faudra",
        "faudrait",
        "vois",
        "voit",
        "voir",
        "vu",
        "mets",
        "met",
        "mettre",
        "mis",
        "donne",
        "donner",
        "donnes",
        "donnez",
        "prends",
        "prend",
        "prendre",
        "pris",
        "pense",
        "penses",
        "penser",
        "trouve",
        "trouves",
        "trouver",
        "parle",
        "parles",
        "parler",
        "dois",
        "doit",
        "devoir",
        "devons",
        "devez",
        "doivent",
        "ajoute",
        "ajouter",
        "ajoutes",
        "cree",
        "creer",
        "crees",
        "cherche",
        "chercher",
        "cherches",
        "montre",
        "montrer",
        "montres",
        "rappelle",
        "rappeler",
        "stp",
        "svp",
        "merci",
        "bonjour",
        "salut",
        "pas",
        "plus",
        "moins",
        "tres",
        "trop",
        "assez",
        "bien",
        "mal",
        "tout",
        "tous",
        "toute",
        "toutes",
        "autre",
        "autres",
        "meme",
        "chose",
        "choses",
        "truc",
        "trucs",
        "fois",
        "quelque",
        "quelques",
        "quel",
        "quelle",
        "quels",
        "quelles",
        "oui",
        "non",
        "peu",
        "beaucoup",
        "deux",
        "trois",
        "y",
        "en",
        "a",
        "au",
        "aux",
        "demain",
        "hier",
        "aujourd",
        "hui",
        "apres",
        "soir",
        "matin",
    ]
)


def fold(text: str) -> str:
    """Lower case, no accents, straight apostrophes: how words are compared."""
    text = unicodedata.normalize("NFD", text.casefold().replace("\u2019", "'"))
    return "".join(c for c in text if not unicodedata.combining(c))


def stem(word: str) -> str:
    """The word without its plural: « mères » and « mère », « concerts » and « concert » meet."""
    return word[:-1] if len(word) > 3 and word[-1] in "sx" else word


def _words(text: str) -> list[str]:
    return _WORD.findall(fold(text))


def significant(text: str) -> list[str]:
    """The stems of the words that say something, in order, once each."""
    found = [stem(w) for w in _words(text) if len(w) >= 3 and w not in STOPWORDS]
    return list(dict.fromkeys(w for w in found if w not in STOPWORDS))


def compact(text: str) -> str:
    """The words glued together (« l'UBU » → « lubu »): how a dictation that lost its apostrophe still matches."""
    return stem("".join(_words(text)))


def _proper_names(text: str) -> set[str]:
    """Stems of the words written with a capital inside a sentence: names, which weigh more."""
    names: set[str] = set()
    for match in _WORD.finditer(text):
        word = match.group(0)
        if not (word[0].isupper() and len(word) >= 3):
            continue
        if _SENTENCE_END.search(text[: match.start()]):
            continue
        names.update(significant(word))
    return names


def resolve_days(text: str, today: date) -> set[str]:
    """The days a message is about, as ISO dates: « demain », « lundi », « 2 novembre », « 2026-11-02 »."""
    days: set[str] = {m.group(0) for m in _ISO_DAY.finditer(text)}
    words = _words(text)
    for index, word in enumerate(words):
        if word == "demain":
            days.add((today + timedelta(days=2 if words[index - 1 : index] == ["apres"] else 1)).isoformat())
        elif word == "aujourd" or (word == "ce" and words[index + 1 : index + 2] in (["soir"], ["matin"])):
            days.add(today.isoformat())
        elif word == "hier":
            days.add((today - timedelta(days=1)).isoformat())
        elif word in WEEKDAYS:
            days.add((today + timedelta(days=(WEEKDAYS[word] - today.weekday()) % 7)).isoformat())
    for match in _DAY_MONTH.finditer(fold(text)):
        month = MONTHS.get(match.group(2))
        day = int(match.group(1))
        if month is None or not 1 <= day <= 31:
            continue
        year = int(match.group(3)) if match.group(3) else today.year
        try:
            found = date(year, month, day)
        except ValueError:
            continue
        if not match.group(3) and (today - found).days > 180:
            try:
                found = date(year + 1, month, day)
            except ValueError:
                continue
        days.add(found.isoformat())
    return days


@dataclass(frozen=True)
class Recalled:
    """A carte that came back, and why."""

    path: str
    kind: str
    card_id: str
    title: str
    resume: str
    moments: list[str]
    personnes: list[str]
    score: float
    because: list[str] = field(default_factory=list)


@dataclass
class Query:
    stems: set[str]
    compacts: set[str]
    names: set[str]
    days: set[str]

    @classmethod
    def of(cls, text: str, today: date) -> "Query":
        raw = [w for w in _words(text) if len(w) >= 3]
        compacts = {stem(w) for w in raw}
        every = _words(text)
        compacts.update(stem(a + b) for a, b in pairwise(every) if len(a + b) >= 3)
        return cls(set(significant(text)), compacts, _proper_names(text), resolve_days(text, today))

    def empty(self) -> bool:
        return not (self.stems or self.compacts or self.days)


def _near(term: str, candidates: set[str]) -> bool:
    """A name close to a word of the message: what a dictation or a typo leaves of it."""
    if len(term) < FUZZY_MIN_LENGTH:
        return False
    for word in candidates:
        if abs(len(word) - len(term)) > 2 or word[0] != term[0]:
            continue
        if SequenceMatcher(None, term, word).ratio() >= FUZZY_RATIO:
            return True
    return False


def _named(label: str, query: Query) -> bool:
    """Is this alias or name in the message — whole, glued, or nearly."""
    wanted = significant(label)
    if wanted and all(w in query.stems for w in wanted):
        return True
    glued = compact(label)
    if len(glued) >= 3 and glued in query.compacts:
        return True
    return _near(glued, query.compacts)


def score(card: CardView, query: Query) -> tuple[float, list[str]]:
    """How much this carte is for what was said, and the reasons. The carte alone, never its text."""
    total = 0.0
    because: list[str] = []

    for term in card.evoque:
        wanted = significant(term)
        if (wanted and all(w in query.stems for w in wanted)) or (
            len(wanted) == 1 and _near(wanted[0], query.compacts)
        ):
            total += W_EVOQUE
            because.append(f"évoque « {term} »")
            break

    for label in card.alias:
        if _named(label, query):
            total += W_ALIAS
            because.append(f"alias « {label} »")
            break

    people = 0
    for person in card.personnes:
        if any(w in query.stems for w in significant(person)) or _named(person, query):
            people += 1
            because.append(f"concerne {person}")
            if people >= MAX_PEOPLE:
                break
    total += W_PERSON * people

    if query.days and any(day in moment for moment in card.moments for day in query.days):
        total += W_DAY
        because.append("un moment tombe ce jour-là")

    seen: set[str] = set()
    for word in significant(card.title):
        if word in query.stems:
            seen.add(word)
            total += W_NAME if word in query.names else W_TITLE
    for word in significant(card.resume):
        if word in query.stems and word not in seen:
            seen.add(word)
            total += W_NAME if word in query.names else W_RESUME
    if seen:
        because.append("mots : " + ", ".join(sorted(seen)))

    if card.kind == "conversation":
        total *= CONVERSATION_FACTOR
    return total, because


def rank(
    cards: list[CardView],
    text: str,
    today: date,
    *,
    exclude: frozenset[str] = frozenset(),
    limit: int | None = None,
    threshold: float | None = None,
) -> list[Recalled]:
    """The cartes that pass the threshold for this text, best first, at most `limit`."""
    limit = settings.memory_recall_max if limit is None else limit
    threshold = settings.memory_recall_threshold if threshold is None else threshold
    query = Query.of(text, today)
    if query.empty():
        return []
    found: list[Recalled] = []
    for card in cards:
        if card.path in exclude or card.archived:
            continue
        points, because = score(card, query)
        if points >= threshold:
            found.append(
                Recalled(
                    card.path,
                    card.kind,
                    card.card_id,
                    card.title,
                    card.resume,
                    card.moments,
                    card.personnes,
                    round(points, 2),
                    because,
                )
            )
    found.sort(key=lambda r: (-r.score, r.kind != "fiche", r.path))
    return found[:limit]


async def recall(
    user_id: str,
    text: str,
    today: date,
    *,
    exclude: frozenset[str] = frozenset(),
    limit: int | None = None,
    threshold: float | None = None,
) -> list[Recalled]:
    """Level 2 for `text`: the cartes of this user's index that are for it. Not bounded — see `recall_for_turn`."""
    paths.check_user_id(user_id)
    if not text.strip():
        return []
    cards = await memory_index_repo.cards(user_id)
    return await asyncio.to_thread(rank, cards, text, today, exclude=exclude, limit=limit, threshold=threshold)


async def _recall_outside_thread(user_id: str, text: str, today: date, context_id: str | None) -> list[Recalled]:
    """The recall, leaving out the current thread's own file: it would come back for everything said in it."""
    exclude: set[str] = set()
    if context_id:
        # Reads the folder when the thread's file is not known yet: off the event loop, inside the delay.
        own = await asyncio.to_thread(conversations.find_file, memory_service, user_id, context_id)
        if own:
            exclude.add(own)
    return await recall(user_id, text, today, exclude=frozenset(exclude))


async def recall_for_turn(
    user_id: str,
    text: str,
    today: date,
    *,
    source: str,
    context_id: str | None = None,
) -> list[Recalled]:
    """The recall of a turn, bounded: past the delay, or on any failure, nothing — the turn goes on without it."""
    started = time.monotonic()
    outcome = "empty"
    found: list[Recalled] = []
    try:
        if not memory_service.enabled or not text.strip():
            return []
        found = await asyncio.wait_for(
            _recall_outside_thread(user_id, text, today, context_id), settings.memory_recall_timeout_seconds
        )
        outcome = "recalled" if found else "empty"
    except TimeoutError:
        outcome = "timeout"
        logger.warning(
            "Recall for %s went past %.2fs: the turn goes on without it", source, settings.memory_recall_timeout_seconds
        )
    except Exception as e:
        outcome = "error"
        logger.warning("Recall for %s failed, the turn goes on without it: %s", source, e)
    finally:
        MEMORY_RECALL_SECONDS.labels(source=source).observe(time.monotonic() - started)
        MEMORY_RECALL_OUTCOMES.labels(source=source, outcome=outcome).inc()
        MEMORY_RECALLED_CARTES.observe(len(found))
    return found if outcome == "recalled" else []
