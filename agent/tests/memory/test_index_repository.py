from datetime import UTC, datetime, timedelta

from app.db.memory_index_repository import memory_index_repo, memory_outbox_repo, memory_usage_repo
from app.memory import carte
from app.memory.indexer import index_file
from tests.memory.conftest import USER_A, USER_B, fiche


async def test_a_file_is_indexed_with_its_terms_and_links(memory_db):
    text = carte.render(
        carte.Carte(
            id="abcdef0123456789",
            resume="Les fleurs de Françoise",
            personnes=["Françoise"],
            evoque=["Cadeau", "anniversaire"],
            alias=["Fanchon"],
            liens=["1111111111111111"],
            sources=[{"id": "2222222222222222", "titre": "Conversation", "provenance": "dit"}],
        ),
        "Elle préfère les pivoines.",
    )

    indexed = await index_file(USER_A, "Famille/Roses.md", text)

    assert indexed.card_id == "abcdef0123456789" and indexed.stamped is None
    terms = dict.fromkeys(await memory_index_repo.terms(USER_A, "Famille/Roses.md"))
    assert ("evoque", "cadeau") in terms and ("alias", "fanchon") in terms and ("personne", "françoise") in terms
    links = await memory_index_repo.links(USER_A, "Famille/Roses.md")
    assert ("lien", "1111111111111111") in links and ("source", "2222222222222222") in links
    assert await memory_index_repo.linked_from(USER_A, "1111111111111111") == ["Famille/Roses.md"]
    assert [f.path for f in await memory_index_repo.search(USER_A, "cadeau")] == ["Famille/Roses.md"]


async def test_reindexing_replaces_the_old_rows(memory_db):
    await index_file(USER_A, "A.md", fiche("x", evoque=["jardin"], ident="abcdef0123456789"))
    await index_file(USER_A, "A.md", fiche("x", evoque=["cuisine"], ident="abcdef0123456789"))

    assert [t for _, t in await memory_index_repo.terms(USER_A, "A.md")] == ["cuisine"]
    assert len(await memory_index_repo.list_files(USER_A)) == 1


async def test_search_is_scoped_to_the_user_and_ignores_empty_queries(memory_db):
    await index_file(USER_A, "A.md", fiche("des pivoines", ident="abcdef0123456789"))

    assert await memory_index_repo.search(USER_B, "pivoines") == []
    assert await memory_index_repo.search(USER_A, "   ") == []
    assert await memory_index_repo.search(USER_A, "100%_") == []


async def test_clear_user_leaves_the_others_the_counters_and_the_outbox(memory_db):
    await index_file(USER_A, "A.md", fiche("a", ident="aaaaaaaaaaaaaaaa"))
    await index_file(USER_B, "A.md", fiche("b", ident="bbbbbbbbbbbbbbbb"))
    await memory_usage_repo.record(USER_A, "aaaaaaaaaaaaaaaa")
    await memory_outbox_repo.enqueue(USER_A, "A.md", "put", "a", None)

    assert await memory_index_repo.clear_user(USER_A) == 1

    assert await memory_index_repo.list_files(USER_A) == [] and len(await memory_index_repo.list_files(USER_B)) == 1
    assert (await memory_usage_repo.get(USER_A, "aaaaaaaaaaaaaaaa"))[0] == 1
    assert await memory_outbox_repo.count(USER_A) == 1


async def test_counters_count_per_user_and_card(memory_db):
    at = datetime(2026, 10, 13, tzinfo=UTC)
    await memory_usage_repo.record(USER_A, "c1", at)
    await memory_usage_repo.record(USER_A, "c1", at + timedelta(days=1))
    await memory_usage_repo.record(USER_B, "c1", at)

    count, last = await memory_usage_repo.get(USER_A, "c1")
    assert count == 2 and last.replace(tzinfo=None) == (at + timedelta(days=1)).replace(tzinfo=None)
    assert await memory_usage_repo.all(USER_B) == {"c1": 1}
    assert (await memory_usage_repo.get(USER_A, "inconnue"))[0] == 0


class TestOutbox:
    async def test_a_newer_write_replaces_the_body_and_keeps_the_first_base(self, memory_db):
        await memory_outbox_repo.enqueue(USER_A, "A.md", "put", "un", "etag-1")
        await memory_outbox_repo.enqueue(USER_A, "A.md", "put", "deux", "etag-2")

        [entry] = await memory_outbox_repo.pending(USER_A)
        assert (entry.body, entry.base_etag) == ("deux", "etag-1")

    async def test_appends_accumulate(self, memory_db):
        await memory_outbox_repo.enqueue(USER_A, "journal.md", "append", "- a\n", None)
        await memory_outbox_repo.enqueue(USER_A, "journal.md", "append", "- b\n", None)

        assert (await memory_outbox_repo.get(USER_A, "journal.md")).body == "- a\n- b\n"

    async def test_done_skips_an_entry_rewritten_while_it_was_pushed(self, memory_db):
        await memory_outbox_repo.enqueue(USER_A, "A.md", "put", "un", None)
        first = await memory_outbox_repo.get(USER_A, "A.md")
        await memory_outbox_repo.enqueue(USER_A, "A.md", "put", "deux", None)

        await memory_outbox_repo.done(USER_A, "A.md", only_if_updated_at=first.updated_at)
        assert await memory_outbox_repo.count(USER_A) == 1

        await memory_outbox_repo.done(USER_A, "A.md")
        assert await memory_outbox_repo.count() == 0

    async def test_a_skipped_done_moves_the_base_to_the_etag_the_push_obtained(self, memory_db):
        await memory_outbox_repo.enqueue(USER_A, "A.md", "put", "un", "etag-1")
        first = await memory_outbox_repo.get(USER_A, "A.md")
        await memory_outbox_repo.enqueue(USER_A, "A.md", "put", "deux", "etag-1")

        await memory_outbox_repo.done(USER_A, "A.md", only_if_updated_at=first.updated_at, new_base_etag="etag-2")

        [entry] = await memory_outbox_repo.pending(USER_A)
        assert (entry.body, entry.base_etag) == ("deux", "etag-2")

    async def test_a_skipped_done_can_move_the_base_to_nothing(self, memory_db):
        await memory_outbox_repo.enqueue(USER_A, "A.md", "delete", None, "etag-1")
        first = await memory_outbox_repo.get(USER_A, "A.md")
        await memory_outbox_repo.enqueue(USER_A, "A.md", "put", "deux", "etag-1")

        await memory_outbox_repo.done(USER_A, "A.md", only_if_updated_at=first.updated_at, new_base_etag=None)

        assert (await memory_outbox_repo.get(USER_A, "A.md")).base_etag is None

    async def test_a_skipped_done_without_a_new_base_leaves_the_base(self, memory_db):
        await memory_outbox_repo.enqueue(USER_A, "A.md", "put", "un", "etag-1")
        first = await memory_outbox_repo.get(USER_A, "A.md")
        await memory_outbox_repo.enqueue(USER_A, "A.md", "put", "deux", "etag-1")

        await memory_outbox_repo.done(USER_A, "A.md", only_if_updated_at=first.updated_at)

        assert (await memory_outbox_repo.get(USER_A, "A.md")).base_etag == "etag-1"

    async def test_failures_are_counted_and_users_listed(self, memory_db):
        await memory_outbox_repo.enqueue(USER_B, "A.md", "put", "x", None)
        await memory_outbox_repo.failed(USER_B, "A.md", "bucket down")

        assert (await memory_outbox_repo.get(USER_B, "A.md")).attempts == 1
        assert await memory_outbox_repo.users() == [USER_B]
        assert await memory_outbox_repo.pending(USER_A) == []
