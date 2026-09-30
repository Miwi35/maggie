
import httpx
import jwt
import respx

from app.mercure.publisher import MercurePublisher


class TestMercurePublisher:
    def _make_publisher(self, hub_url: str = "http://mercure/.well-known/mercure", secret: str = "test-secret") -> MercurePublisher:
        """Create a MercurePublisher with overridden settings."""
        publisher = MercurePublisher.__new__(MercurePublisher)
        publisher.hub_url = hub_url
        publisher.jwt_secret = secret
        return publisher

    def test_generate_jwt_contains_publish_claim(self):
        """The JWT should contain the mercure.publish claim with the given topics."""
        publisher = self._make_publisher(secret="my-test-secret")

        token = publisher._generate_jwt(["/chat/user1", "/chat/user2"])
        decoded = jwt.decode(token, "my-test-secret", algorithms=["HS256"])

        assert "mercure" in decoded
        assert "publish" in decoded["mercure"]
        assert decoded["mercure"]["publish"] == ["/chat/user1", "/chat/user2"]

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
        decoded = jwt.decode(token, secret, algorithms=["HS256"])
        assert decoded["mercure"]["publish"] == ["/agent/chat/user1"]

        # Verify the POST body contains topic and data
        body = request.content.decode()
        assert "topic=%2Fagent%2Fchat%2Fuser1" in body
        # The data field should contain JSON-encoded dict
        assert "data=" in body
        # Private: the hub delivers only to tokens whose subscribe claim names the topic
        assert "private=on" in body
