"""The tool policy: what Maggie does on her own, what she proposes first (MAG-4).

First brick of the Act / Propose / Silent trust system. `data/policy.yaml` gives
each tool call a mode — `allow`, `ask` or `deny` — and `ToolRouter.call_tool`
reads it before routing anything. `ask` is « Propose, never impose »: the call is
persisted as a pending action and the user answers it from the web or the phone.

The first matching rule wins, so a file reads as exceptions first, broad patterns
after. Patterns are `fnmatch` globs on the tool name, case-sensitive.
"""

import enum
import fnmatch
import logging
from dataclasses import dataclass, field
from pathlib import Path

import yaml

logger = logging.getLogger(__name__)

# `source` values call_tool is given. The two the policy itself reacts to:
APPROVAL_SOURCE = "approval"  # the user has already answered — the call *is* the answer
A2A_SOURCE = "a2a"  # a peer agent: nobody is there to answer, so `ask` cannot be waited on

# Relative to the module, which makes it the same path in the container
# (`/app/data/policy.yaml`, the agent directory being copied to /app) and in a
# checkout running the test suite.
POLICY_FILE = Path(__file__).resolve().parents[2] / "data" / "policy.yaml"


class Mode(enum.StrEnum):
    ALLOW = "allow"
    ASK = "ask"
    DENY = "deny"


class PolicyError(ValueError):
    """The policy file does not say what it is supposed to say."""


def _patterns(raw: object, where: str) -> tuple[str, ...]:
    if not isinstance(raw, list) or not raw or not all(isinstance(item, str) for item in raw):
        raise PolicyError(f"{where} must be a non-empty list of strings, got {raw!r}")
    return tuple(raw)


def _mode(raw: object, where: str) -> Mode:
    try:
        return Mode(raw)
    except ValueError as e:
        raise PolicyError(f"{where} must be one of {[m.value for m in Mode]}, got {raw!r}") from e


@dataclass(frozen=True)
class Rule:
    """One line of the policy: these tools, under these conditions, get this mode."""

    tools: tuple[str, ...]
    mode: Mode
    sources: tuple[str, ...] = ()
    when: dict[str, tuple[str, ...]] = field(default_factory=dict)

    @classmethod
    def parse(cls, raw: object, index: int) -> "Rule":
        where = f"rules[{index}]"
        if not isinstance(raw, dict):
            raise PolicyError(f"{where} must be a mapping, got {raw!r}")

        when_raw = raw.get("when") or {}
        if not isinstance(when_raw, dict):
            raise PolicyError(f"{where}.when must be a mapping of argument to accepted values, got {when_raw!r}")

        return cls(
            tools=_patterns(raw.get("tools"), f"{where}.tools"),
            mode=_mode(raw.get("mode"), f"{where}.mode"),
            sources=tuple(_patterns(raw["sources"], f"{where}.sources")) if raw.get("sources") is not None else (),
            when={
                str(key): tuple(value.lower() for value in _patterns(values, f"{where}.when.{key}"))
                for key, values in when_raw.items()
            },
        )

    def matches(self, tool_name: str, arguments: dict, source: str) -> bool:
        if not any(fnmatch.fnmatchcase(tool_name, pattern) for pattern in self.tools):
            return False
        if self.sources and source not in self.sources:
            return False
        # An argument the call does not carry never matches: `manage_meals` with no
        # `action` is a malformed call, not a deletion to hold back.
        return all(str(arguments.get(key, "")).lower() in accepted for key, accepted in self.when.items())


@dataclass
class PolicyEngine:
    """The rules of `data/policy.yaml`, in the order they are written."""

    default: Mode = Mode.ALLOW
    rules: tuple[Rule, ...] = ()

    @classmethod
    def from_mapping(cls, raw: object) -> "PolicyEngine":
        if not isinstance(raw, dict):
            raise PolicyError(f"a policy must be a mapping with `default` and `rules`, got {raw!r}")
        rules_raw = raw.get("rules") or []
        if not isinstance(rules_raw, list):
            raise PolicyError(f"`rules` must be a list, got {rules_raw!r}")
        return cls(
            default=_mode(raw.get("default", Mode.ALLOW.value), "default"),
            rules=tuple(Rule.parse(rule, index) for index, rule in enumerate(rules_raw)),
        )

    @classmethod
    def from_file(cls, path: Path = POLICY_FILE) -> "PolicyEngine":
        """Load the policy, letting every error through.

        A policy that cannot be read must not look like « allow everything »: the file
        is committed next to the code, so an unreadable one is a broken deploy, and the
        agent refusing to start says so where a silent fallback would not.
        """
        engine = cls.from_mapping(yaml.safe_load(path.read_text(encoding="utf-8")))
        logger.info(f"Tool policy loaded from {path}: default={engine.default.value}, {len(engine.rules)} rule(s)")
        return engine

    def evaluate(self, tool_name: str, arguments: dict | None, source: str) -> Mode:
        """The mode of one call. The first matching rule wins, otherwise `default`."""
        if source == APPROVAL_SOURCE:
            return Mode.ALLOW

        arguments = arguments or {}
        mode = self.default
        for rule in self.rules:
            if rule.matches(tool_name, arguments, source):
                mode = rule.mode
                break

        if mode is Mode.ASK and source == A2A_SOURCE:
            return Mode.DENY
        return mode


policy_engine = PolicyEngine.from_file()
