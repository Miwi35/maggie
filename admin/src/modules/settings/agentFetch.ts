/** A call to the agent hub (`/agent/...`) with the stored bearer — what the settings screens talk to. */
export function agentFetch(path: string, options: RequestInit = {}) {
  const token = localStorage.getItem('token')
  return fetch(`/agent${path}`, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...options.headers,
    },
  })
}
