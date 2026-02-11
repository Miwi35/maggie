from app.memory.conversation import ConversationMemory


class TestConversationMemory:
    def test_add_and_get_messages(self):
        memory = ConversationMemory()

        memory.add_message("user1", "user", "Hello")
        memory.add_message("user1", "assistant", "Hi there!")
        memory.add_message("user1", "user", "How are you?")

        history = memory.get_history("user1")

        assert len(history) == 3
        assert history[0] == {"role": "user", "content": "Hello"}
        assert history[1] == {"role": "assistant", "content": "Hi there!"}
        assert history[2] == {"role": "user", "content": "How are you?"}

    def test_max_messages_trim(self):
        memory = ConversationMemory(max_messages=3)

        memory.add_message("user1", "user", "msg1")
        memory.add_message("user1", "assistant", "msg2")
        memory.add_message("user1", "user", "msg3")
        memory.add_message("user1", "assistant", "msg4")
        memory.add_message("user1", "user", "msg5")

        history = memory.get_history("user1")

        assert len(history) == 3
        assert history[0] == {"role": "user", "content": "msg3"}
        assert history[1] == {"role": "assistant", "content": "msg4"}
        assert history[2] == {"role": "user", "content": "msg5"}

    def test_separate_users(self):
        memory = ConversationMemory()

        memory.add_message("user_a", "user", "Hello from A")
        memory.add_message("user_b", "user", "Hello from B")
        memory.add_message("user_a", "assistant", "Response to A")

        history_a = memory.get_history("user_a")
        history_b = memory.get_history("user_b")

        assert len(history_a) == 2
        assert history_a[0] == {"role": "user", "content": "Hello from A"}
        assert history_a[1] == {"role": "assistant", "content": "Response to A"}

        assert len(history_b) == 1
        assert history_b[0] == {"role": "user", "content": "Hello from B"}

    def test_clear(self):
        memory = ConversationMemory()

        memory.add_message("user1", "user", "Hello")
        memory.add_message("user1", "assistant", "Hi!")
        assert len(memory.get_history("user1")) == 2

        memory.clear("user1")

        assert memory.get_history("user1") == []

    def test_get_history_returns_copy(self):
        memory = ConversationMemory()

        memory.add_message("user1", "user", "Hello")
        memory.add_message("user1", "assistant", "Hi!")

        history = memory.get_history("user1")
        history.append({"role": "user", "content": "injected"})
        history.clear()

        internal_history = memory.get_history("user1")
        assert len(internal_history) == 2
        assert internal_history[0] == {"role": "user", "content": "Hello"}
        assert internal_history[1] == {"role": "assistant", "content": "Hi!"}
