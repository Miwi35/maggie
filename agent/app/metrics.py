"""Prometheus metrics for Maggie Agent Hub."""

from prometheus_client import Counter, Histogram

# ---------------------------------------------------------------------------
# LLM pricing table (USD per 1M tokens)
# ---------------------------------------------------------------------------
PRICING: dict[str, tuple[float, float]] = {
    # (input_price, output_price) per 1M tokens
    "claude-sonnet-5-5": (2.0, 10.0),
    "claude-haiku-4-5-20251001": (1.0, 5.0),
    "claude-sonnet-4-5-20250929": (3.0, 15.0),
    "claude-sonnet-4-5-20250514": (3.0, 15.0),
    "claude-opus-4-20250514": (15.0, 75.0),
    # Fallback aliases
    "claude-3-5-haiku-latest": (1.0, 5.0),
    "claude-3-5-sonnet-latest": (3.0, 15.0),
    "claude-3-opus-latest": (15.0, 75.0),
}

DEFAULT_PRICING = (3.0, 15.0)  # Sonnet-level as safe default

# Prompt caching, as multiples of the input price: a 5-minute cache write costs 1.25x, a cache read 0.1x
CACHE_WRITE_MULTIPLIER = 1.25
CACHE_READ_MULTIPLIER = 0.1

# ---------------------------------------------------------------------------
# Counters
# ---------------------------------------------------------------------------
LLM_INPUT_TOKENS = Counter(
    "maggie_llm_input_tokens_total",
    "Total LLM input tokens consumed",
    ["model", "call_type"],
)

LLM_OUTPUT_TOKENS = Counter(
    "maggie_llm_output_tokens_total",
    "Total LLM output tokens consumed",
    ["model", "call_type"],
)

LLM_CACHE_CREATION_TOKENS = Counter(
    "maggie_llm_cache_creation_input_tokens_total",
    "Total LLM input tokens written to the prompt cache",
    ["model", "call_type"],
)

LLM_CACHE_READ_TOKENS = Counter(
    "maggie_llm_cache_read_input_tokens_total",
    "Total LLM input tokens read from the prompt cache",
    ["model", "call_type"],
)

LLM_COST_USD = Counter(
    "maggie_llm_cost_usd_total",
    "Estimated LLM cost in USD",
    ["model", "call_type"],
)

LLM_REQUESTS = Counter(
    "maggie_llm_requests_total",
    "Total LLM API requests",
    ["model", "call_type", "status"],
)

TOOL_CALLS = Counter(
    "maggie_tool_calls_total",
    "Total MCP/native tool calls",
    ["tool_name", "source"],
)

# ---------------------------------------------------------------------------
# Histograms
# ---------------------------------------------------------------------------
LLM_REQUEST_DURATION = Histogram(
    "maggie_llm_request_duration_seconds",
    "LLM API request duration in seconds",
    ["model", "call_type"],
    buckets=(0.5, 1, 2, 5, 10, 20, 30, 60),
)


def usage_kwargs(usage) -> dict[str, int]:
    """Token counts of an Anthropic response usage, as record_llm_usage keyword arguments."""

    def count(name: str) -> int:
        value = getattr(usage, name, 0)
        return value if isinstance(value, int) else 0

    return {
        "input_tokens": count("input_tokens"),
        "output_tokens": count("output_tokens"),
        "cache_creation_input_tokens": count("cache_creation_input_tokens"),
        "cache_read_input_tokens": count("cache_read_input_tokens"),
    }


def record_llm_usage(
    model: str,
    call_type: str,
    input_tokens: int,
    output_tokens: int,
    duration_seconds: float,
    status: str = "success",
    cache_creation_input_tokens: int = 0,
    cache_read_input_tokens: int = 0,
) -> None:
    """Record metrics for a single LLM API call.

    `input_tokens` excludes the tokens read from or written to the prompt cache, as in the Anthropic usage.
    """
    LLM_CACHE_CREATION_TOKENS.labels(model=model, call_type=call_type).inc(cache_creation_input_tokens)
    LLM_CACHE_READ_TOKENS.labels(model=model, call_type=call_type).inc(cache_read_input_tokens)
    LLM_INPUT_TOKENS.labels(model=model, call_type=call_type).inc(input_tokens)
    LLM_OUTPUT_TOKENS.labels(model=model, call_type=call_type).inc(output_tokens)
    LLM_REQUESTS.labels(model=model, call_type=call_type, status=status).inc()
    LLM_REQUEST_DURATION.labels(model=model, call_type=call_type).observe(duration_seconds)

    # Estimate cost
    input_price, output_price = PRICING.get(model, DEFAULT_PRICING)
    cost = (
        input_tokens * input_price
        + cache_creation_input_tokens * input_price * CACHE_WRITE_MULTIPLIER
        + cache_read_input_tokens * input_price * CACHE_READ_MULTIPLIER
        + output_tokens * output_price
    ) / 1_000_000
    LLM_COST_USD.labels(model=model, call_type=call_type).inc(cost)
