"""The four files Maggie generates and keeps in every folder, in French, each saying so on its first line.

The folder has to be understandable alone, handed as a zip to another agent: `LISEZMOI.md` and
`AGENTS.md` are all it needs to know how to read and write it. The first line of each generated file
carries `GENERATED_MARK`, which is how a reader (human or tool) recognises them.
"""

import hashlib
import re
from datetime import datetime

from app.memory import paths

GENERATED_MARK = "Fichier généré et tenu par Maggie"
_NOTE = "Tu peux le corriger à la main : ta version est conservée et Maggie ne la remplace pas."


_SEAL = re.compile(r"^<!-- sceau: ([0-9a-f]{16}) -->\n", re.MULTILINE)


def is_generated_text(text: str) -> bool:
    return GENERATED_MARK in text.split("\n", 1)[0]


def _digest(first_line: str, rest: str) -> str:
    return hashlib.sha256(f"{first_line}\n{rest}".encode()).hexdigest()[:16]


def seal(text: str) -> str:
    """The text with a checksum on its second line, so a later pass can tell whether anyone edited it.

    A generated file is regenerated only while it still matches its seal: once the owner (or any
    agent) has changed it, his version stays. The seal lives in the file, so it survives a new pod.
    """
    first_line, _, rest = text.partition("\n")
    return f"{first_line}\n<!-- sceau: {_digest(first_line, rest)} -->\n{rest}"


def is_untouched(text: str) -> bool:
    """True when `text` is a sealed generated file nobody has edited since."""
    first_line, _, after = text.partition("\n")
    match = _SEAL.match(after)
    if match is None or not is_generated_text(first_line):
        return False
    return match.group(1) == _digest(first_line, after[match.end() :])


def _first_line(what: str) -> str:
    return f"> {GENERATED_MARK} — {what}. {_NOTE}"


def readme() -> str:
    return seal(f"""{_first_line("comment lire ce dossier")}

# Mémoire de Maggie

Ce dossier est la mémoire de Maggie : des fichiers Markdown, rien d'autre. Ils sont lisibles et modifiables
avec n'importe quel éditeur. **Tes corrections font foi** : Maggie relit ce que tu as changé et s'y fie.

## Ce qu'il y a dedans

- `{paths.SUMMARY}` — une ligne par fiche : le plus rapide pour s'y retrouver.
- `{paths.JOURNAL}` — ce que Maggie a changé, quand et pourquoi.
- `{paths.CONVERSATIONS_DIR}/AAAA/MM/` — les échanges, entiers, un fichier chacun : `AAAA-MM-JJ HHhMM — sujet.md`.
  Ils ne sont jamais réécrits : ce sont les souvenirs bruts.
- Les autres dossiers contiennent les **fiches** : ce que Maggie sait de toi, un fait ou une histoire par fichier,
  classées par sujet. Le nom d'une fiche est une phrase vraie : `Françoise n'aime pas les roses.md`.
- `{paths.CONFLICTS_DIR}/` — les versions de Maggie mises de côté quand tu avais modifié le même fichier
  avant elle. Rien n'y est perdu ; garde ce qui te convient.

## Lire une fiche

Chaque fiche commence par une **carte** (en-tête YAML entre deux lignes `---`) : `resume`, `personnes`,
`evoque` (les situations où elle doit revenir à l'esprit), `sources` (d'où vient chaque affirmation, et si c'est
`dit`, `observé` ou `déduit`). Puis le texte.

## Corriger

Modifie, renomme ou déplace une fiche comme tu veux, en gardant la ligne `id:` de sa carte : c'est elle qui
identifie la fiche, pas son nom. Supprimer un fichier l'efface de la mémoire de Maggie ; le stockage garde
les versions précédentes.

Pour un agent : lis `{paths.AGENTS}`.
""")


def agents() -> str:
    return seal(f"""{_first_line("règles pour un agent qui lit ou écrit ce dossier")}

# AGENTS.md

Ce dossier est la mémoire d'une personne, en Markdown. Si tu es un agent, voici comment t'en servir.

## Lire

1. Ouvre `{paths.SUMMARY}` : une ligne par fiche.
2. Juge la pertinence sur la **carte** (l'en-tête YAML) : `resume`, `personnes`, `evoque`. N'ouvre le texte que
   pour un détail.
3. Ne réponds jamais sur ce que tu sais sans avoir ouvert la fiche. Pas de fiche, pas d'affirmation.
4. Une source `déduit` s'annonce comme telle ; `dit` = la personne l'a dit, `observé` = lu dans l'application.

## Écrire

- Une fiche = un fait ou une histoire. Nom = une phrase vraie, avec ses accents. Dossiers peu profonds, par sujet.
- Chaque fiche a une carte avec un `id` (identité stable à travers les renommages) et ses `sources`.
- Ce qui n'est plus vrai est **daté et marqué remplacé**, jamais effacé en silence.
- Ne touche pas aux fichiers de `{paths.CONVERSATIONS_DIR}/` : ils sont conservés tels quels.
- Les fichiers `{paths.README}`, `{paths.AGENTS}`, `{paths.SUMMARY}` et `{paths.JOURNAL}` sont générés.
- Ne mets dans ce dossier que ce qui est vrai de la personne : ni état de conversation, ni compteurs d'usage.

## Priorité

La version de la personne l'emporte toujours sur la tienne. Si elle a modifié un fichier avant toi, garde
sa version et mets la tienne de côté dans `{paths.CONFLICTS_DIR}/`.
""")


def journal_header() -> str:
    return f"""{_first_line("ce que Maggie a changé dans ce dossier, quand, pourquoi")}

# Journal

"""


def journal_line(when: datetime, text: str) -> str:
    one_line = " ".join(text.split())
    return f"- {when:%Y-%m-%d %Hh%M} — {one_line}\n"


def summary(entries: list[tuple[str, str]]) -> str:
    """`entries` = (path, resume) of every fiche: one line each."""
    lines = [
        _first_line("table des matières, une ligne par fiche"),
        "",
        "# Sommaire",
        "",
    ]
    if not entries:
        lines.append("_Aucune fiche pour l'instant._")
    for path, resume in entries:
        label = path.removesuffix(paths.EXTENSION)
        short = " ".join(resume.split())
        lines.append(f"- {label}" + (f" — {short}" if short else ""))
    return seal("\n".join(lines) + "\n")
