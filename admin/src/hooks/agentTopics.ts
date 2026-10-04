/**
 * How the agent's Mercure topics are spelled, on the admin side.
 *
 * The agent publishes `/{stream}/{userId}` — outside `/users/`, keyed by the
 * API user's id — and `agent/contract/mercure-topics.json` lists the streams.
 * `useMercure.contract.test.ts` checks this file against it, and that no
 * component spells one by hand.
 */
export const AGENT_STREAMS = {
  chat: 'chat',
  contexts: 'contexts',
  proactions: 'proactions',
  instructions: 'instructions',
  skills: 'skills',
  memory: 'memory',
} as const

export type AgentStream = (typeof AGENT_STREAMS)[keyof typeof AGENT_STREAMS]

export function agentTopic(stream: AgentStream, userId: string): string {
  return `/${stream}/${userId}`
}

export function getStoredUserId(): string | null {
  try {
    const raw = localStorage.getItem('user')
    return raw ? (JSON.parse(raw).id ?? null) : null
  } catch {
    return null
  }
}
