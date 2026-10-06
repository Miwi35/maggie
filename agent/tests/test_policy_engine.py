"""The tool policy decides what Maggie does alone and what she proposes (MAG-4).

Everything here is about one function, `PolicyEngine.evaluate`, because it is the only
thing standing between a model that has decided to delete something and the deletion.
"""

import pytest

from app.policy.engine import POLICY_FILE, Mode, PolicyEngine, PolicyError, policy_engine


def engine(**policy) -> PolicyEngine:
    return PolicyEngine.from_mapping(policy)


class TestRuleOrder:
    """The first matching rule wins, which is what makes an exception expressible."""

    POLICY = {
        "default": "allow",
        "rules": [
            {"tools": ["delete_memory"], "mode": "allow"},
            {"tools": ["delete_*"], "mode": "ask"},
        ],
    }

    def test_the_first_matching_rule_wins_over_a_later_broader_one(self):
        assert engine(**self.POLICY).evaluate("delete_memory", {}, "chat") is Mode.ALLOW

    def test_the_broad_rule_still_catches_what_the_exception_does_not_name(self):
        assert engine(**self.POLICY).evaluate("delete_event", {}, "chat") is Mode.ASK

    def test_a_tool_no_rule_matches_falls_back_to_the_default(self):
        assert engine(**self.POLICY).evaluate("create_event", {}, "chat") is Mode.ALLOW

    def test_the_default_is_what_the_file_says_not_allow(self):
        assert engine(default="deny", rules=[]).evaluate("create_event", {}, "chat") is Mode.DENY

    def test_a_file_with_no_default_allows(self):
        assert engine(rules=[]).evaluate("create_event", {}, "chat") is Mode.ALLOW

    def test_patterns_are_matched_case_sensitively(self):
        assert engine(**self.POLICY).evaluate("DELETE_EVENT", {}, "chat") is Mode.ALLOW


class TestWhenOnArguments:
    """`manage_*` is one tool with a read action and a destructive one; only the second waits."""

    POLICY = {
        "default": "allow",
        "rules": [{"tools": ["manage_*"], "when": {"action": ["delete"]}, "mode": "ask"}],
    }

    def test_the_named_action_is_held_back(self):
        assert engine(**self.POLICY).evaluate("manage_meals", {"action": "delete"}, "chat") is Mode.ASK

    def test_another_action_of_the_same_tool_goes_through(self):
        assert engine(**self.POLICY).evaluate("manage_meals", {"action": "list"}, "chat") is Mode.ALLOW

    def test_the_argument_is_matched_whatever_its_case(self):
        assert engine(**self.POLICY).evaluate("manage_meals", {"action": "DELETE"}, "chat") is Mode.ASK

    def test_a_call_missing_the_argument_matches_nothing(self):
        # A `manage_meals` with no action is a malformed call, not a deletion to catch.
        assert engine(**self.POLICY).evaluate("manage_meals", {}, "chat") is Mode.ALLOW

    def test_no_arguments_at_all_is_not_a_crash(self):
        assert engine(**self.POLICY).evaluate("manage_meals", None, "chat") is Mode.ALLOW

    def test_every_named_argument_has_to_match(self):
        policy = engine(
            default="allow",
            rules=[{"tools": ["manage_*"], "when": {"action": ["delete"], "slot": ["dinner"]}, "mode": "ask"}],
        )
        assert policy.evaluate("manage_meals", {"action": "delete", "slot": "dinner"}, "chat") is Mode.ASK
        assert policy.evaluate("manage_meals", {"action": "delete", "slot": "lunch"}, "chat") is Mode.ALLOW


class TestSourceFilter:
    """`sources` narrows a rule to who asked."""

    POLICY = {
        "default": "allow",
        "rules": [{"tools": ["delete_*"], "sources": ["proaction"], "mode": "ask"}],
    }

    def test_a_listed_source_is_caught(self):
        assert engine(**self.POLICY).evaluate("delete_event", {}, "proaction") is Mode.ASK

    def test_an_unlisted_source_falls_through_to_the_next_rule(self):
        assert engine(**self.POLICY).evaluate("delete_event", {}, "chat") is Mode.ALLOW

    def test_a_rule_without_sources_applies_to_every_source(self):
        policy = engine(default="allow", rules=[{"tools": ["delete_*"], "mode": "ask"}])
        for source in ("chat", "chat_stream", "proaction", "subagent:researcher"):
            assert policy.evaluate("delete_event", {}, source) is Mode.ASK


class TestTheTwoSpecialSources:
    POLICY = {
        "default": "allow",
        "rules": [
            {"tools": ["delete_*"], "mode": "ask"},
            {"tools": ["drop_database"], "mode": "deny"},
        ],
    }

    def test_approval_always_allows_because_it_is_the_users_own_answer(self):
        assert engine(**self.POLICY).evaluate("delete_event", {}, "approval") is Mode.ALLOW

    def test_a2a_turns_ask_into_deny_because_nobody_is_there_to_answer(self):
        assert engine(**self.POLICY).evaluate("delete_event", {}, "a2a") is Mode.DENY

    def test_a2a_leaves_an_allowed_tool_alone(self):
        assert engine(**self.POLICY).evaluate("search_recipes", {}, "a2a") is Mode.ALLOW

    def test_a2a_leaves_a_denied_tool_denied(self):
        assert engine(**self.POLICY).evaluate("drop_database", {}, "a2a") is Mode.DENY

    def test_a_default_of_ask_is_a_deny_over_a2a(self):
        assert engine(default="ask", rules=[]).evaluate("create_event", {}, "a2a") is Mode.DENY


class TestMalformedPolicy:
    """A policy that does not say what it means must fail loudly, never read as « allow everything »."""

    @pytest.mark.parametrize(
        "policy",
        [
            {"default": "maybe", "rules": []},
            {"default": "allow", "rules": [{"tools": ["delete_*"], "mode": "sometimes"}]},
            {"default": "allow", "rules": [{"tools": ["delete_*"]}]},
            {"default": "allow", "rules": [{"mode": "ask"}]},
            {"default": "allow", "rules": [{"tools": [], "mode": "ask"}]},
            {"default": "allow", "rules": [{"tools": "delete_*", "mode": "ask"}]},
            {"default": "allow", "rules": [{"tools": ["manage_*"], "mode": "ask", "when": ["delete"]}]},
            {"default": "allow", "rules": [{"tools": ["manage_*"], "mode": "ask", "when": {"action": "delete"}}]},
            {"default": "allow", "rules": [{"tools": ["delete_*"], "mode": "ask", "sources": "chat"}]},
            {"default": "allow", "rules": ["delete_*"]},
            {"default": "allow", "rules": "none"},
        ],
    )
    def test_it_is_refused(self, policy):
        with pytest.raises(PolicyError):
            PolicyEngine.from_mapping(policy)

    def test_a_file_that_is_not_a_mapping_is_refused(self):
        with pytest.raises(PolicyError):
            PolicyEngine.from_mapping(["allow"])

    def test_a_missing_file_raises_rather_than_allowing_everything(self, tmp_path):
        with pytest.raises(OSError):
            PolicyEngine.from_file(tmp_path / "nope.yaml")


class TestTheShippedPolicy:
    """What `data/policy.yaml` actually decides, read through the singleton the router uses."""

    def test_the_singleton_is_loaded_from_the_file_in_the_repository(self):
        assert POLICY_FILE.is_file()
        assert policy_engine.rules, "The shipped policy has no rule, so nothing is ever proposed."

    def test_maggie_corrects_her_own_notes_without_asking(self):
        assert policy_engine.evaluate("delete_memory", {}, "chat") is Mode.ALLOW
        assert policy_engine.evaluate("delete_instruction", {}, "chat") is Mode.ALLOW
        assert policy_engine.evaluate("update_memory", {}, "chat") is Mode.ALLOW

    @pytest.mark.parametrize("tool", ["delete_event", "delete_recipe", "delete_task", "delete_skill"])
    def test_deleting_something_the_user_made_is_proposed(self, tool):
        assert policy_engine.evaluate(tool, {}, "chat") is Mode.ASK

    def test_a_manage_tool_only_waits_on_its_destructive_action(self):
        assert policy_engine.evaluate("manage_meals", {"action": "delete"}, "chat") is Mode.ASK
        assert policy_engine.evaluate("manage_meals", {"action": "list"}, "chat") is Mode.ALLOW

    @pytest.mark.parametrize(
        "tool",
        ["search_recipes", "get_upcoming_events", "create_event", "update_event", "store_memory", "get_skill"],
    )
    def test_everything_else_is_still_hers_to_do(self, tool):
        # The policy exists to hold back a handful of calls; a file that quietly turned
        # the whole agent into a confirmation prompt would be the worse failure.
        assert policy_engine.evaluate(tool, {}, "chat") is Mode.ALLOW
