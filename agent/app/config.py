from pydantic import computed_field, field_validator
from pydantic_settings import BaseSettings

# Where a model comes from. Validated below rather than compared at the call
# site, because an unrecognised value has to fail whether or not a key is set:
# with one, `LLM_PROVIDER=Fake` reaches the real API; without one, the agent
# answers "the AI service is not configured" to every journey step and names
# nothing.
LLM_PROVIDERS = ("anthropic", "fake")
MEMORY_PROVIDERS = ("s3", "fake")


class Settings(BaseSettings):
    # LLM
    anthropic_api_key: str = ""
    anthropic_model: str = "claude-sonnet-5-5"
    # The cheap model for the calls nobody reads: routing a message to a context,
    # summarizing a thread. Named here rather than written at each call site because
    # `metrics.PRICING` is keyed on it — a rename has to move in one place.
    anthropic_fast_model: str = "claude-haiku-4-5-20251001"
    # The heavy model a sub-agent may ask for with `model: opus` (MAG-3).
    anthropic_opus_model: str = "claude-opus-5-5"
    # "anthropic" talks to the real model. "fake" answers from the scenario
    # files in agent/fixtures/fake-llm/ instead, through the same tool loop and
    # the same streaming gateway — what the e2e stack runs, so a journey that
    # talks to Maggie is fast, free and deterministic (MAG-95).
    llm_provider: str = "anthropic"
    # Empty means agent/fixtures/fake-llm/. Only the tests move it.
    fake_llm_fixtures_dir: str = ""
    openai_api_key: str = ""
    # Whisper lives behind the OpenAI client, so pointing that client
    # elsewhere is all the e2e stack needs to stop calling out. Empty means
    # the real API (MAG-94).
    openai_base_url: str = ""

    # Speech synthesis (ADR-014). "elevenlabs" speaks with the voice the user picked — Claire by
    # default — and falls back on Edge for a sentence when ElevenLabs fails; "edge" forces Edge.
    # Edge reaches Microsoft over a WebSocket the library opens itself, which no base URL can
    # redirect — so the e2e stack switches provider instead, to "fake": a fixed silent clip.
    tts_provider: str = "elevenlabs"
    # Empty means no ElevenLabs account configured: every sentence goes to Edge.
    elevenlabs_api_key: str = ""
    elevenlabs_base_url: str = "https://api.elevenlabs.io"
    # v4 for a voice message (quality), v4 Turbo for the live voice (latency).
    elevenlabs_model_file: str = "eleven_v4"
    elevenlabs_model_live: str = "eleven_v4_turbo"
    # How long ElevenLabs may take to start answering before the sentence goes to Edge.
    elevenlabs_timeout_seconds: float = 8.0

    # Token of the e2e-only surface (`app/e2e.py`), the same `E2E_LOGIN_TOKEN` the API's
    # test login takes. Empty keeps it closed even under TTS_PROVIDER=fake.
    e2e_login_token: str = ""

    # Error tracking (GlitchTip, Sentry-compatible). Empty DSN: nothing is initialised and
    # nothing leaves the process — dev, tests and e2e. The release is the deployed commit,
    # baked into the prod image (SENTRY_RELEASE build-arg).
    sentry_dsn: str = ""
    sentry_release: str = ""
    sentry_environment: str = "prod"

    # Anthropic's server-side web search, given to Maggie and to the `researcher` (MAG-384).
    # The most searches one request may run; 0 switches the web off. The country is a hint
    # for local questions, and the user's own timezone is added to it per request.
    web_search_max_uses: int = 5
    web_search_country: str = "FR"

    # MCP Server
    mcp_server_url: str = "http://nginx/_mcp"

    # Mercure
    mercure_url: str = "http://mercure/.well-known/mercure"
    mercure_public_url: str = "http://maggie.local/.well-known/mercure"
    mercure_jwt_secret: str = "!ChangeThisMercureHubJWTSecretKey!"

    # Agent database — all agent-owned tables (messages, proactions, memory, etc.)
    agent_database_url: str = "postgresql://maggie:maggie@database:5432/maggie_agent"

    # RabbitMQ
    rabbitmq_url: str = "amqp://guest:guest@rabbitmq:5672/"

    # Auth
    service_token: str = ""
    # Bearer token A2A peers must present. Empty keeps /a2a closed: the route is
    # reachable from the internet through the ingress, so it never opens by default.
    a2a_token: str = ""
    jwt_public_key_path: str = "/etc/jwt/public.pem"
    # Clock skew tolerated on `iat`/`exp`. Zero in production; the e2e stack sets it because its
    # simulated clock ticks per process, so the API's drifts ahead of the agent's (MAG-234).
    jwt_leeway_seconds: int = 0

    # Daily planning: runs at this local hour, early enough to schedule a 7:00 directive,
    # in each user's own timezone (MAG-165). This one is the fallback when it is unknown or invalid.
    planning_timezone: str = "Europe/Paris"
    daily_planning_hour: int = 5

    # Agent
    agent_name: str = "Maggie"
    agent_base_url: str = "http://maggie.local/agent"
    # What the model is sent of the conversation (MAG-13). Two windows, because they
    # answer two different needs: the thread the current message was routed into is the
    # conversation itself, and the short global window is there so a reference to what was
    # just said in a neighbouring thread is not lost. The other open threads reach the
    # model as their summary, in the system prompt, never as raw messages.
    context_history_messages: int = 40
    recent_history_messages: int = 8
    # How long a message left without an answer (the agent restarted mid-turn) is still
    # answered when the agent comes back. Past it, the request is stale — « rappelle-moi
    # dans 1 min » said hours ago — and is marked unanswered instead (MAG-344).
    turn_resume_minutes: int = 5
    # How many of the thread's turns get their `tool_use` / `tool_result` blocks replayed
    # on top of their text (MAG-211). A tool result is bulky — a month of transactions is
    # not a sentence — so replaying all forty turns of a thread would cost more than the
    # thread itself: beyond this window a turn keeps its text and loses its blocks. Two
    # covers « what you just read » and the turn before it, which is what a follow-up
    # question is about. Zero turns the replay off.
    #
    # The ceiling this buys is per result, not per turn: a turn can hold up to five rounds
    # of tools (the loop's `max_iterations`), each of several calls, and
    # `tool_blocks.TOOL_RESULT_MAX_CHARS` caps one result at 8 000 characters. So raising
    # this is not linear in cost — a thread of heavy turns is where it is felt.
    tool_replay_turns: int = 2
    # How many messages a conversation context has to gain before its summary is
    # rewritten (MAG-11). Low enough that a thread is summarized within a sitting,
    # high enough that a Haiku call is not made on every other message.
    context_summary_every_messages: int = 10
    # Life of a conversation context (MAG-12): quiet this many hours and it goes dormant,
    # quiet this many days and it is closed. Both count from the last message routed into it.
    context_dormant_after_hours: int = 24
    context_close_after_days: int = 14
    context_lifecycle_interval_seconds: int = 600
    # The style synthesis (MAG-23): how often the owner's thumbs are read back, and how many new
    # ones it takes before the fast model is asked for a directive. One thumb is a mood, not a
    # style — below the threshold nothing is proposed and nothing is spent.
    style_synthesis_interval_hours: int = 24
    style_synthesis_min_feedback: int = 3
    # Encoding along the way (MAG-15): a conversation that has been silent this long gets its carte
    # and its obvious fiches, written by the fast model in the background. Its own clock — not the
    # dormancy above, which waits a day.
    memory_encode_after_seconds: int = 300
    memory_encode_interval_seconds: int = 60

    # Memory folder (MAG-195): Markdown files, the only truth, kept in an S3-compatible bucket with
    # versioning on and worked on through a local copy. Provisioned by the owner (MAG-225); nothing here
    # names a provider, so the bucket stays replaceable. "s3" with an empty bucket = memory off.
    # "fake" keeps the bucket in the agent's memory, able to fail on demand: dev, unit tests, e2e stack.
    memory_provider: str = "s3"
    memory_endpoint: str = ""
    memory_region: str = ""
    memory_bucket: str = ""
    memory_key_id: str = ""
    memory_application_key: str = ""
    # True only once the endpoint is known to honour If-Match / If-None-Match: a provider that ignores
    # the headers would overwrite the owner's edit in silence, so the default compares the ETag first.
    memory_conditional_writes: bool = False
    memory_timeout_seconds: float = 10.0
    # The local copy the agent works on. No persistent volume: it is re-hydrated from the bucket at boot.
    memory_local_dir: str = "/tmp/maggie-memory"
    # Background synchronisation, independent of RabbitMQ; a failing bucket is retried with backoff.
    memory_sync_interval_seconds: int = 60
    memory_sync_max_backoff_seconds: int = 300
    # A turn syncs first only if the last pass is older than this, and waits for it at most `timeout`.
    memory_turn_sync_max_age_seconds: int = 30
    memory_turn_sync_timeout_seconds: float = 2.0
    # Recall (MAG-16). Level 2 runs on the path of every answer, voice included: past the delay the turn
    # goes on without it. A carte must score at least the threshold to come back, at most `max` of them.
    memory_recall_timeout_seconds: float = 0.8
    memory_recall_max: int = 3
    memory_recall_threshold: float = 2.5
    # Level 1: the facts of the permanent profile, ranked by use, always in the prompt.
    memory_profile_max: int = 80
    # The night (MAG-159): once a day, at this hour of each owner's own clock, Maggie goes through the
    # day. Bounded: a pass makes at most `max_calls` model calls (the rest waits for the next night), and
    # one scheduler tick spends at most `tick_calls` of them, so a pass cut off resumes where it stopped.
    memory_night_hour: int = 3
    memory_night_interval_seconds: int = 600
    memory_night_max_calls: int = 200
    memory_night_tick_calls: int = 40
    # A regularity needs at least this many different conversations to prove it.
    memory_night_min_proofs: int = 2
    # A fiche whose last claim is older than this is looked at for forgetting (age, use and links shown).
    memory_night_forget_after_days: int = 21
    # The habits read in the app's data (MAG-18): once a week, the night reads the agenda, the errands, the
    # meals and the transactions of this many past days (and the agenda of this many days ahead) and writes what
    # they prove. A habit needs this many data points, on different days.
    memory_habits_past_days: int = 84
    memory_habits_future_days: int = 90
    memory_habits_min_proofs: int = 3

    @field_validator("llm_provider")
    @classmethod
    def _known_provider(cls, value: str) -> str:
        if value not in LLM_PROVIDERS:
            raise ValueError(f"LLM_PROVIDER must be one of {LLM_PROVIDERS}, not {value!r}")
        return value

    @field_validator("memory_provider")
    @classmethod
    def _known_memory_provider(cls, value: str) -> str:
        if value not in MEMORY_PROVIDERS:
            raise ValueError(f"MEMORY_PROVIDER must be one of {MEMORY_PROVIDERS}, not {value!r}")
        return value

    @property
    def memory_enabled(self) -> bool:
        return self.memory_provider == "fake" or bool(self.memory_bucket)

    @property
    def model_aliases(self) -> dict[str, str]:
        """What `model:` may say in a sub-agent file (agent/data/agents/*.md) and the model each name stands for."""
        return {
            "haiku": self.anthropic_fast_model,
            "sonnet": self.anthropic_model,
            "opus": self.anthropic_opus_model,
        }

    @computed_field
    @property
    def async_agent_database_url(self) -> str:
        """Convert agent database URL to asyncpg driver URL."""
        url = self.agent_database_url.split("?")[0]
        return url.replace("postgresql://", "postgresql+asyncpg://", 1)

    model_config = {"env_prefix": "", "case_sensitive": False}


settings = Settings()
