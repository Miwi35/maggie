import type { APIRequestContext } from '@playwright/test'

/**
 * Calling an MCP tool the way Maggie does, from a journey.
 *
 * The HTTP transport is stateful: `tools/call` without the `initialize`
 * handshake answers "a valid session id is REQUIRED". The `api` fixture already
 * carries the user's JWT, which `/_mcp` accepts as a bearer.
 */

const HEADERS = { 'Content-Type': 'application/json', Accept: 'application/json, text/event-stream' }

let requestId = 0

/** The first `text` field anywhere in the envelope: the transport is free to move it. */
function toolText(body: string): string {
  const json = body.replace(/^data: /m, '')
  const found = (value: unknown): string | undefined => {
    if (typeof value !== 'object' || value === null) return undefined
    if (typeof (value as { text?: unknown }).text === 'string') return (value as { text: string }).text
    for (const child of Object.values(value)) {
      const text = found(child)
      if (text !== undefined) return text
    }
    return undefined
  }

  const text = found(JSON.parse(json))
  if (text === undefined) throw new Error(`MCP answered nothing readable: ${body.slice(0, 400)}`)

  return text
}

export async function callMcpTool(
  api: APIRequestContext,
  name: string,
  args: Record<string, unknown>,
): Promise<Record<string, unknown>> {
  const initialize = await api.post('/_mcp', {
    headers: HEADERS,
    data: {
      jsonrpc: '2.0',
      id: ++requestId,
      method: 'initialize',
      params: { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'e2e-web', version: '1.0.0' } },
    },
  })
  const session = initialize.headers()['mcp-session-id']
  if (!session) throw new Error(`MCP handshake returned no session id (${initialize.status()})`)

  const headers = { ...HEADERS, 'Mcp-Session-Id': session }
  await api.post('/_mcp', { headers, data: { jsonrpc: '2.0', method: 'notifications/initialized' } })

  const call = await api.post('/_mcp', {
    headers,
    data: { jsonrpc: '2.0', id: ++requestId, method: 'tools/call', params: { name, arguments: args } },
  })

  return JSON.parse(toolText(await call.text())) as Record<string, unknown>
}
