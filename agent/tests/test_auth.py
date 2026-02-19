from unittest.mock import patch

import jwt
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from fastapi import Depends, FastAPI
from fastapi.testclient import TestClient

from app.auth import get_current_user_id

# Generate a test RSA key pair
_private_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
_public_key_pem = _private_key.public_key().public_bytes(
    serialization.Encoding.PEM,
    serialization.PublicFormat.SubjectPublicKeyInfo,
).decode()
_private_key_pem = _private_key.private_bytes(
    serialization.Encoding.PEM,
    serialization.PrivateFormat.TraditionalOpenSSL,
    serialization.NoEncryption(),
).decode()


def _build_test_app():
    """Build a minimal FastAPI app with a protected endpoint."""
    app = FastAPI()

    @app.get("/protected")
    def protected(user_id: str = Depends(get_current_user_id)):
        return {"user_id": user_id}

    return app


class TestAuth:
    @patch("app.auth._get_public_key", return_value=_public_key_pem)
    def test_valid_token(self, _mock_key):
        """Valid RS256 JWT should return user_id from 'sub' claim."""
        app = _build_test_app()
        client = TestClient(app)

        token = jwt.encode({"sub": "user-123"}, _private_key_pem, algorithm="RS256")
        response = client.get("/protected", headers={"Authorization": f"Bearer {token}"})

        assert response.status_code == 200
        assert response.json()["user_id"] == "user-123"

    @patch("app.auth._get_public_key", return_value=_public_key_pem)
    def test_expired_token(self, _mock_key):
        """Expired JWT should return 401."""
        import time

        app = _build_test_app()
        client = TestClient(app)

        token = jwt.encode(
            {"sub": "user-123", "exp": int(time.time()) - 3600},
            _private_key_pem,
            algorithm="RS256",
        )
        response = client.get("/protected", headers={"Authorization": f"Bearer {token}"})

        assert response.status_code == 401
        assert "expired" in response.json()["detail"].lower()

    @patch("app.auth._get_public_key", return_value=_public_key_pem)
    def test_invalid_token(self, _mock_key):
        """Invalid token should return 401."""
        app = _build_test_app()
        client = TestClient(app)

        response = client.get("/protected", headers={"Authorization": "Bearer garbage"})

        assert response.status_code == 401

    def test_missing_token(self):
        """Missing Authorization header should return 401 or 403."""
        app = _build_test_app()
        client = TestClient(app)

        response = client.get("/protected")

        assert response.status_code in (401, 403)

    @patch("app.auth._get_public_key", return_value=_public_key_pem)
    def test_token_without_sub(self, _mock_key):
        """Token without 'sub' claim should return 401."""
        app = _build_test_app()
        client = TestClient(app)

        token = jwt.encode({"role": "admin"}, _private_key_pem, algorithm="RS256")
        response = client.get("/protected", headers={"Authorization": f"Bearer {token}"})

        assert response.status_code == 401
        assert "sub" in response.json()["detail"].lower()
