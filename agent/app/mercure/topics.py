"""The one place that spells an agent Mercure topic.

The agent keys its topics by the API user's ULID — the `sub` claim of the JWT,
see `app.auth.get_current_user_id` — as `/{stream}/{userId}`. They sit outside
`/users/{id}/`, so `MercureSubscriberTokenFactory` lists them as selectors.
`agent/contract/mercure-topics.json` is written from `SUBSCRIPTION_PATTERNS` by
`tests/test_mercure_topics_contract.py`; the admin and mobile suites read it.
"""

CHAT = "chat"
CONTEXTS = "contexts"
PROACTIONS = "proactions"
INSTRUCTIONS = "instructions"
SKILLS = "skills"

STREAMS = (CHAT, CONTEXTS, PROACTIONS, INSTRUCTIONS, SKILLS)

SUBSCRIPTION_PATTERNS = {stream: f"/{stream}/{{userId}}" for stream in STREAMS}


def for_user(stream: str, user_id: str) -> str:
    """The topic published for one user: /chat/01HXYZ."""
    if stream not in STREAMS:
        raise ValueError(f"Unknown agent Mercure stream: {stream}")
    return f"/{stream}/{user_id}"
