import { useState, useEffect, useRef, useCallback } from 'react'

export interface SearchIndexConfig {
  label: string
  icon: string
  basePath: string
}

export const SEARCH_INDEX_CONFIG: Record<string, SearchIndexConfig> = {
  events: { label: 'Événements', icon: 'Event', basePath: '/events' },
  tasks: { label: 'Tâches', icon: 'CheckCircle', basePath: '/tasks' },
  recipes: { label: 'Recettes', icon: 'Restaurant', basePath: '/recipes' },
  products: { label: 'Produits', icon: 'ShoppingCart', basePath: '/products' },
  agendas: { label: 'Agendas', icon: 'CalendarMonth', basePath: '/agendas' },
  grocery_lists: { label: 'Courses', icon: 'ShoppingBag', basePath: '/grocery' },
  meals: { label: 'Repas', icon: 'DinnerDining', basePath: '/events' },
  recurring_grocery_items: { label: 'Articles récurrents', icon: 'Repeat', basePath: '/recurring_grocery_items' },
  notifications: { label: 'Notifications', icon: 'Notifications', basePath: '/notifications' },
  users: { label: 'Utilisateurs', icon: 'Person', basePath: '/users' },
}

export interface SearchResult {
  index: string
  id: string
  score: number
  data: Record<string, unknown>
  highlights: Record<string, string[]>
}

export interface SearchResponse {
  total: number
  page: number
  limit: number
  results: SearchResult[]
}

export function useSearch(debounceMs = 300) {
  const [query, setQuery] = useState('')
  const [types, setTypes] = useState<string[] | null>(null)
  const [page, setPage] = useState(1)
  const [limit, setLimit] = useState(10)
  const [data, setData] = useState<SearchResponse | null>(null)
  const [loading, setLoading] = useState(false)
  const timerRef = useRef<ReturnType<typeof setTimeout>>(undefined)
  const abortRef = useRef<AbortController>(undefined)

  const fetchResults = useCallback(async (q: string, t: string[] | null, p: number, l: number) => {
    if (!q.trim()) {
      setData(null)
      return
    }

    abortRef.current?.abort()
    const controller = new AbortController()
    abortRef.current = controller

    setLoading(true)
    try {
      const params = new URLSearchParams({ q, page: String(p), limit: String(l) })
      if (t && t.length > 0) {
        params.set('types', t.join(','))
      }
      const token = localStorage.getItem('token')
      const res = await fetch(`/api/search?${params}`, {
        headers: token ? { Authorization: `Bearer ${token}` } : {},
        signal: controller.signal,
      })
      if (!res.ok) throw new Error(`Search failed: ${res.status}`)
      const json: SearchResponse = await res.json()
      setData(json)
    } catch (err) {
      if (err instanceof DOMException && err.name === 'AbortError') return
      setData(null)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    clearTimeout(timerRef.current)
    if (!query.trim()) {
      setData(null)
      setLoading(false)
      return
    }
    timerRef.current = setTimeout(() => {
      fetchResults(query, types, page, limit)
    }, debounceMs)
    return () => clearTimeout(timerRef.current)
  }, [query, types, page, limit, debounceMs, fetchResults])

  return { query, setQuery, types, setTypes, page, setPage, limit, setLimit, data, loading }
}

/** Get a display label for a search result (first non-empty text field). */
export function getResultLabel(result: SearchResult): string {
  const d = result.data
  return String(d.name || d.title || d.summary || d.label || d.email || result.id)
}

/** Get the first highlight text, or fallback to the label. */
export function getResultHighlight(result: SearchResult): string {
  const entries = Object.values(result.highlights)
  for (const arr of entries) {
    if (arr.length > 0) return arr[0]
  }
  return getResultLabel(result)
}
