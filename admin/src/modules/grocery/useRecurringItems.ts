import { useCallback, useEffect, useMemo, useState } from 'react'
import { useNotify } from 'react-admin'
import { useMercure } from '../../hooks/useMercure'
import { bareId } from './recurring'
import type { Frequency, RecurringItem } from './recurring'

const entrypoint = import.meta.env.VITE_API_URL || 'http://localhost/api'
const COLLECTION = `${entrypoint}/recurring_grocery_items`
const RECURRING_TOPICS = ['/api/recurring_grocery_items/{id}']

const call = (url: string, method: string, body?: unknown) => {
  const token = localStorage.getItem('token')
  const contentType = method === 'PATCH' ? 'application/merge-patch+json' : 'application/ld+json'
  return fetch(url, {
    method,
    headers: {
      Accept: 'application/ld+json',
      Authorization: `Bearer ${token}`,
      ...(body ? { 'Content-Type': contentType } : {}),
    },
    ...(body ? { body: JSON.stringify(body) } : {}),
  })
}

interface Member {
  '@id': string
  id?: string
  product?: string | { '@id': string } | null
  label: string
  frequency: Frequency
  nextDueAt?: string | null
}

const toItem = (m: Member): RecurringItem => {
  const product = typeof m.product === 'string' ? m.product : (m.product?.['@id'] ?? null)
  return {
    id: m.id ?? bareId(m['@id']),
    product: product ? bareId(product) : null,
    label: m.label,
    frequency: m.frequency,
    nextDueAt: m.nextDueAt ?? null,
  }
}

async function fetchItems(): Promise<RecurringItem[]> {
  const response = await call(`${COLLECTION}?itemsPerPage=100`, 'GET')
  if (!response.ok) throw new Error(`recurring items: ${response.status}`)
  const body = await response.json()
  return ((body?.member ?? []) as Member[]).map(toItem)
}

/**
 * The owner's recurring items, kept in step with the API: any Mercure event for
 * one of them re-reads the collection. Writes are not optimistic — the state is
 * what the API answered, so a refusal leaves everything where it was.
 */
export function useRecurringItems() {
  const notify = useNotify()
  const [items, setItems] = useState<RecurringItem[] | null>(null)
  const [failed, setFailed] = useState(false)

  const load = useCallback(async () => {
    try {
      setItems(await fetchItems())
      setFailed(false)
    } catch {
      setFailed(true)
    }
  }, [])

  useEffect(() => {
    load()
  }, [load])
  useMercure(RECURRING_TOPICS, () => {
    load()
  })

  const byProduct = useMemo(() => {
    const map = new Map<string, RecurringItem>()
    for (const item of items ?? []) if (item.product) map.set(item.product, item)
    return map
  }, [items])

  const write = useCallback(
    async (run: () => Promise<Response | null>, message: string): Promise<boolean> => {
      try {
        const response = await run()
        if (response && !response.ok) throw new Error(`recurring items: ${response.status}`)
        await load()
        return true
      } catch {
        notify(message, { type: 'error' })
        return false
      }
    },
    [load, notify],
  )

  /**
   * Make a product recurring (or change how often), or stop it with `null`.
   * The collection is read again first: a second item for the same product is
   * never created, even when the screen has not heard about the first yet.
   */
  const setProductFrequency = useCallback(
    (productId: string, frequency: Frequency | null) =>
      write(async () => {
        const current = (await fetchItems()).find((item) => item.product === productId)
        if (!current) {
          return frequency
            ? call(COLLECTION, 'POST', { product: `/api/products/${productId}`, frequency })
            : null
        }
        if (!frequency) return call(`${COLLECTION}/${current.id}`, 'DELETE')
        return current.frequency === frequency
          ? null
          : call(`${COLLECTION}/${current.id}`, 'PATCH', { frequency })
      }, "Erreur : la récurrence n'a pas été enregistrée"),
    [write],
  )

  const changeFrequency = useCallback(
    (item: RecurringItem, frequency: Frequency) =>
      write(() => call(`${COLLECTION}/${item.id}`, 'PATCH', { frequency }), `Erreur : « ${item.label} » n'a pas été modifié`),
    [write],
  )

  const remove = useCallback(
    (item: RecurringItem) =>
      write(() => call(`${COLLECTION}/${item.id}`, 'DELETE'), `Erreur : « ${item.label} » n'a pas été retiré`),
    [write],
  )

  return { items, failed, byProduct, reload: load, setProductFrequency, changeFrequency, remove }
}

export type RecurringItems = ReturnType<typeof useRecurringItems>
