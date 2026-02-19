from pydantic import computed_field
from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    # LLM
    anthropic_api_key: str = ""
    anthropic_model: str = "claude-sonnet-4-5-20250929"

    # MCP Server
    mcp_server_url: str = "http://nginx/_mcp"

    # Mercure
    mercure_url: str = "http://mercure/.well-known/mercure"
    mercure_public_url: str = "http://maggie.local/.well-known/mercure"
    mercure_jwt_secret: str = "!ChangeThisMercureHubJWTSecretKey!"

    # Database — shared with Symfony API (messages)
    database_url: str = "postgresql://maggie:maggie@database:5432/maggie"

    # Agent database — agent-owned (proactions)
    agent_database_url: str = "postgresql://maggie:maggie@database:5432/maggie_agent"

    # RabbitMQ
    rabbitmq_url: str = "amqp://guest:guest@rabbitmq:5672/"

    # Auth
    service_token: str = ""
    jwt_public_key_path: str = "/app/jwt/public.pem"

    # Agent
    agent_name: str = "Maggie"
    agent_base_url: str = "http://maggie.local/agent"
    max_conversation_history: int = 50

    @computed_field
    @property
    def async_database_url(self) -> str:
        """Convert Doctrine-style postgresql:// to asyncpg driver URL."""
        url = self.database_url.split("?")[0]  # strip query params like ?serverVersion=
        return url.replace("postgresql://", "postgresql+asyncpg://", 1)

    @computed_field
    @property
    def async_agent_database_url(self) -> str:
        """Convert agent database URL to asyncpg driver URL."""
        url = self.agent_database_url.split("?")[0]
        return url.replace("postgresql://", "postgresql+asyncpg://", 1)

    model_config = {"env_prefix": "", "case_sensitive": False}


settings = Settings()
