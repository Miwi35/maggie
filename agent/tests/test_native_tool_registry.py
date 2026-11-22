import pytest

from app.runtime.tools.registry import NativeTool, NativeToolRegistry


async def _handler(arguments, ctx):
    return "ok"


def _tool(name: str, offered=lambda: True, **schema) -> NativeTool:
    return NativeTool(definition={"name": name, "input_schema": schema}, handler=_handler, offered=offered)


def test_registering_a_name_twice_is_refused():
    registry = NativeToolRegistry()
    registry.register(_tool("a"))
    with pytest.raises(ValueError, match="already registered"):
        registry.register(_tool("a"))


def test_definitions_keep_registration_order_and_skip_tools_not_offered():
    registry = NativeToolRegistry()
    registry.register(_tool("b"))
    registry.register(_tool("hidden", offered=lambda: False))
    registry.register(NativeTool(definition=lambda: {"name": "lazy"}, handler=_handler))
    registry.register(_tool("a"))

    assert [d["name"] for d in registry.definitions()] == ["b", "lazy", "a"]
    assert registry.names() == {"b", "hidden", "lazy", "a"}


def test_required_fields_read_the_input_schema():
    registry = NativeToolRegistry()
    registry.register(_tool("a", required=["x"]))
    registry.register(_tool("b"))

    assert registry.required_fields("a") == ["x"]
    assert registry.required_fields("b") == []
    assert registry.required_fields("unknown") == []


@pytest.mark.asyncio
async def test_with_handler_copies_the_registry_and_swaps_one_handler():
    registry = NativeToolRegistry()
    registry.register(_tool("a"))

    async def other(arguments, ctx):
        return "other"

    copy = registry.with_handler("a", other)

    assert await copy.get("a").handler({}, None) == "other"
    assert await registry.get("a").handler({}, None) == "ok"
