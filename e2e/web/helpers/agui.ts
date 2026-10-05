/**
 * Reading the AG-UI event stream the agent emits on `POST /agent/chat/stream`.
 *
 * Why the harness parses the stream at all, rather than only looking at the
 * chat bubbles: with `LLM_PROVIDER=fake` the *wording* is a fixture — the fake
 * does not read tool results, so a scripted sentence cannot prove anything
 * about data. What the stream does prove is the production plumbing around it:
 * which tool really ran, that the answer arrived as deltas rather than in one
 * buffered lump, and that the run finished instead of hanging.
 *
 * Assert wording against the scenario, data against the API. See
 * agent/fixtures/fake-llm/README.md.
 */

export interface AgUiEvent {
  type: string
  [key: string]: unknown
}

/** Splits an SSE body into its `data:` payloads. Malformed lines are dropped, as the admin drops them. */
export function parseAgUiStream(body: string): AgUiEvent[] {
  const events: AgUiEvent[] = []

  for (const line of body.split('\n')) {
    if (!line.startsWith('data: ')) {
      continue
    }

    const payload = line.slice(6).trim()
    if (!payload) {
      continue
    }

    try {
      const parsed: unknown = JSON.parse(payload)
      if (typeof parsed === 'object' && parsed !== null && typeof (parsed as AgUiEvent).type === 'string') {
        events.push(parsed as AgUiEvent)
      }
    } catch {
      // Same as the admin's own reader: skip and keep going.
    }
  }

  return events
}

/**
 * The assistant's answer, reassembled the way the admin and the app do: a
 * `TEXT_MESSAGE_START` empties the bubble, so the text a step said before a tool
 * call gives way to the last step's (MAG-229).
 */
export function assistantText(events: AgUiEvent[]): string {
  let text = ''

  for (const event of events) {
    if (event.type === 'TEXT_MESSAGE_START') {
      text = ''
    } else if (event.type === 'TEXT_MESSAGE_CONTENT') {
      text += String(event.delta ?? '')
    }
  }

  return text
}

/** How many deltas the answer arrived in — one means the gateway buffered it. */
export function deltaCount(events: AgUiEvent[]): number {
  return events.filter((event) => event.type === 'TEXT_MESSAGE_CONTENT').length
}

/** How many events of a kind the run emitted. */
export function countEvents(events: AgUiEvent[], type: string): number {
  return events.filter((event) => event.type === type).length
}

/**
 * The message ids the run opened, deduplicated.
 *
 * One answer is one bubble, however many tool rounds it took. The gateway
 * opens a single `messageId` and holds it across every iteration — and the
 * version that did not is half of `176c40c`: each round emitted its own
 * `TEXT_MESSAGE_START`/`END` pair, the admin finalised a bubble per round, and
 * Maggie answered the same question two or three times over.
 */
export function messageIds(events: AgUiEvent[]): string[] {
  return [
    ...new Set(
      events
        .filter((event) => event.type === 'TEXT_MESSAGE_START')
        .map((event) => String(event.messageId ?? '')),
    ),
  ]
}

/** The MCP tools the run actually called, in order. */
export function calledTools(events: AgUiEvent[]): string[] {
  return events
    .filter((event) => event.type === 'TOOL_CALL_START')
    .map((event) => String(event.toolName ?? ''))
}

/**
 * What the run reported about each tool it called: the Mind panel's own source.
 *
 * `TOOL_CALL_START` says a tool was asked for; only the `tool_result` custom
 * event says how it ended. Asserting on the first alone is how `a7b08cf` would
 * have slipped through again — the agent was emitting tool *markup as text*,
 * which looks like activity and writes nothing.
 */
export function toolResults(events: AgUiEvent[]): Array<{ toolName: string; status: string }> {
  return events
    .filter((event) => event.type === 'CUSTOM' && event.name === 'tool_result')
    .map((event) => {
      const value = (event.value ?? {}) as { toolName?: unknown; status?: unknown }

      return { toolName: String(value.toolName ?? ''), status: String(value.status ?? '') }
    })
}

/** The `context_update` the context router emitted, whole: `action`, `id`, `label`, `status`. */
export function contextUpdate(events: AgUiEvent[]): Record<string, unknown> | null {
  const update = events.find((event) => event.type === 'CUSTOM' && event.name === 'context_update')
  const value = update?.value

  return typeof value === 'object' && value !== null ? (value as Record<string, unknown>) : null
}

/** The `context_update` the context router emitted: `created` or `matched`. */
export function contextAction(events: AgUiEvent[]): string | null {
  const action = contextUpdate(events)?.action

  return action === undefined ? null : String(action)
}

/** The label the router gave the context this message landed in. */
export function contextLabel(events: AgUiEvent[]): string | null {
  const label = contextUpdate(events)?.label

  return label === undefined ? null : String(label)
}

/** True when the fake answered because no scenario matched — the message names its own cause. */
export function isUnscripted(text: string): boolean {
  return text.includes('[fake-llm]')
}
