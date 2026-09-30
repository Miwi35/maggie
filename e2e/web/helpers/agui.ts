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

/** The assistant's answer, reassembled from its `TEXT_MESSAGE_CONTENT` deltas. */
export function assistantText(events: AgUiEvent[]): string {
  return events
    .filter((event) => event.type === 'TEXT_MESSAGE_CONTENT')
    .map((event) => String(event.delta ?? ''))
    .join('')
}

/** How many deltas the answer arrived in — one means the gateway buffered it. */
export function deltaCount(events: AgUiEvent[]): number {
  return events.filter((event) => event.type === 'TEXT_MESSAGE_CONTENT').length
}

/** The MCP tools the run actually called, in order. */
export function calledTools(events: AgUiEvent[]): string[] {
  return events
    .filter((event) => event.type === 'TOOL_CALL_START')
    .map((event) => String(event.toolName ?? ''))
}

/** The `context_update` the context router emitted: `created` or `matched`. */
export function contextAction(events: AgUiEvent[]): string | null {
  const update = events.find((event) => event.type === 'CUSTOM' && event.name === 'context_update')
  const value = update?.value

  if (typeof value === 'object' && value !== null && 'action' in value) {
    return String((value as { action: unknown }).action)
  }

  return null
}

/** True when the fake answered because no scenario matched — the message names its own cause. */
export function isUnscripted(text: string): boolean {
  return text.includes('[fake-llm]')
}
