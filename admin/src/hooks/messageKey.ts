// `crypto.randomUUID` only exists in a secure context; the key only has to be unique.
export function newMessageKey(): string {
  return typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID()
    : `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`
}
