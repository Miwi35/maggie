import { useEffect, useState } from 'react'

export const DEFAULT_AGENT_NAME = 'Maggie'

const NAME_CHANGED = 'agent-name-changed'

/** Tells every `useAgentName` that the personality was saved under a new name. */
export function announceAgentName(name: string) {
  window.dispatchEvent(new CustomEvent(NAME_CHANGED, { detail: name }))
}

const labelOf = (name: unknown): string =>
  typeof name === 'string' && name.trim() !== '' ? name.trim() : DEFAULT_AGENT_NAME

/** The name the user gave their agent (personality `name`), « Maggie » until it is known. */
export function useAgentName(): string {
  const [name, setName] = useState(DEFAULT_AGENT_NAME)

  useEffect(() => {
    let active = true
    const token = localStorage.getItem('token')

    fetch('/agent/personality', { headers: token ? { Authorization: `Bearer ${token}` } : {} })
      .then((res) => (res.ok ? res.json() : null))
      .then((personality) => {
        if (active && personality) setName(labelOf(personality.name))
      })
      .catch(() => {})

    const onChange = (event: Event) => setName(labelOf((event as CustomEvent).detail))
    window.addEventListener(NAME_CHANGED, onChange)

    return () => {
      active = false
      window.removeEventListener(NAME_CHANGED, onChange)
    }
  }, [])

  return name
}
