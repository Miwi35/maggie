"""Error tracking: unhandled errors and a few explicit signals sent to GlitchTip.

GlitchTip speaks the Sentry protocol, so this is the Sentry SDK. With `SENTRY_DSN`
empty nothing is initialised and every function here is a no-op: dev, tests and e2e
send nothing.

Every event carries `component=agent`, which the bridge to Linear reads. The repository
is public and the messages are personal data: no request body, no local variables, no
log records, and the signals carry identifiers and tool names only — never what the user
or the model wrote.
"""

import logging

import sentry_sdk
from sentry_sdk.integrations.logging import LoggingIntegration

from app.config import Settings, settings

logger = logging.getLogger(__name__)

COMPONENT = "agent"

# The signals of a behaviour that went wrong without raising (level `warning`).
CLAIM_GUARD_RETRY = "claim_guard_retry"
MCP_TOOL_ERROR = "mcp_tool_error"
PROACTION_FAILED = "proaction_failed"
UNANSWERED_MESSAGE = "unanswered_message"


def _tag_component(event: dict, _hint: dict) -> dict:
    event.setdefault("tags", {})["component"] = COMPONENT
    return event


def init_error_tracking(config: Settings = settings, **options) -> bool:
    """Start the SDK when a DSN is configured; whether it was started.

    `options` reach `sentry_sdk.init` as they are — the tests pass a transport there.
    """
    if not config.sentry_dsn:
        return False
    sentry_sdk.init(
        dsn=config.sentry_dsn,
        release=config.sentry_release or None,
        environment=config.sentry_environment,
        send_default_pii=False,
        # Message contents travel in request bodies and in the locals of the chat code.
        max_request_body_size="never",
        include_local_variables=False,
        # Log records mention contents and tool results: neither events nor breadcrumbs.
        integrations=[LoggingIntegration(level=None, event_level=None)],
        before_send=_tag_component,
        **options,
    )
    logger.info("Error tracking enabled")
    return True


def _active() -> bool:
    return sentry_sdk.get_client().is_active()


def capture_signal(signal: str, **ids: str | None) -> None:
    """Report a `warning` with the tag `signal=<name>` and identifiers as tags.

    Only identifiers and names belong in `ids`: user and message ids, tool names, error
    types. One issue per signal (and per tool, when there is one) in GlitchTip.
    """
    if not _active():
        return
    with sentry_sdk.new_scope() as scope:
        scope.set_level("warning")
        scope.set_tag("signal", signal)
        for key, value in ids.items():
            if value is not None:
                scope.set_tag(key, str(value))
        scope.fingerprint = ["signal", signal, str(ids.get("tool") or "")]
        sentry_sdk.capture_message(signal, level="warning")


def capture_error(error: BaseException, **ids: str | None) -> None:
    """Report an exception caught on a path no request handler sees (background turns)."""
    if not _active():
        return
    with sentry_sdk.new_scope() as scope:
        for key, value in ids.items():
            if value is not None:
                scope.set_tag(key, str(value))
        sentry_sdk.capture_exception(error)
