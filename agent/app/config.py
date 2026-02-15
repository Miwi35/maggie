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

    # Database (Doctrine-style URL from .env, converted to asyncpg)
    database_url: str = "postgresql://maggie:maggie@database:5432/maggie"

    # Auth
    service_token: str = ""

    # Agent
    agent_name: str = "Maggie"
    max_conversation_history: int = 50

    @computed_field
    @property
    def async_database_url(self) -> str:
        """Convert Doctrine-style postgresql:// to asyncpg driver URL."""
        url = self.database_url.split("?")[0]  # strip query params like ?serverVersion=
        return url.replace("postgresql://", "postgresql+asyncpg://", 1)

    model_config = {"env_prefix": "", "case_sensitive": False}


settings = Settings()
