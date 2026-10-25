import { vi } from 'vitest'

export interface StubAisle {
  id: string
  store: string
  name: string
  position: number
}

export interface StubWrite {
  method: string
  url: string
  body: Record<string, unknown> | undefined
}

/**
 * An in-memory `/api/aisles` and `/api/products/{id}` (its `aisles` only): GET
 * lists, POST / PATCH / DELETE change what the next GET answers, as the API
 * does — a move renumbers the store's aisles 1..n. Any other URL answers an
 * empty 200. `failWrites` makes every write a 400 with a Hydra `detail`.
 */
export function stubAislesApi(
  initial: StubAisle[] = [],
  {
    products = {} as Record<string, { store: string; aisle: string }[]>,
    failWrites = false,
  }: { products?: Record<string, { store: string; aisle: string }[]>; failWrites?: boolean } = {},
) {
  const aisles = initial.map((a) => ({ ...a }))
  const productAisles = Object.fromEntries(Object.entries(products).map(([k, v]) => [k, v.map((e) => ({ ...e }))]))
  const writes: StubWrite[] = []
  let next = 1

  const respond = (status: number, body: unknown = {}) =>
    Promise.resolve({ ok: status < 400, status, json: () => Promise.resolve(body) } as Response)

  const ofStore = (store: string) =>
    aisles.filter((a) => a.store === store).sort((a, b) => a.position - b.position)

  const serialise = (a: StubAisle) => ({ '@id': `/api/aisles/${a.id}`, ...a, store: `/api/stores/${a.store}` })

  const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    const method = init?.method ?? 'GET'
    const body = init?.body ? (JSON.parse(String(init.body)) as Record<string, unknown>) : undefined

    const product = url.match(/\/products\/([^/?]+)$/)?.[1]
    if (product) {
      if (method === 'GET') return respond(200, { '@id': `/api/products/${product}`, aisles: productAisles[product] ?? [] })
      writes.push({ method, url, body })
      if (failWrites) return respond(400, { detail: 'Refusé' })
      productAisles[product] = (body?.aisles as { store: string; aisle: string }[]) ?? []
      return respond(200, { aisles: productAisles[product] })
    }

    if (!url.includes('/aisles')) return respond(200)
    const id = url.match(/\/aisles\/([^/?]+)$/)?.[1]

    if (method === 'GET' && !id) {
      const store = new URL(url, 'http://localhost').searchParams.get('store')
      return respond(200, { member: (store === null ? aisles : ofStore(store)).map(serialise) })
    }

    writes.push({ method, url, body })
    if (failWrites) return respond(400, { detail: 'Ce magasin a déjà ce rayon.' })

    if (method === 'POST') {
      const store = String(body?.store).split('/').pop() ?? ''
      const created = { id: `new-${next++}`, store, name: String(body?.name), position: ofStore(store).length + 1 }
      aisles.push(created)
      return respond(201, serialise(created))
    }
    const aisle = aisles.find((a) => a.id === id)
    if (!aisle) return respond(404)
    if (method === 'DELETE') {
      aisles.splice(aisles.indexOf(aisle), 1)
      ofStore(aisle.store).forEach((a, i) => (a.position = i + 1))
      return respond(204)
    }
    if (typeof body?.name === 'string') aisle.name = body.name
    if (typeof body?.position === 'number') {
      const others = ofStore(aisle.store).filter((a) => a !== aisle)
      others.splice(body.position - 1, 0, aisle)
      others.forEach((a, i) => (a.position = i + 1))
    }
    return respond(200, serialise(aisle))
  })

  vi.stubGlobal('fetch', fetchMock)

  return { fetchMock, writes, aisles, productAisles }
}
