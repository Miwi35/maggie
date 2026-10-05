from pydantic import computed_field, field_validator
from pydantic_settings import BaseSettings

# Where a model comes from. Validated below rather than compared at the call
# site, because an unrecognised value has to fail whether or not a key is set:
# with one, `LLM_PROVIDER=Fake` reaches the real API; without one, the agent
# answers "the AI service is not configured" to every journey step and names
# nothing.
LLM_PROVIDERS = ("anthropic", "fake")


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

    # Speech synthesis. "edge" reaches Microsoft over a WebSocket the library
    # opens itself, which no base URL can redirect — so the e2e stack switches
    # provider instead, to one that returns a fixed silent clip.
    tts_provider: str = "edge"

    # Token of the e2e-only surface (`app/e2e.py`), the same `E2E_LOGIN_TOKEN` the API's
    # test login takes. Empty keeps it closed even under TTS_PROVIDER=fake.
    e2e_login_token: str = ""

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
    # How many of the thread's turns get their `tool_use` / `tool_result` blocks replayed
    # on top of their text (MAG-211). A tool result is bulky — a month of transactions is
    # not a sentence — so replaying all forty turns of a thread would cost more than the
    # thread itself: beyond this window a turn keeps its text and loses its blocks. Two
    # covers « what you just read » and the turn before it, which is what a follow-up
    # question is about. Zero turns the replay off.
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

    @field_validator("llm_provider")
    @classmethod
    def _known_provider(cls, value: str) -> str:
        if value not in LLM_PROVIDERS:
            raise ValueError(f"LLM_PROVIDER must be one of {LLM_PROVIDERS}, not {value!r}")
        return value

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
