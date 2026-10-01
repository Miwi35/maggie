
import httpx
import jwt
import respx

from app.mercure.publisher import MercurePublisher


class TestMercurePublisher:
    AUDIENCE = "https://maggie.test/.well-known/mercure"

    def _make_publisher(self, hub_url: str = "http://mercure/.well-known/mercure", secret: str = "test-secret") -> MercurePublisher:
        """Create a MercurePublisher with overridden settings."""
        publisher = MercurePublisher.__new__(MercurePublisher)
        publisher.hub_url = hub_url
        publisher.jwt_secret = secret
        publisher.audience = self.AUDIENCE
        return publisher

    def _decode(self, token: str, secret: str) -> dict:
        return jwt.decode(token, secret, algorithms=["HS256"], audience=self.AUDIENCE)

    def test_generate_jwt_is_an_oauth_access_token_granting_publish_on_the_topics(self):
        """A Mercure 1.0 hub wants typ at+jwt, a trusted iss, its own aud and authorization_details."""
        publisher = self._make_publisher(secret="my-test-secret")

        token = publisher._generate_jwt(["/chat/user1", "/chat/user2"])
        decoded = self._decode(token, "my-test-secret")

        assert jwt.get_unverified_header(token)["typ"] == "at+jwt"
        assert decoded["iss"] == "maggie"
        assert decoded["exp"] > decoded["iat"]
        assert "mercure" not in decoded
        assert decoded["authorization_details"] == [
            {
                "type": "https://mercure.rocks/authorization-detail",
                "actions": ["publish"],
                "topics": [{"match": "/chat/user1"}, {"match": "/chat/user2"}],
            }
        ]

    @respx.mock
    async def test_publish_sends_post_to_hub(self):
        """publish() should POST to the Mercure hub with correct topic, data, and auth header."""
        hub_url = "http://mercure/.well-known/mercure"
        secret = "my-test-secret"
        publisher = self._make_publisher(hub_url=hub_url, secret=secret)

        route = respx.post(hub_url).mock(return_value=httpx.Response(200))

        await publisher.publish(
            topic="/agent/chat/user1",
            data={"response": "Hello!", "tool_calls": []},
        )

        assert route.called
        request = route.calls.last.request

        # Verify Authorization header contains a Bearer token
        auth_header = request.headers["authorization"]
        assert auth_header.startswith("Bearer ")
        token = auth_header.split(" ", 1)[1]

        # Decode and verify the JWT payload
        decoded = self._decode(token, secret)
        assert decoded["authorization_details"][0]["topics"] == [{"match": "/agent/chat/user1"}]

        # Verify the POST body contains topic and data
        body = request.content.decode()
        assert "topic=%2Fagent%2Fchat%2Fuser1" in body
        # The data field should contain JSON-encoded dict
        assert "data=" in body
        # Private: the hub delivers only to tokens whose subscribe grant names the topic
        assert "private=on" in body
