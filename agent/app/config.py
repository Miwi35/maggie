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
    jwt_public_key_path: str = "/etc/jwt/public.pem"

    # Agent
    agent_name: str = "Maggie"
    agent_base_url: str = "http://maggie.local/agent"
    max_conversation_history: int = 50

    @field_validator("llm_provider")
    @classmethod
    def _known_provider(cls, value: str) -> str:
        if value not in LLM_PROVIDERS:
            raise ValueError(f"LLM_PROVIDER must be one of {LLM_PROVIDERS}, not {value!r}")
        return value

    @computed_field
    @property
    def async_agent_database_url(self) -> str:
        """Convert agent database URL to asyncpg driver URL."""
        url = self.agent_database_url.split("?")[0]
        return url.replace("postgresql://", "postgresql+asyncpg://", 1)

    model_config = {"env_prefix": "", "case_sensitive": False}


settings = Settings()
