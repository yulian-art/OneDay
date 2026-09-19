from typing import Literal

from pydantic import Field, SecretStr, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="ONEDAY_", env_file=".env", extra="ignore")
    database_url: str = "sqlite:///./oneday.db"
    environment: Literal["development", "production"] = "development"
    dev_login: bool = True
    jwt_secret: SecretStr = SecretStr("local-development-only-change-before-deploying")
    token_ttl_seconds: int = Field(86400, ge=60, le=604800)
    sync_max_error_ms: float = Field(200, ge=0, le=5000)
    vlm_provider: Literal["disabled", "openai_compatible"] = "disabled"
    vlm_base_url: str = "https://your-provider.example/v1"
    vlm_model: str = ""
    vlm_api_key: SecretStr = SecretStr("")
    vlm_timeout_seconds: float = Field(30, ge=1, le=120)
    job_max_attempts: int = Field(3, ge=1, le=10)
    job_lease_seconds: int = Field(180, ge=150)

    @model_validator(mode="after")
    def validate_config(self):
        if self.environment == "production":
            if self.dev_login:
                raise ValueError("Device-ID login must be disabled in production")
            secret = self.jwt_secret.get_secret_value()
            if len(secret) < 32 or secret.startswith(("local-development", "replace-with")):
                raise ValueError("A private JWT secret is required in production")
        if self.vlm_provider == "openai_compatible":
            if not self.vlm_api_key.get_secret_value() or not self.vlm_model:
                raise ValueError("VLM model and API key are required")
            if not self.vlm_base_url.startswith("https://"):
                raise ValueError("VLM endpoint must use HTTPS")
        return self
