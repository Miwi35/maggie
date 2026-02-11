from collections import defaultdict


class ConversationMemory:
    """In-memory conversation history per user. Keeps the last N messages."""

    def __init__(self, max_messages: int = 50):
        self.max_messages = max_messages
        self._histories: dict[str, list[dict]] = defaultdict(list)

    def add_message(self, user_id: str, role: str, content: str) -> None:
        self._histories[user_id].append({"role": role, "content": content})
        # Trim to max
        if len(self._histories[user_id]) > self.max_messages:
            self._histories[user_id] = self._histories[user_id][-self.max_messages :]

    def get_history(self, user_id: str) -> list[dict]:
        return list(self._histories[user_id])

    def clear(self, user_id: str) -> None:
        self._histories[user_id] = []
