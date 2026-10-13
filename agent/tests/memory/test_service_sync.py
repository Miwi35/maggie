import logging
import time

import pytest

from app.db.memory_index_repository import memory_index_repo, memory_outbox_repo, memory_usage_repo
from app.memory import carte, generated, paths
from app.memory.bucket import BucketUnavailable
from app.memory.service import MemoryService
from app.metrics import MEMORY_DEGRADED, MEMORY_SYNC_FAILURES
from tests.memory.conftest import USER_A, USER_B, fiche

ROSES = "Famille/Françoise n'aime pas les roses.md"


def _failures() -> float:
    return MEMORY_SYNC_FAILURES.labels(reason="BucketUnavailable")._value.get()


class TestWriteAndPush:
    async def test_a_write_lands_locally_in_the_index_and_in_the_outbox_before_any_network(self, env):
        await env.service.write(USER_A, ROSES, fiche("Elle préfère les pivoines.", resume="Fleurs de Françoise"))

        assert "pivoines" in env.local(ROSES)
        assert (await memory_index_repo.get(USER_A, ROSES)).resume == "Fleurs de Françoise"
        assert (await memory_outbox_repo.get(USER_A, ROSES)).op == "put"
        assert env.bucket.calls == []

    async def test_the_fiche_gets_an_id_and_the_sync_pushes_it_with_the_generated_files(self, env):
        await env.service.write(USER_A, ROSES, fiche("Elle préfère les pivoines.", resume="Fleurs de Françoise"))

        report = await env.service.sync_user(USER_A)

        assert report.pushed >= 1
        pushed = env.remote(ROSES)
        assert carte.is_valid_id(carte.parse(pushed).carte.id)
        assert pushed == env.local(ROSES)
        for name in (paths.README, paths.AGENTS, paths.SUMMARY, paths.JOURNAL):
            assert generated.is_generated_text(env.remote(name)), name
        assert "Françoise n'aime pas les roses" in env.remote(paths.SUMMARY)
        assert "créé `Famille/Françoise n'aime pas les roses.md`" in env.remote(paths.JOURNAL)
        assert await memory_outbox_repo.count(USER_A) == 0

    async def test_a_second_pass_with_nothing_to_do_does_no_writes(self, env):
        await env.service.write(USER_A, ROSES, fiche("x"))
        await env.service.sync_user(USER_A)
        puts = env.bucket.count("put")

        report = await env.service.sync_user(USER_A)

        assert not report.changed()
        assert env.bucket.count("put") == puts

    async def test_unsafe_writes_are_refused_and_leave_nothing(self, env):
        for rel in ("../x.md", "a/../../x.md", "/abs.md", "a\\b.md", "journal.md", "Conflits/x.md", "x.txt"):
            with pytest.raises(paths.UnsafePath):
                await env.service.write(USER_A, rel, "x")
        with pytest.raises(paths.UnsafePath):
            await env.service.write("../autre", "x.md", "x")
        assert await memory_outbox_repo.count() == 0
        assert not env.root.exists()

    async def test_a_conversation_is_written_only_when_said_so_and_is_indexed_as_one(self, env):
        rel = "Conversations/2026/10/2026-10-13 09h30 — Cadeau.md"
        with pytest.raises(paths.UnsafePath):
            await env.service.write(USER_A, rel, "x")
        await env.service.write(USER_A, rel, "Elle a parlé de fleurs.", conversation=True)
        assert (await memory_index_repo.get(USER_A, rel)).kind == "conversation"


class TestPull:
    async def test_a_file_changed_in_the_bucket_is_taken_at_the_next_sync_and_indexed(self, env):
        await env.service.write(USER_A, ROSES, fiche("Elle préfère les pivoines."))
        await env.service.sync_user(USER_A)
        ident = carte.parse(env.local(ROSES)).carte.id

        env.bucket.seed(env.key(ROSES), fiche("Elle préfère les tulipes.", resume="Corrigé", ident=ident))
        report = await env.service.sync_user(USER_A)

        assert report.pulled == 1
        assert "tulipes" in env.local(ROSES)
        assert (await memory_index_repo.get(USER_A, ROSES)).resume == "Corrigé"
        assert [f.path for f in await env.service.search(USER_A, "tulipes")] == [ROSES]
        assert await env.service.search(USER_A, "pivoines") == []

    async def test_a_new_pod_rehydrates_the_whole_folder_from_the_bucket(self, env, tmp_path):
        await env.service.write(USER_A, ROSES, fiche("Elle préfère les pivoines."))
        await env.service.sync_user(USER_A)

        fresh = MemoryService(env.bucket, tmp_path / "other-pod", clock=env.clock, push_after_write=False)
        report = await fresh.sync_user(USER_A)

        assert report.pulled >= 5
        assert fresh.folder(USER_A).read(ROSES) == env.local(ROSES)
        assert sorted(fresh.folder(USER_A).files()) == sorted(env.service.folder(USER_A).files())

    async def test_a_file_deleted_in_the_bucket_leaves_the_folder_and_the_index(self, env):
        await env.service.write(USER_A, ROSES, fiche("x"))
        await env.service.sync_user(USER_A)

        env.bucket.remove(env.key(ROSES))
        report = await env.service.sync_user(USER_A)

        assert report.deleted == 1
        assert env.local(ROSES) is None
        assert await memory_index_repo.get(USER_A, ROSES) is None

    async def test_a_rename_in_the_bucket_keeps_the_id_and_the_counters(self, env):
        await env.service.write(USER_A, ROSES, fiche("x", ident="abcdef0123456789"))
        await env.service.sync_user(USER_A)
        await env.service.record_use(USER_A, "abcdef0123456789")
        moved = env.remote(ROSES)

        env.bucket.remove(env.key(ROSES))
        env.bucket.seed(env.key("Jardin/Pivoines.md"), moved)
        await env.service.sync_user(USER_A)

        assert env.local(ROSES) is None
        assert (await memory_index_repo.get(USER_A, "Jardin/Pivoines.md")).card_id == "abcdef0123456789"
        assert (await memory_usage_repo.get(USER_A, "abcdef0123456789"))[0] == 1

    async def test_a_copied_fiche_gets_an_id_of_its_own(self, env):
        await env.service.write(USER_A, "Roses.md", fiche("x", ident="abcdef0123456789"))
        await env.service.sync_user(USER_A)

        env.bucket.seed(env.key("Copie.md"), env.remote("Roses.md"))
        await env.service.sync_user(USER_A)

        copy_id = carte.parse(env.remote("Copie.md")).carte.id
        assert copy_id != "abcdef0123456789" and carte.is_valid_id(copy_id)
        assert carte.parse(env.remote("Roses.md")).carte.id == "abcdef0123456789"

    async def test_a_note_written_by_hand_in_the_bucket_is_stamped_and_the_text_is_kept(self, env):
        env.bucket.seed(env.key("Notes/Idée cadeau.md"), "Des fleurs pour Françoise.\n")
        await env.service.sync_user(USER_A)

        stamped = env.remote("Notes/Idée cadeau.md")
        assert carte.is_valid_id(carte.parse(stamped).carte.id)
        assert "Des fleurs pour Françoise." in stamped

    async def test_a_broken_carte_is_indexed_as_unreadable_and_never_rewritten(self, env):
        broken = "---\nid: [oops\n---\nTexte du propriétaire.\n"
        env.bucket.seed(env.key("Cassée.md"), broken)
        await env.service.sync_user(USER_A)

        assert env.remote("Cassée.md") == broken
        assert (await memory_index_repo.get(USER_A, "Cassée.md")).unreadable

    async def test_non_markdown_and_foreign_objects_are_not_pulled(self, env):
        env.bucket.seed(env.key("notes.txt"), "x")
        env.bucket.seed(f"{USER_A}/Autre/x.md", "x")
        await env.service.sync_user(USER_A)
        assert env.local("notes.txt") is None
        assert env.service.folder(USER_A).files() != []
        assert all(not f.startswith("Autre") for f in env.service.folder(USER_A).files())


class TestOwnerWins:
    async def test_his_edit_made_before_my_push_stays_and_mine_is_set_aside(self, env):
        await env.service.write(USER_A, ROSES, fiche("Version 1."))
        await env.service.sync_user(USER_A)
        ident = carte.parse(env.local(ROSES)).carte.id
        await env.service.write(USER_A, ROSES, fiche("Version de Maggie.", ident=ident))

        env.bucket.seed(env.key(ROSES), fiche("Version du propriétaire.", ident=ident))
        report = await env.service.sync_user(USER_A)

        assert report.conflicts == 1
        assert "propriétaire" in env.remote(ROSES) and "propriétaire" in env.local(ROSES)
        [aside] = env.service.conflicts(USER_A)
        assert aside.startswith("Conflits/") and "version de Maggie" in aside
        assert "Version de Maggie." in env.remote(aside)
        journal = env.remote(paths.JOURNAL)
        assert "conflit sur `" + ROSES + "`" in journal and aside in journal
        assert await memory_outbox_repo.count(USER_A) == 0

    async def test_the_index_follows_his_version(self, env):
        await env.service.write(USER_A, ROSES, fiche("Version 1."))
        await env.service.sync_user(USER_A)
        await env.service.write(USER_A, ROSES, fiche("Maggie."))

        env.bucket.seed(env.key(ROSES), fiche("Propriétaire.", resume="Sa version"))
        await env.service.sync_user(USER_A)

        assert (await memory_index_repo.get(USER_A, ROSES)).resume == "Sa version"
        assert [f.path for f in await env.service.search(USER_A, "maggie")] == []

    async def test_a_new_file_written_in_the_bucket_at_the_same_name_wins_too(self, env):
        await env.service.write(USER_A, ROSES, fiche("Maggie."))
        env.bucket.seed(env.key(ROSES), "Propriétaire.\n")

        await env.service.sync_user(USER_A)

        assert env.remote(ROSES).count("Propriétaire.") == 1 and "Maggie." not in env.remote(ROSES)
        assert len(env.service.conflicts(USER_A)) == 1

    async def test_the_notice_tells_maggie_about_set_aside_versions(self, env):
        await env.service.write(USER_A, ROSES, fiche("Maggie."))
        env.bucket.seed(env.key(ROSES), "Propriétaire.\n")
        await env.service.sync_user(USER_A)

        assert "Conflits/" in await env.service.notice(USER_A)

    async def test_my_delete_does_not_remove_a_file_he_edited_meanwhile(self, env):
        await env.service.write(USER_A, ROSES, fiche("x"))
        await env.service.sync_user(USER_A)
        await env.service.delete(USER_A, ROSES)

        env.bucket.seed(env.key(ROSES), "Corrigé par lui.\n")
        await env.service.sync_user(USER_A)

        assert "Corrigé par lui." in env.remote(ROSES) and env.remote(ROSES) == env.local(ROSES)

    async def test_my_delete_reaches_the_bucket_when_nobody_touched_the_file(self, env):
        await env.service.write(USER_A, ROSES, fiche("x"))
        await env.service.sync_user(USER_A)

        assert await env.service.delete(USER_A, ROSES, reason="plus vrai")
        assert env.local(ROSES) is None and await memory_index_repo.get(USER_A, ROSES) is None
        await env.service.sync_user(USER_A)

        assert env.remote(ROSES) is None
        assert "supprimé `" + ROSES + "` — plus vrai" in env.remote(paths.JOURNAL)

    async def test_deleting_what_does_not_exist_is_a_noop(self, env):
        assert not await env.service.delete(USER_A, "Rien.md")

    async def test_his_lines_in_the_journal_and_mine_both_stay(self, env):
        await env.service.write(USER_A, "A.md", fiche("x"))
        await env.service.sync_user(USER_A)
        env.bucket.seed(env.key(paths.JOURNAL), env.remote(paths.JOURNAL) + "- ma note à moi\n")

        await env.service.write(USER_A, "B.md", fiche("y"))
        report = await env.service.sync_user(USER_A)

        assert report.conflicts == 0
        journal = env.remote(paths.JOURNAL)
        assert "ma note à moi" in journal and "créé `B.md`" in journal and "créé `A.md`" in journal
        assert env.local(paths.JOURNAL) == journal

    async def test_a_spurious_412_is_not_a_conflict(self, env):
        await env.service.write(USER_A, ROSES, fiche("x"))
        env.bucket.fail_next_puts_with_412 = 1

        report = await env.service.sync_user(USER_A)

        assert report.conflicts == 0
        assert env.remote(ROSES) == env.local(ROSES)


class TestGeneratedFiles:
    async def test_an_edited_generated_file_is_never_regenerated(self, env):
        await env.service.sync_user(USER_A)
        env.bucket.seed(env.key(paths.README), env.remote(paths.README) + "\nMa remarque.\n")
        edited = env.remote(paths.README)

        await env.service.write(USER_A, ROSES, fiche("x"))
        await env.service.sync_user(USER_A)

        assert env.remote(paths.README) == edited == env.local(paths.README)

    async def test_a_generated_file_the_owner_deleted_comes_back(self, env):
        await env.service.write(USER_A, ROSES, fiche("x"))
        await env.service.sync_user(USER_A)
        env.bucket.remove(env.key(paths.SUMMARY))

        await env.service.sync_user(USER_A)

        assert "Françoise n'aime pas les roses" in env.remote(paths.SUMMARY)

    async def test_the_summary_follows_the_fiches(self, env):
        await env.service.write(USER_A, "A.md", fiche("x", resume="Premier"))
        await env.service.sync_user(USER_A)
        await env.service.write(USER_A, "B.md", fiche("y", resume="Second"))
        await env.service.sync_user(USER_A)

        assert "- A — Premier" in env.remote(paths.SUMMARY) and "- B — Second" in env.remote(paths.SUMMARY)


class TestDegradedMode:
    async def test_with_the_bucket_down_maggie_still_reads_searches_and_writes(self, env, caplog):
        await env.service.write(USER_A, ROSES, fiche("Elle préfère les pivoines."))
        await env.service.sync_user(USER_A)
        env.bucket.down = True
        before = _failures()

        with caplog.at_level(logging.CRITICAL, logger="app.memory.service"):
            with pytest.raises(BucketUnavailable):
                await env.service.sync_user(USER_A)
        await env.service.write(USER_A, "Jardin/Tulipes.md", fiche("Les tulipes en avril."))

        assert env.service.degraded(USER_A) and MEMORY_DEGRADED._value.get() == 1
        assert _failures() == before + 1
        assert any(r.levelno == logging.CRITICAL and USER_A in r.getMessage() for r in caplog.records)
        assert "pivoines" in await env.service.read(USER_A, ROSES)
        assert [f.path for f in await env.service.search(USER_A, "tulipes")] == ["Jardin/Tulipes.md"]
        assert await memory_outbox_repo.count(USER_A) >= 2

    async def test_maggie_is_told_her_memory_may_be_late(self, env):
        env.bucket.down = True
        await env.service.write(USER_A, ROSES, fiche("x"))
        with pytest.raises(BucketUnavailable):
            await env.service.sync_user(USER_A)

        notice = await env.service.notice(USER_A)

        assert "peut-être en retard" in notice and "écriture(s) attendent" in notice
        assert await env.service.notice(USER_B) == ""

    async def test_the_writes_made_during_the_outage_are_pushed_on_return_without_loss(self, env):
        await env.service.write(USER_A, ROSES, fiche("Avant."))
        await env.service.sync_user(USER_A)
        env.bucket.down = True
        await env.service.write(USER_A, "Jardin/Tulipes.md", fiche("Pendant la panne."))
        with pytest.raises(BucketUnavailable):
            await env.service.sync_user(USER_A)
        assert env.remote("Jardin/Tulipes.md") is None

        env.bucket.down = False
        await env.service.sync_user(USER_A)

        assert "Pendant la panne." in env.remote("Jardin/Tulipes.md")
        assert not env.service.degraded(USER_A) and await env.service.notice(USER_A) == ""
        assert await memory_outbox_repo.count(USER_A) == 0
        assert "créé `Jardin/Tulipes.md`" in env.remote(paths.JOURNAL)

    async def test_a_write_edited_twice_during_the_outage_pushes_the_last_version_once(self, env):
        env.bucket.down = True
        await env.service.write(USER_A, ROSES, fiche("Première."))
        await env.service.write(USER_A, ROSES, fiche("Seconde."))
        env.bucket.down = False

        await env.service.sync_user(USER_A)

        assert "Seconde." in env.remote(ROSES) and "Première." not in env.remote(ROSES)

    async def test_the_outbox_survives_a_pod_that_loses_its_disk(self, env, tmp_path):
        env.bucket.down = True
        await env.service.write(USER_A, ROSES, fiche("Écrit avant la panne du pod."))
        env.bucket.down = False

        fresh = MemoryService(env.bucket, tmp_path / "nouveau-pod", clock=env.clock, push_after_write=False)
        await fresh.sync_user(USER_A)

        assert "Écrit avant la panne du pod." in env.remote(ROSES)
        assert "Écrit avant la panne du pod." in fresh.folder(USER_A).read(ROSES)

    async def test_an_empty_listing_after_files_were_known_is_an_outage_not_a_mass_delete(self, env):
        await env.service.write(USER_A, ROSES, fiche("x"))
        await env.service.sync_user(USER_A)
        saved = dict(env.bucket.objects)
        env.bucket.objects.clear()

        with pytest.raises(BucketUnavailable):
            await env.service.sync_user(USER_A)

        assert env.local(ROSES) is not None and await memory_index_repo.get(USER_A, ROSES) is not None
        env.bucket.objects.update(saved)
        await env.service.sync_user(USER_A)
        assert not env.service.degraded(USER_A)

    async def test_a_listing_that_dies_half_way_deletes_nothing(self, env):
        env.bucket.page_size = 2
        for index in range(5):
            await env.service.write(USER_A, f"Note {index}.md", fiche(f"texte {index}"))
        await env.service.sync_user(USER_A)
        local = env.service.folder(USER_A).files()

        env.bucket.fail_listing_after_pages = 1
        with pytest.raises(BucketUnavailable):
            await env.service.sync_user(USER_A)

        assert env.service.folder(USER_A).files() == local

    async def test_before_a_turn_a_dead_bucket_never_blocks_the_answer(self, env):
        env.service.turn_timeout_seconds = 0.5
        env.bucket.down = True
        started = time.monotonic()

        await env.service.before_turn(USER_A)

        assert time.monotonic() - started < 1.5
        assert env.service.degraded(USER_A)

    async def test_before_a_turn_a_fresh_sync_is_not_repeated(self, env):
        await env.service.before_turn(USER_A)
        calls = len(env.bucket.calls)

        await env.service.before_turn(USER_A)

        assert len(env.bucket.calls) == calls

    async def test_a_service_without_a_bucket_does_nothing(self, memory_db, tmp_path):
        service = MemoryService(None, tmp_path)
        await service.before_turn(USER_A)
        assert not service.enabled and await service.notice(USER_A) == ""


class TestIsolation:
    async def test_two_users_never_see_each_other(self, env):
        await env.service.write(USER_A, "Roses.md", fiche("Le secret d'Alice : pivoines.", resume="Alice"))
        await env.service.write(USER_B, "Roses.md", fiche("Le secret de Bob : tulipes.", resume="Bob"))
        await env.service.sync_user(USER_A)
        await env.service.sync_user(USER_B)

        assert "pivoines" in env.remote("Roses.md", USER_A) and "tulipes" not in env.remote("Roses.md", USER_A)
        assert "tulipes" in env.remote("Roses.md", USER_B) and "pivoines" not in env.remote("Roses.md", USER_B)
        assert await env.service.search(USER_B, "pivoines") == []
        assert await env.service.search(USER_A, "tulipes") == []
        assert [f.path for f in await env.service.search(USER_A, "pivoines")] == ["Roses.md"]
        assert (await memory_index_repo.get(USER_B, "Roses.md")).resume == "Bob"
        assert all(k.startswith((f"{USER_A}/Mémoire/", f"{USER_B}/Mémoire/")) for k in env.bucket.objects)

    async def test_a_pass_for_one_user_does_not_touch_the_other(self, env):
        await env.service.write(USER_A, "Roses.md", fiche("x"))

        await env.service.sync_user(USER_B)

        assert env.remote("Roses.md", USER_A) is None
        assert await memory_outbox_repo.count(USER_A) == 2
        assert env.service.folder(USER_B).files() != [] and "Roses.md" not in env.service.folder(USER_B).files()

    async def test_another_users_objects_in_the_bucket_are_not_pulled(self, env):
        env.bucket.seed(env.key("Secret.md", USER_A), "à Alice")
        await env.service.sync_user(USER_B)

        assert env.local("Secret.md", USER_B) is None
        assert await env.service.read(USER_B, "Secret.md") is None

    async def test_a_deletion_by_one_user_is_not_the_others(self, env):
        await env.service.write(USER_A, "Roses.md", fiche("a"))
        await env.service.write(USER_B, "Roses.md", fiche("b"))
        await env.service.sync_user(USER_A)
        await env.service.sync_user(USER_B)

        await env.service.delete(USER_A, "Roses.md")
        await env.service.sync_user(USER_A)
        await env.service.sync_user(USER_B)

        assert env.remote("Roses.md", USER_A) is None and env.remote("Roses.md", USER_B) is not None
        assert env.local("Roses.md", USER_B) is not None

    async def test_the_sync_of_everyone_covers_every_user_known_to_the_bucket(self, env):
        env.bucket.seed(env.key("A.md", USER_A), "a")
        env.bucket.seed(env.key("B.md", USER_B), "b")
        env.bucket.seed("skills/x.md", "pas un utilisateur")

        assert await env.service.sync_all()

        assert env.local("A.md", USER_A) and env.local("B.md", USER_B)
        assert await env.service.users() == [USER_A, USER_B]

    async def test_sync_all_reports_a_failure_and_goes_degraded(self, env):
        await env.service.write(USER_A, "A.md", fiche("a"))
        env.bucket.down = True

        assert not await env.service.sync_all()
        assert env.service.degraded()
