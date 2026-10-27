"""The night's upkeep (MAG-159): cartes caught up, fiches drawn and corrected, regularities proved, files tidied,
circumstance set aside, orphans told — bounded, resumable, and written down in `journal.md`."""

import json
import uuid
from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch
from zoneinfo import ZoneInfo

import pytest

from app.config import settings
from app.db.context_model import ConversationContext
from app.db.context_repository import context_repo
from app.db.memory_index_repository import memory_index_repo
from app.db.memory_night_repository import memory_night_repo
from app.db.models import Message
from app.memory import agent_memory, conversations, fiches, recall
from app.memory import carte as carte_mod
from app.memory.encoder import PassageEncoder
from app.memory.night import NightKeeper, messages_of, summary_line
from tests.memory.conftest import USER_A, USER_B

PARIS = ZoneInfo("Europe/Paris")
NOW = datetime(2026, 10, 14, 10, 30, tzinfo=UTC)
TODAY = "2026-10-14"

RENNES = "Le propriétaire habite à Rennes"
LYON = "Le propriétaire habite à Lyon"
FICHE_RENNES = f"Lieu de vie/{RENNES}.md"

STEPS = {
    "aucune fiche ne le disait": "miss_fiche",
    "aurait dû revenir à l'esprit": "miss",
    "relis UNE conversation": "consolidate",
    "RÉGULARITÉS": "regularities",
    "ranges les fiches": "tidy",
    "n'a plus à être évoqué": "forget",
    "n'a pas de carte": "carte",
    "Tu écris la mémoire de Maggie": "encoder",
}
EMPTY = {
    "miss": {"evoque": [], "voisines": []},
    "miss_fiche": {"fiches": []},
    "consolidate": {"fiches": []},
    "regularities": {"regularites": []},
    "tidy": {"operations": []},
    "forget": {"decisions": []},
    "carte": {"resume": "Une conversation du jour.", "moments": [], "personnes": [], "evoque": []},
    "encoder": {"carte": {"resume": "Une conversation du jour.", "moments": []}, "fiches": [], "doute": None},
}


class NightModel:
    """The model of the night: answers by step (a list is consumed in order, a function sees the request)."""

    def __init__(self, **answers):
        self.answers = {name: (list(a) if isinstance(a, list) else a) for name, a in answers.items()}
        self.requests: list[dict] = []
        self.messages = SimpleNamespace(create=self._create)

    def step(self, system: str) -> str:
        return next(name for marker, name in STEPS.items() if marker in system)

    async def _create(self, **kwargs):
        self.requests.append(kwargs)
        step = self.step(kwargs["system"])
        answer = self.answers.get(step, EMPTY[step])
        if isinstance(answer, list):
            answer = answer.pop(0) if len(answer) > 1 else answer[0]
        if callable(answer):
            answer = answer(kwargs["messages"][0]["content"])
        if isinstance(answer, Exception):
            raise answer
        text = answer if isinstance(answer, str) else json.dumps(answer)
        return SimpleNamespace(
            content=[SimpleNamespace(type="text", text=text)],
            usage=SimpleNamespace(input_tokens=100, output_tokens=50),
        )

    def asked(self, step: str) -> list[str]:
        return [r["messages"][0]["content"] for r in self.requests if self.step(r["system"]) == step]

    @property
    def calls(self) -> int:
        return len(self.requests)


@pytest.fixture(autouse=True)
def wired(chat_db, env):
    conversations._files.clear()
    conversations._locks.clear()
    paris = AsyncMock(return_value=PARIS)
    with (
        patch("app.user_timezone.resolve_user_timezone", paris),
        patch("app.memory.night.resolve_user_timezone", paris),
        patch("app.memory.encoder.resolve_user_timezone", paris),
        patch("app.memory.night.record_llm_usage"),
        patch("app.memory.encoder.record_llm_usage"),
        patch.object(conversations, "memory_service", env.service),
    ):
        yield
    conversations._files.clear()
    conversations._locks.clear()


def keeper(env, model: NightModel, encoder_model=None) -> NightKeeper:
    encoder = PassageEncoder(service=env.service)
    encoder.client = encoder_model or NightModel()
    night = NightKeeper(service=env.service, encoder=encoder)
    night.client = model
    return night


async def conversation(chat_db, env, said: str, when: datetime, *, label: str = "Fil", encoded: bool = True) -> str:
    """A thread with its file on disk and in the index; `encoded` keeps the encoder out of it (the night's own work is tested)."""
    context_id = uuid.uuid4().hex
    session = chat_db.session()._session
    session.add(
        ConversationContext(id=context_id, user_id=USER_A, label=label, updated_at=when + timedelta(seconds=20))
    )
    for index, (role, content, at) in enumerate(
        [("user", said, when), ("assistant", "D'accord, c'est noté.", when + timedelta(seconds=20))]
    ):
        session.add(
            Message(
                id=f"{context_id[:20]}{index:06d}",
                user_id=USER_A,
                role=role,
                content=content,
                context_id=context_id,
                created_at=at,
            )
        )
    session.commit()
    await conversations.record_thread(USER_A, context_id, service=env.service)
    if encoded:
        await context_repo.set_encoded(context_id, when + timedelta(seconds=20), None)
    return conversations.find_file(env.service, USER_A, context_id)


def minute(when: datetime) -> str:
    return when.astimezone(PARIS).strftime("%Y-%m-%d %Hh%M")


def proposal(*, chemin=None, titre=RENNES, claims=None, remplace=None, doute=None, dossiers=("Lieu de vie",), **extra):
    return {
        "fiches": [
            {
                "chemin": chemin,
                "titre": titre,
                "dossiers": list(dossiers),
                "resume": "Où habite le propriétaire",
                "personnes": [],
                "evoque": [],
                "alias": [],
                "ajouts": claims or [],
                "remplace": remplace or [],
                "doute": doute,
                **extra,
            }
        ],
        "carte": {"personnes": [], "evoque": []},
    }


async def run(night: NightKeeper, now: datetime = NOW, *, force: bool = True):
    return await night.run(USER_A, now, force=force)


def journal(env) -> str:
    return env.local("journal.md") or ""


def fiche_files(env) -> list[str]:
    return [
        f
        for f in env.service.folder(USER_A).files()
        if not f.startswith("Conversations/")
        and f.endswith(".md")
        and f not in ("journal.md", "sommaire.md", "LISEZMOI.md", "AGENTS.md")
    ]


async def seed(
    env, titre: str, texte: str, source: str, heure: str, *, dossiers=("Lieu de vie",), resume="Résumé", **kw
):
    return await fiches.write_fiche(
        env.service,
        USER_A,
        titre=titre,
        dossiers=list(dossiers),
        resume=resume,
        affirmations=[{"texte": texte, "provenance": "dit", "source": source, "heure": heure}],
        today=NOW.date(),
        **kw,
    )


class TestTheMessagesOfAConversation:
    def test_the_messages_are_read_back_with_their_minute_and_without_their_anchors(self):
        text = (
            "---\ncontexte: abc\n---\n\n# Sujet\n\n> Note\n\n"
            "## 2026-10-14 12h00 — Propriétaire\n<!-- msg: m1 -->\n\nBonjour\nle monde\n\n"
            "## 2026-10-14 12h01 — Maggie\n<!-- msg: m2 -->\n\nSalut\n\n"
        )

        messages = messages_of(text)

        assert [(m.heure, m.role, m.content) for m in messages] == [
            ("2026-10-14 12h00", "Propriétaire", "Bonjour\nle monde"),
            ("2026-10-14 12h01", "Maggie", "Salut"),
        ]


class TestTheCartes:
    async def test_a_conversation_the_encoder_left_without_a_carte_gets_one_and_its_messages_stay(self, env, chat_db):
        path = await conversation(chat_db, env, "On ira au concert à l'UBU.", NOW - timedelta(hours=2))
        before_body = carte_mod.split(env.local(path))[1]
        model = NightModel(
            carte={"resume": "Un concert à l'UBU.", "moments": [], "personnes": ["Léa"], "evoque": ["sorties"]}
        )

        run_ = await run(keeper(env, model))

        text = env.local(path)
        assert carte_mod.parse(text).carte.resume == "Un concert à l'UBU."
        assert carte_mod.parse(text).carte.personnes == ["Léa"]
        assert carte_mod.split(text)[1] == before_body, "the conversation itself is never touched"
        assert run_.report["cartes"] == [path.rsplit("/", 1)[-1].removesuffix(".md")]
        assert "carte rattrapée" in journal(env)

    async def test_a_thread_still_to_encode_is_encoded_first_and_only_those_missing_are_caught_up(self, env, chat_db):
        thread = await conversation(
            chat_db, env, "Ma nièce Zoé est végétarienne.", NOW - timedelta(hours=2), encoded=False
        )
        encoder_model = NightModel(
            encoder={"carte": {"resume": "Zoé est végétarienne.", "moments": []}, "fiches": [], "doute": None}
        )
        model = NightModel()

        await run(keeper(env, model, encoder_model))

        assert carte_mod.parse(env.local(thread)).carte.resume == "Zoé est végétarienne."
        assert model.asked("carte") == [], "the encoder wrote it: the night does not ask again"
        assert encoder_model.calls == 1

    async def test_a_conversation_the_owner_cartes_by_hand_is_left_alone(self, env, chat_db):
        path = await conversation(chat_db, env, "Un mot.", NOW - timedelta(hours=2))
        await conversations.update_carte_of_file(
            USER_A,
            path,
            lambda c: carte_mod.Carte(id=c.id, resume="À moi.", extra=c.extra),
            reason="le propriétaire",
            service=env.service,
        )
        model = NightModel()

        await run(keeper(env, model))

        assert model.asked("carte") == []
        assert carte_mod.parse(env.local(path)).carte.resume == "À moi."


class TestTheFiches:
    async def test_a_fiche_is_drawn_from_a_conversation_and_cites_it_with_the_minute(self, env, chat_db):
        when = NOW - timedelta(hours=3)
        path = await conversation(chat_db, env, "J'ai emménagé à Rennes.", when)
        model = NightModel(consolidate=proposal(claims=[{"texte": RENNES, "heure": minute(when)}]))

        run_ = await run(keeper(env, model))

        assert fiche_files(env) == [FICHE_RENNES]
        assert f"- {RENNES} — dit · {path} · {minute(when)}" in env.local(FICHE_RENNES)
        assert run_.report["ecrites"] == [FICHE_RENNES]
        assert f"entretien nocturne du {TODAY}" in journal(env)

    async def test_the_most_recent_word_wins_and_the_old_wording_stays_dated_and_replaced(self, env, chat_db):
        first, second = NOW - timedelta(days=1, hours=3), NOW - timedelta(hours=3)
        before = await conversation(chat_db, env, "J'ai emménagé à Rennes.", first)
        after = await conversation(chat_db, env, "Finalement, je m'installe à Lyon.", second)
        model = NightModel(
            consolidate=[
                proposal(claims=[{"texte": RENNES, "heure": minute(first)}]),
                proposal(
                    chemin=FICHE_RENNES,
                    titre=LYON,
                    claims=[{"texte": LYON, "heure": minute(second)}],
                    remplace=[{"texte": RENNES, "motif": "il s'installe finalement à Lyon"}],
                ),
            ]
        )

        run_ = await run(keeper(env, model))

        text = env.local(FICHE_RENNES)
        known, replaced = text.split("## Ce qui n'est plus vrai")
        assert f"- {LYON} — dit · {after} · {minute(second)}" in known
        assert RENNES not in known
        assert (
            f"- ~~{RENNES}~~ — dit · {before} · {minute(first)} — remplacé le {TODAY} : il s'installe finalement à Lyon"
            in replaced
        )
        assert run_.report["corrigees"] == [FICHE_RENNES]
        assert "1 fiche(s) corrigée(s)" in journal(env)
        assert f"remplacée(s)" in journal(env)

    async def test_the_conversations_are_read_oldest_first(self, env, chat_db):
        late = await conversation(chat_db, env, "Finalement, je m'installe à Lyon.", NOW - timedelta(hours=1))
        early = await conversation(chat_db, env, "J'ai emménagé à Rennes.", NOW - timedelta(days=2))
        model = NightModel()

        await run(keeper(env, model))

        asked = model.asked("consolidate")
        assert early in asked[0] and late in asked[1]

    async def test_an_older_word_does_not_replace_a_more_recent_one(self, env, chat_db):
        old_when, new_when = NOW - timedelta(days=2), NOW - timedelta(hours=2)
        old = await conversation(chat_db, env, "J'ai emménagé à Rennes.", old_when)
        recent = await conversation(chat_db, env, "Finalement, je m'installe à Lyon.", new_when)
        await seed(env, LYON, LYON, recent, minute(new_when), titre_unused=None) if False else None
        await fiches.write_fiche(
            env.service,
            USER_A,
            titre=LYON,
            dossiers=["Lieu de vie"],
            resume="Où",
            affirmations=[{"texte": LYON, "provenance": "dit", "source": recent, "heure": minute(new_when)}],
            today=NOW.date(),
        )
        indexed = {f.path: f for f in await memory_index_repo.list_files(USER_A, kind="conversation")}
        await memory_night_repo.mark_seen(
            USER_A, indexed[recent].card_id, "consolidated", carte_mod.content_hash(indexed[recent].body)
        )
        path = f"Lieu de vie/{LYON}.md"
        model = NightModel(
            consolidate=proposal(
                chemin=path,
                titre=RENNES,
                claims=[{"texte": RENNES, "heure": minute(old_when)}],
                remplace=[{"texte": LYON, "motif": "Rennes"}],
            )
        )
        before = env.local(path)

        run_ = await run(keeper(env, model))

        assert env.local(path) == before
        assert "plus récente" in json.dumps(run_.report["laissees"], ensure_ascii=False)
        assert old in json.dumps(run_.report["laissees"], ensure_ascii=False)

    async def test_a_claim_on_a_message_the_owner_did_not_write_is_not_written(self, env, chat_db):
        when = NOW - timedelta(hours=3)
        await conversation(chat_db, env, "J'ai emménagé à Rennes.", when)
        answered = minute(when + timedelta(minutes=5))
        model = NightModel(consolidate=proposal(claims=[{"texte": RENNES, "heure": answered}]))

        await run(keeper(env, model))

        assert fiche_files(env) == []

    async def test_a_doubt_is_a_question_for_the_owner_not_a_decision(self, env, chat_db):
        first, second = NOW - timedelta(days=1, hours=3), NOW - timedelta(hours=3)
        await conversation(chat_db, env, "J'ai emménagé à Rennes.", first)
        await conversation(chat_db, env, "Finalement, je m'installe à Lyon.", second)
        question = "Lyon remplace-t-il Rennes, ou est-ce un second logement ?"
        model = NightModel(
            consolidate=[
                proposal(claims=[{"texte": RENNES, "heure": minute(first)}]),
                proposal(chemin=FICHE_RENNES, claims=[{"texte": LYON, "heure": minute(second)}], doute=question),
            ]
        )

        run_ = await run(keeper(env, model))

        text = env.local(FICHE_RENNES)
        assert LYON not in text, "nothing is decided silently"
        assert carte_mod.parse(text).carte.extra["question"] == question
        assert run_.report["questions"] == [{"chemin": FICHE_RENNES, "question": question}]
        assert f"question sur `{FICHE_RENNES}` : {question}" in journal(env)
        cards = await env.service.cards(USER_A)
        assert [c.question for c in cards if c.path == FICHE_RENNES] == [question]
        profile = agent_memory.render_profile(cards, {}, 50)
        assert f"❓ À demander au propriétaire, à la prochaine occasion naturelle : {question}" in profile

    async def test_the_question_stays_until_the_fiche_is_written_again_by_a_tool(self, env, chat_db):
        when = NOW - timedelta(hours=3)
        path = await conversation(chat_db, env, "J'ai emménagé à Rennes.", when)
        await seed(env, RENNES, RENNES, path, minute(when))
        await run(keeper(env, NightModel(consolidate=proposal(chemin=FICHE_RENNES, doute="Vraiment ?"))))
        assert carte_mod.parse(env.local(FICHE_RENNES)).carte.extra["question"] == "Vraiment ?"

        await fiches.write_fiche(
            env.service,
            USER_A,
            titre=RENNES,
            chemin=FICHE_RENNES,
            today=NOW.date(),
            settle_question=True,
            affirmations=[{"texte": RENNES, "provenance": "dit", "source": path, "heure": minute(when)}],
        )

        assert "question" not in carte_mod.parse(env.local(FICHE_RENNES)).carte.extra

    async def test_a_doubt_about_no_fiche_is_reported_and_written_nowhere(self, env, chat_db):
        when = NOW - timedelta(hours=3)
        await conversation(chat_db, env, "On verra pour l'été.", when)
        model = NightModel(consolidate=proposal(titre="L'été", doute="De quel été parle-t-il ?"))

        run_ = await run(keeper(env, model))

        assert fiche_files(env) == []
        assert run_.report["questions"][0]["question"] == "De quel été parle-t-il ?"
        assert "De quel été parle-t-il ?" in journal(env)

    async def test_a_fiche_that_is_not_listed_is_left_alone_and_said(self, env, chat_db):
        when = NOW - timedelta(hours=3)
        await conversation(chat_db, env, "J'ai emménagé à Rennes.", when)
        model = NightModel(
            consolidate=proposal(chemin="Ailleurs/Fantôme.md", claims=[{"texte": RENNES, "heure": minute(when)}])
        )

        run_ = await run(keeper(env, model))

        assert fiche_files(env) == []
        assert "Fantôme" in json.dumps(run_.report["laissees"], ensure_ascii=False)

    async def test_the_carte_of_the_conversation_is_enriched_with_hindsight(self, env, chat_db):
        when = NOW - timedelta(hours=3)
        path = await conversation(chat_db, env, "J'ai emménagé à Rennes.", when)
        await conversations.update_carte_of_file(
            USER_A,
            path,
            lambda c: carte_mod.Carte(id=c.id, resume="Rennes.", evoque=["adresse"], extra=c.extra),
            reason="test",
            service=env.service,
        )
        answer = proposal()
        answer["carte"] = {"personnes": ["Léa"], "evoque": ["changement d'adresse", "Adresse"]}

        await run(keeper(env, NightModel(consolidate=answer)))

        carte = carte_mod.parse(env.local(path)).carte
        assert carte.evoque == ["adresse", "changement d'adresse"]
        assert carte.personnes == ["Léa"]
        assert carte.resume == "Rennes."


class TestRegularities:
    async def two_days(self, chat_db, env):
        first, second = NOW - timedelta(days=3), NOW - timedelta(hours=3)
        a = await conversation(chat_db, env, "Le concert commençait à 19 heures.", first)
        b = await conversation(chat_db, env, "Encore un concert, à 19 heures.", second)
        return (a, minute(first)), (b, minute(second))

    def regularity(self, proofs):
        return {
            "regularites": [
                {
                    "chemin": None,
                    "titre": "Les concerts commencent en général à 19 heures",
                    "dossiers": ["Sorties"],
                    "resume": "L'heure des concerts",
                    "evoque": ["concert"],
                    "preuves": [{"conversation": c, "heure": h, "texte": t} for c, h, t in proofs],
                }
            ]
        }

    async def test_a_regularity_is_written_with_the_conversations_that_prove_it(self, env, chat_db):
        (a, ha), (b, hb) = await self.two_days(chat_db, env)
        model = NightModel(
            regularities=self.regularity(
                [(a, ha, "Le concert commençait à 19 heures"), (b, hb, "Encore un concert, à 19 heures")]
            )
        )

        run_ = await run(keeper(env, model))

        text = env.local("Sorties/Les concerts commencent en général à 19 heures.md")
        assert f"- Le concert commençait à 19 heures — dit · {a} · {ha}" in text
        assert f"- Encore un concert, à 19 heures — dit · {b} · {hb}" in text
        assert f"- Les concerts commencent en général à 19 heures — déduit · {b} · {hb}" in text
        assert run_.report["regularites"] == [
            {"chemin": "Sorties/Les concerts commencent en général à 19 heures.md", "preuves": 2}
        ]

    async def test_without_enough_proofs_nothing_is_written(self, env, chat_db):
        (a, ha), (b, hb) = await self.two_days(chat_db, env)
        model = NightModel(regularities=self.regularity([(a, ha, "Le concert commençait à 19 heures")]))

        run_ = await run(keeper(env, model))

        assert fiche_files(env) == []
        assert "preuves" in json.dumps(run_.report["laissees"], ensure_ascii=False)

    async def test_a_proof_on_a_message_that_does_not_exist_does_not_count(self, env, chat_db):
        (a, ha), (b, hb) = await self.two_days(chat_db, env)
        model = NightModel(
            regularities=self.regularity(
                [(a, ha, "Le concert commençait à 19 heures"), (b, "2026-10-14 03h33", "Inventé")]
            )
        )

        await run(keeper(env, model))

        assert fiche_files(env) == []

    async def test_two_proofs_in_the_same_conversation_are_one_conversation(self, env, chat_db):
        (a, ha), _ = await self.two_days(chat_db, env)
        model = NightModel(
            regularities=self.regularity([(a, ha, "Le concert commençait à 19 heures"), (a, ha, "Encore")])
        )

        await run(keeper(env, model))

        assert fiche_files(env) == []

    async def test_a_pass_that_read_no_conversation_looks_for_no_regularity(self, env, chat_db):
        await self.two_days(chat_db, env)
        night = keeper(env, NightModel())
        await run(night)
        model = NightModel()
        again = keeper(env, model)

        await run(again, NOW + timedelta(days=1))

        assert model.asked("regularities") == []


class TestTheTidying:
    async def fiches_of_two_nights(self, chat_db, env):
        when = NOW - timedelta(hours=3)
        path = await conversation(chat_db, env, "J'ai emménagé à Rennes.", when)
        await seed(env, RENNES, RENNES, path, minute(when))
        return path, minute(when)

    async def test_a_fiche_is_renamed_keeping_its_id_and_the_old_title_as_an_alias(self, env, chat_db):
        await self.fiches_of_two_nights(chat_db, env)
        before = await env.service.indexed(USER_A, FICHE_RENNES)
        model = NightModel(
            tidy={
                "operations": [
                    {"op": "renommer", "chemin": FICHE_RENNES, "titre": LYON, "motif": "le nom disait Rennes"}
                ]
            }
        )

        run_ = await run(keeper(env, model))

        new = f"Lieu de vie/{LYON}.md"
        assert fiche_files(env) == [new]
        after = await env.service.indexed(USER_A, new)
        assert after.card_id == before.card_id
        assert carte_mod.parse(env.local(new)).carte.alias == [RENNES]
        assert run_.report["renommees"] == [{"de": FICHE_RENNES, "a": new}]
        assert f"déplacé `{FICHE_RENNES}` → `{new}`" in journal(env)

    async def test_a_fiche_is_moved_to_other_folders(self, env, chat_db):
        await self.fiches_of_two_nights(chat_db, env)
        model = NightModel(
            tidy={"operations": [{"op": "deplacer", "chemin": FICHE_RENNES, "dossiers": ["Vie", "Logement"]}]}
        )

        run_ = await run(keeper(env, model))

        assert fiche_files(env) == [f"Vie/Logement/{RENNES}.md"]
        assert run_.report["deplacees"]

    async def test_duplicates_are_merged_and_nothing_true_disappears(self, env, chat_db):
        when = NOW - timedelta(hours=3)
        path = await conversation(chat_db, env, "J'ai emménagé à Rennes. Je prends le vélo.", when)
        await seed(env, RENNES, RENNES, path, minute(when), resume="Adresse")
        await seed(
            env, "Le propriétaire vit à Rennes", "Le propriétaire vit à Rennes", path, minute(when), resume="Ville"
        )
        await seed(
            env, "Il roule à vélo", "Il roule à vélo", path, minute(when), dossiers=("Habitudes",), resume="Vélo"
        )
        a = await env.service.indexed(USER_A, FICHE_RENNES)
        b = await env.service.indexed(USER_A, "Lieu de vie/Le propriétaire vit à Rennes.md")
        # A conversation points at the absorbed fiche: it must follow to the survivor.
        await conversations.update_carte_of_file(
            USER_A,
            path,
            lambda c: carte_mod.Carte(id=c.id, resume="Rennes", liens=[b.card_id], extra=c.extra),
            reason="test",
            service=env.service,
        )
        model = NightModel(
            tidy={
                "operations": [
                    {"op": "fusionner", "chemins": [FICHE_RENNES, "Lieu de vie/Le propriétaire vit à Rennes.md"]}
                ]
            }
        )

        run_ = await run(keeper(env, model))

        survivor = env.local(FICHE_RENNES)
        assert f"- {RENNES} — dit" in survivor and "- Le propriétaire vit à Rennes — dit" in survivor
        assert env.local("Lieu de vie/Le propriétaire vit à Rennes.md") is None
        assert "Habitudes/Il roule à vélo.md" in fiche_files(env)
        assert "Le propriétaire vit à Rennes" in carte_mod.parse(survivor).carte.alias
        liens = carte_mod.parse(env.local(path)).carte.liens
        assert a.card_id in liens and b.card_id not in liens
        assert run_.report["fusions"] == [
            {"dans": FICHE_RENNES, "absorbees": ["Lieu de vie/Le propriétaire vit à Rennes.md"]}
        ]
        assert "supprimé `Lieu de vie/Le propriétaire vit à Rennes.md`" in journal(env)

    async def test_a_catch_all_is_split_and_every_claim_lands_in_one_place_only(self, env, chat_db):
        when = NOW - timedelta(hours=3)
        path = await conversation(chat_db, env, "J'habite à Rennes. Je roule à vélo. J'aime le thé.", when)
        stamp = minute(when)
        await fiches.write_fiche(
            env.service,
            USER_A,
            titre="Vie de tous les jours",
            dossiers=["Vie"],
            resume="Divers",
            affirmations=[
                {"texte": t, "provenance": "dit", "source": path, "heure": stamp}
                for t in ("Il habite à Rennes", "Il roule à vélo", "Il aime le thé")
            ],
            today=NOW.date(),
        )
        model = NightModel(
            tidy={
                "operations": [
                    {
                        "op": "scinder",
                        "chemin": "Vie/Vie de tous les jours.md",
                        "parties": [
                            {
                                "titre": "Il roule à vélo",
                                "dossiers": ["Habitudes"],
                                "resume": "Vélo",
                                "affirmations": ["Il roule à vélo"],
                            },
                            {
                                "titre": "Il aime le thé",
                                "dossiers": ["Goûts"],
                                "resume": "Thé",
                                "affirmations": ["Il aime le thé"],
                            },
                        ],
                    }
                ]
            }
        )

        run_ = await run(keeper(env, model))

        assert sorted(fiche_files(env)) == [
            "Goûts/Il aime le thé.md",
            "Habitudes/Il roule à vélo.md",
            "Vie/Vie de tous les jours.md",
        ]
        assert f"- Il roule à vélo — dit · {path} · {stamp}" in env.local("Habitudes/Il roule à vélo.md")
        original = env.local("Vie/Vie de tous les jours.md")
        assert (
            "Il habite à Rennes" in original and "Il roule à vélo" not in original and "Il aime le thé" not in original
        )
        assert run_.report["scissions"][0]["de"] == "Vie/Vie de tous les jours.md"

    async def test_a_split_that_would_take_a_claim_twice_is_refused_and_nothing_changes(self, env, chat_db):
        await self.fiches_of_two_nights(chat_db, env)
        before = env.local(FICHE_RENNES)
        model = NightModel(
            tidy={
                "operations": [
                    {
                        "op": "scinder",
                        "chemin": FICHE_RENNES,
                        "parties": [{"titre": "A", "affirmations": [RENNES]}, {"titre": "B", "affirmations": [RENNES]}],
                    }
                ]
            }
        )

        run_ = await run(keeper(env, model))

        assert fiche_files(env) == [FICHE_RENNES]
        assert env.local(FICHE_RENNES) == before
        assert run_.report["laissees"]

    async def test_a_fiche_already_tidied_and_unchanged_is_not_asked_about_again(self, env, chat_db):
        await self.fiches_of_two_nights(chat_db, env)
        model = NightModel()
        night = keeper(env, model)
        await run(night)
        asked = len(model.asked("tidy"))

        await run(night, NOW + timedelta(days=1))

        assert asked == 1 and len(model.asked("tidy")) == 1


class TestTidyingMoreFichesThanOneAskCanShow:
    async def test_every_fiche_gets_its_turn_and_none_is_marked_done_unseen(self, env, chat_db):
        when = NOW - timedelta(hours=3)
        path = await conversation(chat_db, env, "Quinze choses sur moi.", when)
        for index in range(14):
            await seed(env, f"Fait numéro {index}", f"Fait numéro {index}", path, minute(when), dossiers=("Faits",))
        model = NightModel()
        night = keeper(env, model)

        await run(night)

        shown = "\n".join(model.asked("tidy"))
        assert len(model.asked("tidy")) == 3, "14 fiches, 6 shown per request"
        assert all(f"### Faits/Fait numéro {index}.md" in shown for index in range(14))
        await run(night, NOW + timedelta(days=1))
        assert len(model.asked("tidy")) == 3, "nothing changed since"


class TestWhenSomethingGoesWrongInTheNight:
    async def test_a_step_that_fails_is_retried_then_given_up_so_the_pass_ends(self, env, chat_db, monkeypatch):
        await conversation(chat_db, env, "Un mot.", NOW - timedelta(hours=3))
        night = keeper(env, NightModel())
        monkeypatch.setattr(night, "_step_regularites", AsyncMock(side_effect=RuntimeError("boom")))

        first = await run(night)
        assert first.status == "running" and first.report["echecs"] == {"step:regularites": 1}
        second = await run(night, NOW + timedelta(minutes=10), force=False)

        assert second.status == "done" and second.id == first.id
        assert any("régularités" in line or "regularites" in line for line in second.report["reste"])
        assert "step:rangement" in second.done

    async def test_a_carte_with_no_summary_is_a_failure_and_not_a_carte(self, env, chat_db):
        path = await conversation(chat_db, env, "Un mot.", NOW - timedelta(hours=3))
        model = NightModel(carte={"resume": "  ", "moments": []})

        run_ = await run(keeper(env, model))

        assert not carte_mod.parse(env.local(path)).carte.resume
        assert run_.report["echecs"] and "cartes" not in run_.report

    async def test_a_replacement_of_a_claim_the_fiche_does_not_hold_is_reported(self, env, chat_db):
        when = NOW - timedelta(hours=3)
        path = await conversation(chat_db, env, "J'ai emménagé à Rennes.", when)
        await seed(env, RENNES, RENNES, path, minute(when))
        model = NightModel(
            consolidate=proposal(
                chemin=FICHE_RENNES,
                claims=[{"texte": LYON, "heure": minute(when)}],
                remplace=[{"texte": "Le propriétaire habite à Nantes", "motif": "Lyon"}],
            )
        )

        run_ = await run(keeper(env, model))

        assert "Nantes" in json.dumps(run_.report["laissees"], ensure_ascii=False)


class TestSettingAsideWhatWasCircumstance:
    OLD = NOW - timedelta(days=60)

    async def old_fiche(self, chat_db, env):
        path = await conversation(chat_db, env, "Le plombier passe mardi.", self.OLD)
        await seed(
            env, "Le plombier passe mardi", "Le plombier passe mardi", path, minute(self.OLD), dossiers=("Maison",)
        )
        return path, "Maison/Le plombier passe mardi.md"

    async def test_what_was_circumstance_is_set_aside_but_its_file_and_conversation_stay(self, env, chat_db):
        conversation_path, fiche_path = await self.old_fiche(chat_db, env)
        model = NightModel(
            forget={"decisions": [{"chemin": fiche_path, "action": "oublier", "motif": "un rendez-vous passé"}]}
        )
        assert recall.rank(await env.service.cards(USER_A), "le plombier passe mardi", NOW.date())

        run_ = await run(keeper(env, model))

        assert env.local(fiche_path) is not None and env.local(conversation_path) is not None
        assert carte_mod.parse(env.local(fiche_path)).carte.extra["archivee"] == TODAY
        cards = await env.service.cards(USER_A)
        assert agent_memory.render_profile(cards, {}, 50) == ""
        assert recall.rank(cards, "le plombier passe mardi", NOW.date()) == []
        assert run_.report["archivees"] == [{"chemin": fiche_path, "motif": "un rendez-vous passé"}]
        assert f"mise de côté `{fiche_path}` (un rendez-vous passé)" in journal(env)

    async def test_the_signals_the_judgement_rests_on_are_shown(self, env, chat_db):
        _, fiche_path = await self.old_fiche(chat_db, env)
        indexed = await env.service.indexed(USER_A, fiche_path)
        await env.service.record_use(USER_A, indexed.card_id)
        await env.service.record_use(USER_A, indexed.card_id)
        model = NightModel()

        await run(keeper(env, model))

        asked = model.asked("forget")[0]
        assert fiche_path in asked
        assert "60 jours" in asked and "ouverte 2 fois" in asked and "pointée par" in asked

    async def test_a_recent_fiche_and_an_undated_one_are_never_offered(self, env, chat_db):
        when = NOW - timedelta(days=2)
        path = await conversation(chat_db, env, "J'ai emménagé à Rennes.", when)
        await seed(env, RENNES, RENNES, path, minute(when))
        await fiches.write_fiche(
            env.service,
            USER_A,
            titre="Son solde",
            resume="Le solde",
            dossiers=["Argent"],
            today=NOW.date(),
            affirmations=[{"texte": "Le solde est de 10 euros", "provenance": "observé", "source": "app : comptes"}],
        )
        model = NightModel()

        await run(keeper(env, model))

        assert model.asked("forget") == []

    async def test_a_fiche_kept_is_not_judged_again_the_same_week(self, env, chat_db):
        _, fiche_path = await self.old_fiche(chat_db, env)
        model = NightModel(forget={"decisions": [{"chemin": fiche_path, "action": "garder", "motif": "durable"}]})
        night = keeper(env, model)
        await run(night)

        await run(night, NOW + timedelta(days=1))
        assert len(model.asked("forget")) == 1
        await run(night, NOW + timedelta(days=8))

        assert len(model.asked("forget")) == 2

    async def test_a_fiche_written_again_comes_back_to_the_profile(self, env, chat_db):
        conversation_path, fiche_path = await self.old_fiche(chat_db, env)
        await run(
            keeper(
                env, NightModel(forget={"decisions": [{"chemin": fiche_path, "action": "oublier", "motif": "passé"}]})
            )
        )
        assert "archivee" in carte_mod.parse(env.local(fiche_path)).carte.extra

        await fiches.write_fiche(
            env.service,
            USER_A,
            titre="Le plombier passe mardi",
            chemin=fiche_path,
            today=NOW.date(),
            affirmations=[
                {
                    "texte": "Le plombier passe mardi",
                    "provenance": "dit",
                    "source": conversation_path,
                    "heure": minute(self.OLD),
                }
            ],
        )

        assert "archivee" not in carte_mod.parse(env.local(fiche_path)).carte.extra

    async def test_the_summary_says_which_fiche_was_set_aside(self, env, chat_db):
        _, fiche_path = await self.old_fiche(chat_db, env)
        await run(
            keeper(
                env, NightModel(forget={"decisions": [{"chemin": fiche_path, "action": "oublier", "motif": "passé"}]})
            )
        )

        await env.service.sync_user(USER_A)

        assert f"(mise de côté le {TODAY})" in env.local("sommaire.md")


class TestTheOrphans:
    async def test_a_fiche_gets_the_links_of_the_conversations_that_justify_it_and_orphans_are_told(self, env, chat_db):
        when = NOW - timedelta(hours=3)
        path = await conversation(chat_db, env, "J'ai emménagé à Rennes.", when)
        await seed(env, RENNES, RENNES, path, minute(when))
        await env.service.write(
            USER_A, "Divers/Sans source.md", "---\nresume: Écrite à la main\n---\n\nUn mot.\n", reason="le propriétaire"
        )

        run_ = await run(keeper(env, NightModel()))

        fiche = await env.service.indexed(USER_A, FICHE_RENNES)
        assert carte_mod.parse(env.local(path)).carte.liens == [fiche.card_id]
        assert run_.report["orphelines"]["sans_source"] == ["Divers/Sans source.md"]
        assert run_.report["orphelines"]["non_pointees"] == ["Divers/Sans source.md"]
        assert "sans source : « Sans source »" in journal(env)

    async def test_an_orphan_is_told_once_until_the_list_changes(self, env, chat_db):
        await env.service.write(
            USER_A, "Divers/Sans source.md", "---\nresume: Seule\n---\n\nUn mot.\n", reason="le propriétaire"
        )
        night = keeper(env, NightModel())
        await run(night)
        lines = journal(env).count("entretien nocturne")

        again = await run(night, NOW + timedelta(days=1))

        assert again.report["orphelines"]["sans_source"] == ["Divers/Sans source.md"]
        assert journal(env).count("entretien nocturne") == lines


class TestBoundedAndResumable:
    async def three(self, chat_db, env):
        for index in range(3):
            await conversation(chat_db, env, f"Un sujet numéro {index}.", NOW - timedelta(days=3 - index))

    async def test_a_pass_cut_off_resumes_without_redoing_what_was_done(self, env, chat_db, monkeypatch):
        await self.three(chat_db, env)
        monkeypatch.setattr(settings, "memory_night_tick_calls", 4)
        model = NightModel()
        night = keeper(env, model)

        first = await run(night)

        assert first.status == "running"
        done = model.calls
        assert done == 4
        second = await run(night, NOW + timedelta(minutes=10), force=False)
        assert second.status == "done" and second.id == first.id
        consolidated = [r for r in model.asked("consolidate")]
        assert len(consolidated) == 3 and len(set(consolidated)) == 3, "each conversation read once"
        assert model.calls == 3 + 3 + 1, "3 cartes, 3 conversations, 1 look at regularities; nothing asked twice"

    async def test_the_pass_ends_incomplete_when_it_spends_its_calls_and_says_so(self, env, chat_db, monkeypatch):
        await self.three(chat_db, env)
        monkeypatch.setattr(settings, "memory_night_max_calls", 2)
        monkeypatch.setattr(settings, "memory_night_tick_calls", 40)

        run_ = await run(keeper(env, NightModel()))

        assert run_.status == "done" and run_.calls == 2
        assert "limite d'appels" in " ".join(run_.report["reste"])
        assert "limite d'appels" in journal(env)

    async def test_a_model_that_is_down_leaves_the_pass_where_it_is_for_the_next_tick(self, env, chat_db):
        await self.three(chat_db, env)
        broken = NightModel(carte=RuntimeError("API down"))
        night = keeper(env, broken)

        run_ = await run(night)

        assert run_.status == "running"
        assert await memory_night_repo.running(USER_A) is not None
        healthy = keeper(env, NightModel())
        resumed = await run(healthy, NOW + timedelta(minutes=10), force=False)
        assert resumed.status == "done" and resumed.id == run_.id

    async def test_a_conversation_the_model_cannot_answer_is_given_up_after_two_tries_and_does_not_block_the_rest(
        self, env, chat_db
    ):
        await self.three(chat_db, env)
        garbled = NightModel(consolidate=["pas du JSON"])
        night = keeper(env, garbled)

        run_ = await run(night)

        assert run_.status == "done"
        assert len(garbled.asked("consolidate")) == 3 * 1
        assert sum(run_.report["echecs"].values()) == 3

    async def test_a_second_pass_on_a_quiet_day_asks_nothing_and_writes_nothing(self, env, chat_db):
        when = NOW - timedelta(hours=3)
        await conversation(chat_db, env, "J'ai emménagé à Rennes.", when)
        model = NightModel(consolidate=proposal(claims=[{"texte": RENNES, "heure": minute(when)}]))
        night = keeper(env, model)
        await run(night)
        calls, lines, files = model.calls, journal(env), {f: env.local(f) for f in env.service.folder(USER_A).files()}

        again = await run(night, NOW + timedelta(days=1))

        assert model.calls == calls
        assert journal(env) == lines
        assert {f: env.local(f) for f in env.service.folder(USER_A).files()} == files
        assert summary_line(again) is None

    async def test_a_conversation_that_grew_is_read_again_but_the_nights_own_carte_rewrites_do_not_count(
        self, env, chat_db
    ):
        when = NOW - timedelta(hours=3)
        path = await conversation(chat_db, env, "J'ai emménagé à Rennes.", when)
        model = NightModel()
        night = keeper(env, model)
        await run(night)
        assert len(model.asked("consolidate")) == 1
        await run(night, NOW + timedelta(days=1))
        assert len(model.asked("consolidate")) == 1, "its carte was rewritten, its messages were not"

        context_id = conversations.key_of(env.service, USER_A, path)
        session = chat_db.session()._session
        session.add(
            Message(
                id=uuid.uuid4().hex[:26],
                user_id=USER_A,
                role="user",
                content="Et je prends un chat.",
                context_id=context_id,
                created_at=when + timedelta(hours=1),
            )
        )
        session.commit()
        await conversations.record_thread(USER_A, context_id, service=env.service)
        await run(night, NOW + timedelta(days=2))

        assert len(model.asked("consolidate")) == 2

    async def test_the_nights_of_two_owners_do_not_meet(self, env, chat_db):
        when = NOW - timedelta(hours=3)
        await conversation(chat_db, env, "J'ai emménagé à Rennes.", when)
        model = NightModel()

        other = await keeper(env, model).run(USER_B, NOW, force=True)

        assert other.user_id == USER_B
        assert model.asked("consolidate") == []


class TestWhenTheNightIsDue:
    async def test_it_is_due_from_the_night_hour_once_per_local_night(self, env, chat_db):
        night = keeper(env, NightModel())
        at_two = datetime(2026, 10, 14, 0, 0, tzinfo=UTC)  # 02:00 in Paris
        at_four = datetime(2026, 10, 14, 2, 30, tzinfo=UTC)  # 04:30 in Paris

        assert not await night.due(USER_A, at_two)
        assert await night.due(USER_A, at_four)
        assert await run(night, at_four, force=False) is not None
        assert not await night.due(USER_A, at_four + timedelta(hours=3))
        assert await night.due(USER_A, at_four + timedelta(days=1))

    async def test_a_pass_cut_off_is_due_whatever_the_hour(self, env, chat_db, monkeypatch):
        for index in range(3):
            await conversation(chat_db, env, f"Sujet {index}.", NOW - timedelta(days=3 - index))
        monkeypatch.setattr(settings, "memory_night_tick_calls", 1)
        night = keeper(env, NightModel())
        noon = datetime(2026, 10, 14, 10, 0, tzinfo=UTC)
        started = await run(night, noon)
        assert started.status == "running"

        assert await night.due(USER_A, noon + timedelta(minutes=10))

    async def test_without_a_model_or_memory_the_night_does_nothing(self, env):
        night = keeper(env, NightModel())
        night.client = None

        assert await night.run(USER_A, NOW, force=True) is None
        assert await night.run_all(NOW) == []


class TestTheReport:
    async def test_the_report_is_kept_with_the_pass_and_the_journal_sums_it_up(self, env, chat_db):
        when = NOW - timedelta(hours=3)
        await conversation(chat_db, env, "J'ai emménagé à Rennes.", when)
        model = NightModel(consolidate=proposal(claims=[{"texte": RENNES, "heure": minute(when)}]))

        run_ = await run(keeper(env, model))

        kept = await memory_night_repo.last_done(USER_A)
        assert kept.report == run_.report and kept.status == "done" and kept.finished_at is not None
        assert kept.report["appels"] == model.calls
        line = journal(env).strip().splitlines()[-1]
        assert f"entretien nocturne du {TODAY} ({model.calls} appel(s) au modèle)" in line
        assert "1 conversation(s) relue(s)" in line and "1 fiche(s) créée(s)" in line

    async def test_the_last_pass_of_a_night_is_the_most_recent_one(self, env):
        first = await memory_night_repo.start(USER_A, TODAY)
        first.status, first.report = "done", {"appels": 1}
        await memory_night_repo.save(first)
        second = await memory_night_repo.start(USER_A, TODAY)
        second.status, second.report = "done", {"appels": 2}
        await memory_night_repo.save(second)

        assert (await memory_night_repo.last_done(USER_A)).id == second.id

    async def test_what_the_night_writes_says_in_the_journal_that_it_was_the_night(self, env, chat_db):
        when = NOW - timedelta(hours=3)
        await conversation(chat_db, env, "J'ai emménagé à Rennes.", when)
        model = NightModel(consolidate=proposal(claims=[{"texte": RENNES, "heure": minute(when)}]))

        await run(keeper(env, model))

        assert f"créé `{FICHE_RENNES}` — entretien nocturne du {TODAY}" in journal(env)

    async def test_the_pass_is_pushed_to_the_bucket_at_its_end(self, env, chat_db):
        when = NOW - timedelta(hours=3)
        await conversation(chat_db, env, "J'ai emménagé à Rennes.", when)
        model = NightModel(consolidate=proposal(claims=[{"texte": RENNES, "heure": minute(when)}]))

        await run(keeper(env, model))

        assert RENNES in (env.remote(FICHE_RENNES) or "")
        assert "entretien nocturne" in (env.remote("journal.md") or "")
