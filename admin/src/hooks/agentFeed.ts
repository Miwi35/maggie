import { AGENT_STREAMS, agentTopic } from './agentTopics'
import { mercureUrl } from './mercureUrl'

// May be relative in production ('/.well-known/mercure'): mercureUrl() resolves it.
const MERCURE_URL = import.meta.env.VITE_MERCURE_PUBLIC_URL || 'http://maggie.local/.well-known/mercure'

type Listener = (data: string) => void

interface Feed {
  source: EventSource
  listeners: Set<Listener>
}

const feeds = new Map<string, Feed>()

/**
 * The agent's background streams — contexts and proactions — over one connection.
 *
 * Over plain HTTP/1.1 a browser holds six connections per origin and every
 * EventSource keeps one for good: a page already subscribed to five topics leaves
 * a single slot for all its requests, and a sixth stream starves them (the
 * « Créer » of an event never answered). The two share this feed; each listener
 * recognises its own payloads by their shape and ignores the rest.
 */
export function subscribeAgentFeed(userId: string, listener: Listener): () => void {
  const url = mercureUrl(MERCURE_URL, [
    agentTopic(AGENT_STREAMS.contexts, userId),
    agentTopic(AGENT_STREAMS.proactions, userId),
  ]).toString()

  let feed = feeds.get(url)
  if (!feed) {
    const listeners = new Set<Listener>()
    const source = new EventSource(url, { withCredentials: true })
    source.onmessage = (event) => listeners.forEach((l) => l(event.data))
    feed = { source, listeners }
    feeds.set(url, feed)
  }
  feed.listeners.add(listener)

  const joined = feed
  return () => {
    joined.listeners.delete(listener)
    if (joined.listeners.size === 0) {
      joined.source.close()
      feeds.delete(url)
    }
  }
}
