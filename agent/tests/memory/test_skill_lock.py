"""A skill the owner writes from the admin is locked in its file; Maggie's tools no longer rewrite it (MAG-892)."""

import pytest

from app.skills.document import parse_markdown, render_markdown
from app.skills.index import SkillLocked
from tests.memory.test_skill_files import RAPPEL, SCOPE, skills  # noqa: F401  (skills is a fixture)

REL = "rappel-livraison.md"


def _locked(skills) -> bool:  # noqa: F811
    return skills.files.load(RAPPEL[0])[0].locked


class TestTheOwnerLocksASkill:
    async def test_a_skill_the_owner_edits_is_locked_in_its_file(self, skills):  # noqa: F811
        await skills.index.create(*RAPPEL, user_id="u")

        await skills.index.update(RAPPEL[0], content="Version du propriétaire.", user_id="u", by_owner=True)

        assert "verrouillee: true" in skills.local(REL)
        assert _locked(skills)

    async def test_a_skill_the_owner_creates_is_locked(self, skills):  # noqa: F811
        await skills.index.create(*RAPPEL, user_id="u", by_owner=True)

        assert "verrouillee: true" in skills.local(REL)
        assert _locked(skills)

    async def test_the_owner_can_still_edit_a_locked_skill_and_it_stays_locked(self, skills):  # noqa: F811
        await skills.index.create(*RAPPEL, user_id="u", by_owner=True)

        await skills.index.update(RAPPEL[0], content="Encore moi.", user_id="u", by_owner=True)
        await skills.index.create(*RAPPEL[:3], "Remplacé.", user_id="u", by_owner=True)

        assert _locked(skills)
        assert "Remplacé." in skills.local(REL)

    async def test_a_skill_maggie_wrote_stays_unlocked_and_she_can_update_it(self, skills):  # noqa: F811
        await skills.index.create(*RAPPEL, user_id="u")

        await skills.index.update(RAPPEL[0], content="Corrigé par Maggie.", user_id="u")

        assert not _locked(skills)
        assert "verrouillee" not in skills.local(REL)
        assert "Corrigé par Maggie." in skills.local(REL)

    async def test_a_skill_with_no_lock_is_rendered_as_before(self, skills):  # noqa: F811
        await skills.index.create(*RAPPEL, user_id="u")

        assert skills.local(REL) == render_markdown(*RAPPEL)


class TestMaggieCannotRewriteALockedSkill:
    async def test_maggie_cannot_update_a_locked_skill(self, skills):  # noqa: F811
        await skills.index.create(*RAPPEL, user_id="u", by_owner=True)
        before = (skills.local(REL), await skills.index.get(RAPPEL[0]), list(skills.index.entries))

        with pytest.raises(SkillLocked) as raised:
            await skills.index.update(RAPPEL[0], content="Réécrit par Maggie.", user_id="u")

        assert raised.value.name == RAPPEL[0]
        assert (skills.local(REL), await skills.index.get(RAPPEL[0]), list(skills.index.entries)) == before

    async def test_maggie_cannot_replace_a_locked_skill_by_creating_one_of_the_same_name(self, skills):  # noqa: F811
        await skills.index.create(*RAPPEL, user_id="u", by_owner=True)
        before = (skills.local(REL), await skills.index.get(RAPPEL[0]), list(skills.index.entries))

        with pytest.raises(SkillLocked):
            await skills.index.create(RAPPEL[0], "Autre", [], "Remplacement.", user_id="u")

        assert (skills.local(REL), await skills.index.get(RAPPEL[0]), list(skills.index.entries)) == before


class TestTheLockLivesInTheFile:
    async def test_the_lock_reaches_the_bucket_and_survives_a_rebuild_of_the_table(self, skills):  # noqa: F811
        await skills.index.create(*RAPPEL, user_id="u", by_owner=True)
        await skills.files.sync_user(SCOPE)

        assert "verrouillee: true" in skills.remote(REL)

        await skills.files.rebuild_table()

        assert _locked(skills)
        with pytest.raises(SkillLocked):
            await skills.index.update(RAPPEL[0], content="Non.", user_id="u")

    async def test_a_lock_written_by_hand_in_the_bucket_is_honoured(self, skills):  # noqa: F811
        skills.bucket.seed(skills.key(REL), render_markdown(*RAPPEL, locked=True))
        await skills.files.sync_user(SCOPE)

        assert parse_markdown(skills.local(REL))[0].locked
        with pytest.raises(SkillLocked):
            await skills.index.update(RAPPEL[0], content="Non.", user_id="u")
