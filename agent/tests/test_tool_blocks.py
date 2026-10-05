"""The tool rounds a turn stores, and what comes back out of them (MAG-211).

`replay` is the half that matters: the API rejects a conversation whose `tool_result` is
not immediately behind the `tool_use` carrying its id, and a rejected call is read by the
user as « une erreur est survenue ». So every malformed shape below has to come back
empty rather than nearly right.
"""

from app.llm.tool_blocks import TOOL_RESULT_MAX_CHARS, TRUNCATION_MARK, record, replay


def _call(tool_id: str = "toolu_1", name: str = "get_grocery_list", result: str = '{"items": []}') -> dict:
    return {"id": tool_id, "name": name, "input": {"includeDeferred": False}, "result": result}


class TestRecordingARound:
    def test_a_call_becomes_the_two_turns_the_api_takes(self):
        blocks = record([_call()])

        assert blocks == [
            {
                "role": "assistant",
                "content": [
                    {
                        "type": "tool_use",
                        "id": "toolu_1",
                        "name": "get_grocery_list",
                        "input": {"includeDeferred": False},
                    }
                ],
            },
            {
                "role": "user",
                "content": [{"type": "tool_result", "tool_use_id": "toolu_1", "content": '{"items": []}'}],
            },
        ]

    def test_two_calls_of_one_round_keep_their_order(self):
        blocks = record([_call("toolu_1"), _call("toolu_2", name="get_upcoming_events")])

        assert [use["id"] for use in blocks[0]["content"]] == ["toolu_1", "toolu_2"]
        assert [result["tool_use_id"] for result in blocks[1]["content"]] == ["toolu_1", "toolu_2"]

    def test_a_call_without_an_id_is_left_out(self):
        """Its result could never be paired, and the API refuses the round rather than ignore it."""
        blocks = record([{"name": "get_grocery_list", "input": {}, "result": "{}"}])

        assert blocks == []

    def test_a_round_of_nothing_stores_nothing(self):
        assert record([]) == []

    def test_a_long_result_is_cut_and_says_so(self):
        blocks = record([_call(result="x" * (TOOL_RESULT_MAX_CHARS + 500))])

        stored = blocks[1]["content"][0]["content"]
        assert len(stored) == TOOL_RESULT_MAX_CHARS + len(TRUNCATION_MARK)
        # Announced, so the model does not answer from a list it thinks it saw the end of.
        assert stored.endswith(TRUNCATION_MARK)

    def test_a_result_that_is_not_text_is_made_into_text(self):
        blocks = record([{**_call(), "result": {"items": []}}])

        assert blocks[1]["content"][0]["content"] == "{'items': []}"


class TestReplayingARound:
    def test_what_was_recorded_comes_back_as_it_was(self):
        blocks = record([_call()])

        assert replay(blocks) == blocks

    def test_several_rounds_come_back_in_order(self):
        blocks = [*record([_call("toolu_1")]), *record([_call("toolu_2")])]

        turns = replay(blocks)

        assert [turn["role"] for turn in turns] == ["assistant", "user", "assistant", "user"]
        assert turns[0]["content"][0]["id"] == "toolu_1"
        assert turns[2]["content"][0]["id"] == "toolu_2"

    def test_a_result_is_capped_on_the_way_out_too(self):
        """A row written before the ceiling moved must not blow every later prompt."""
        blocks = record([_call()])
        blocks[1]["content"][0]["content"] = "x" * (TOOL_RESULT_MAX_CHARS + 10)

        assert replay(blocks)[1]["content"][0]["content"].endswith(TRUNCATION_MARK)


class TestWhatReplayRefuses:
    def test_a_call_whose_result_went_missing(self):
        blocks = record([_call()])

        assert replay([blocks[0]]) == []

    def test_a_result_whose_id_does_not_match_its_call(self):
        blocks = record([_call()])
        blocks[1]["content"][0]["tool_use_id"] = "toolu_other"

        assert replay(blocks) == []

    def test_a_round_with_more_calls_than_results(self):
        blocks = record([_call("toolu_1"), _call("toolu_2")])
        blocks[1]["content"].pop()

        assert replay(blocks) == []

    def test_one_bad_round_takes_the_good_ones_with_it(self):
        """Half a conversation of blocks is not a safer thing to send: it is the same 400."""
        good = record([_call("toolu_1")])
        bad = record([_call("toolu_2")])
        bad[1]["content"][0]["tool_use_id"] = "toolu_elsewhere"

        assert replay([*good, *bad]) == []

    def test_the_turns_in_the_wrong_roles(self):
        blocks = record([_call()])
        blocks[0]["role"] = "user"

        assert replay(blocks) == []

    def test_a_result_that_is_not_text(self):
        blocks = record([_call()])
        blocks[1]["content"][0]["content"] = {"items": []}

        assert replay(blocks) == []

    def test_a_call_with_no_name(self):
        blocks = record([_call()])
        blocks[0]["content"][0]["name"] = ""

        assert replay(blocks) == []

    def test_an_input_that_is_not_a_mapping(self):
        blocks = record([_call()])
        blocks[0]["content"][0]["input"] = "includeDeferred=false"

        assert replay(blocks) == []

    def test_a_block_whose_type_was_overwritten(self):
        blocks = record([_call()])
        blocks[0]["content"][0]["type"] = "text"

        assert replay(blocks) == []

    def test_a_round_that_is_only_half_a_round(self):
        """An odd number of turns means something other than the loop wrote this column."""
        blocks = record([_call()])

        assert replay([*blocks, blocks[0]]) == []

    def test_a_column_holding_anything_else(self):
        """A truncated or hand-edited row is not an exception the user should see."""
        stored_values = (
            None,
            [],
            {},
            "",
            "[]",
            ["nonsense", "nonsense"],
            [{"role": "assistant"}, {"role": "user"}],
            [{"role": "assistant", "content": []}, {"role": "user", "content": []}],
            [{"role": "assistant", "content": ["toolu_1"]}, {"role": "user", "content": ["toolu_1"]}],
        )
        for stored in stored_values:
            assert replay(stored) == [], stored
