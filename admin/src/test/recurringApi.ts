import { vi } from 'vitest'

export interface StubMember {
  id: string
  product: string | null
  label: string
  frequency: string
  nextDueAt?: string
}

/**
 * An in-memory `/api/recurring_grocery_items`: GET lists, POST / PATCH / DELETE
 * change what the next GET answers, as the real collection does. Any other URL
 * answers an empty 200. `failWrites` makes every write a 500.
 */
export function stubRecurringApi(initial: StubMember[] = [], { failWrites = false, failReads = false } = {}) {
  const members = initial.map((m) => ({ ...m }))
  const writes: { method: string; url: string; body: Record<string, unknown> | undefined }[] = []

  const respond = (status: number, body: unknown = {}) =>
    Promise.resolve({ ok: status < 400, status, json: () => Promise.resolve(body) } as Response)

  const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    if (!url.includes('/recurring_grocery_items')) return respond(200)
    const method = init?.method ?? 'GET'
    const id = url.split('/recurring_grocery_items/')[1]
    const body = init?.body ? (JSON.parse(String(init.body)) as Record<string, unknown>) : undefined

    if (method === 'GET') {
      if (failReads) return respond(500)
      return respond(200, {
        member: members.map((m) => ({ ...m, '@id': `/api/recurring_grocery_items/${m.id}` })),
      })
    }
    writes.push({ method, url, body })
    if (failWrites) return respond(500)
    if (method === 'POST') {
      const product = String(body?.product)
      members.push({
        id: `R${members.length + 1}`,
        product,
        label: `Produit ${product.split('/').pop()}`,
        frequency: String(body?.frequency),
        nextDueAt: '2099-01-08',
      })
      return respond(201)
    }
    const index = members.findIndex((m) => m.id === id)
    if (method === 'PATCH') Object.assign(members[index], body)
    if (method === 'DELETE') members.splice(index, 1)
    return respond(method === 'DELETE' ? 204 : 200)
  })

  vi.stubGlobal('fetch', fetchMock)

  return { fetchMock, members, writes }
}
