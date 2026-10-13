from app.db.memory_index_repository import memory_index_repo, memory_outbox_repo, memory_usage_repo
from app.memory import carte, paths
from tests.memory.conftest import USER_A, USER_B, fiche


async def _snapshot(user):
    files = await memory_index_repo.list_files(user)
    return [
        (f.path, f.kind, f.card_id, f.resume, f.body, f.content_hash, await memory_index_repo.terms(user, f.path))
        for f in files
    ]


async def test_an_emptied_index_is_rebuilt_in_full_except_the_counters(env):
    await env.service.write(USER_A, "Famille/Roses.md", fiche("Pivoines.", resume="Fleurs", evoque=["cadeau"]))
    await env.service.write(USER_A, "Jardin/Tulipes.md", fiche("Avril.", resume="Bulbes"))
    await env.service.write(USER_A, "Conversations/2026/10/2026-10-13 09h30 — Cadeau.md", "Discussion.", conversation=True)
    await env.service.sync_user(USER_A)
    ident = (await memory_index_repo.get(USER_A, "Famille/Roses.md")).card_id
    await env.service.record_use(USER_A, ident)
    await env.service.record_use(USER_A, ident)
    before = await _snapshot(USER_A)
    assert len(before) == 3

    await memory_index_repo.clear_user(USER_A)
    assert await memory_index_repo.list_files(USER_A) == []
    report = await env.service.rebuild(USER_A)

    assert (report.users, report.files, report.skipped) == (1, 3, [])
    assert await _snapshot(USER_A) == before
    assert (await memory_usage_repo.get(USER_A, ident))[0] == 2
    assert [f.path for f in await env.service.search(USER_A, "cadeau")]


async def test_rebuild_works_with_the_bucket_down_from_the_local_copy(env):
    await env.service.write(USER_A, "Roses.md", fiche("Pivoines."))
    await env.service.sync_user(USER_A)
    before = await _snapshot(USER_A)
    env.bucket.down = True

    await memory_index_repo.clear_user(USER_A)
    report = await env.service.rebuild(USER_A)

    assert report.files == 1 and await _snapshot(USER_A) == before


async def test_rebuild_takes_the_owners_corrections_first(env):
    await env.service.write(USER_A, "Roses.md", fiche("Pivoines."))
    await env.service.sync_user(USER_A)
    env.bucket.seed(env.key("Roses.md"), fiche("Tulipes.", ident=carte.parse(env.local("Roses.md")).carte.id))

    await env.service.rebuild(USER_A)

    assert [f.path for f in await env.service.search(USER_A, "tulipes")] == ["Roses.md"]


async def test_a_user_with_an_index_but_no_local_copy_is_left_alone_while_the_bucket_is_down(env):
    await env.service.write(USER_A, "Roses.md", fiche("Pivoines."))
    await env.service.sync_user(USER_A)
    env.service.folder(USER_A).wipe()
    env.bucket.down = True

    report = await env.service.rebuild()

    assert report.skipped == [USER_A]
    assert await env.service.read(USER_A, "Roses.md") is not None
    assert [f.path for f in await env.service.search(USER_A, "pivoines")] == ["Roses.md"]


async def test_rebuild_of_everyone_keeps_the_outbox_and_the_users_apart(env):
    await env.service.write(USER_A, "A.md", fiche("a"))
    await env.service.write(USER_B, "B.md", fiche("b"))
    env.bucket.down = True

    report = await env.service.rebuild()

    assert report.users == 2
    assert [f.path for f in await memory_index_repo.list_files(USER_A, kind="fiche")] == ["A.md"]
    assert [f.path for f in await memory_index_repo.list_files(USER_B, kind="fiche")] == ["B.md"]
    assert await memory_outbox_repo.count(USER_A) >= 1 and paths.JOURNAL in {
        e.path for e in await memory_outbox_repo.pending(USER_B)
    }
