import { useState, useEffect, useRef, useCallback } from 'react'

export interface SearchIndexConfig {
  label: string
  icon: string
  /** API Platform collection segment — `/api/<resource>/<id>` is the record's IRI. */
  resource: string
  /** Admin route of the resource, whose `/:id/show` page is the default destination. */
  basePath: string
  /** Override default navigation path for a result, from its IRI. */
  getPath?: (iri: string) => string
}

export const SEARCH_INDEX_CONFIG: Record<string, SearchIndexConfig> = {
  events: {
    label: 'Événements',
    icon: 'Event',
    resource: 'events',
    basePath: '/events',
    getPath: (iri) => `/calendar?eventId=${encodeURIComponent(iri)}`,
  },
  tasks: { label: 'Tâches', icon: 'CheckCircle', resource: 'tasks', basePath: '/tasks' },
  recipes: { label: 'Recettes', icon: 'Restaurant', resource: 'recipes', basePath: '/recipes' },
  products: { label: 'Produits', icon: 'ShoppingCart', resource: 'products', basePath: '/products' },
  agendas: { label: 'Agendas', icon: 'CalendarMonth', resource: 'agendas', basePath: '/agendas' },
  grocery_lists: {
    label: 'Courses',
    icon: 'ShoppingBag',
    resource: 'grocery_lists',
    basePath: '/grocery',
    getPath: () => '/grocery',
  },
  meals: {
    label: 'Repas',
    icon: 'DinnerDining',
    resource: 'meals',
    basePath: '/events',
    getPath: (iri) => `/calendar?mealId=${encodeURIComponent(iri)}`,
  },
  recurring_grocery_items: {
    label: 'Articles récurrents',
    icon: 'Repeat',
    resource: 'recurring_grocery_items',
    basePath: '/recurring_grocery_items',
  },
  // No admin page lists or shows these two: the dashboard and the preferences are the closest.
  notifications: {
    label: 'Notifications',
    icon: 'Notifications',
    resource: 'notifications',
    basePath: '/notifications',
    getPath: () => '/',
  },
  users: {
    label: 'Utilisateurs',
    icon: 'Person',
    resource: 'users',
    basePath: '/users',
    getPath: () => '/settings/preferences',
  },
}

/**
 * The id react-admin knows a hit by.
 *
 * The Hydra data provider uses the IRI as the record id and `getOne` fetches it
 * as a URL, whereas `/api/search` returns the bare Elasticsearch identifier.
 */
export function getResultRecordId(result: SearchResult): string {
  if (result.id.startsWith('/')) return result.id
  const config = SEARCH_INDEX_CONFIG[result.index]
  return `/api/${config?.resource ?? result.index}/${result.id}`
}

/** Get the navigation path for a search result. */
export function getResultPath(result: SearchResult): string {
  const config = SEARCH_INDEX_CONFIG[result.index]
  if (!config) return '#'
  const iri = getResultRecordId(result)
  if (config.getPath) return config.getPath(iri)
  return `${config.basePath}/${encodeURIComponent(iri)}/show`
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

/**
 * `perType`: ask each index for its own best hits instead of one ranked page.
 *
 * `/api/search` ranks every index together and cuts at `limit`, so a dozen
 * products named "Pâtes …" push the recipe and the meal off a 10-hit page.
 * A dropdown that groups by type needs a few hits of each type, not the top 10.
 */
export function useSearch(debounceMs = 300, perType?: number) {
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
      const token = localStorage.getItem('token')
      const get = async (params: URLSearchParams): Promise<SearchResponse> => {
        const res = await fetch(`/api/search?${params}`, {
          headers: token ? { Authorization: `Bearer ${token}` } : {},
          signal: controller.signal,
        })
        if (!res.ok) throw new Error(`Search failed: ${res.status}`)
        return res.json()
      }

      if (perType) {
        const responses = await Promise.all(
          Object.keys(SEARCH_INDEX_CONFIG).map((index) =>
            get(new URLSearchParams({ q, types: index, page: '1', limit: String(perType) })),
          ),
        )
        setData({
          total: responses.reduce((sum, r) => sum + r.total, 0),
          page: 1,
          limit: perType,
          results: responses.flatMap((r) => r.results).sort((a, b) => b.score - a.score),
        })
      } else {
        const params = new URLSearchParams({ q, page: String(p), limit: String(l) })
        if (t && t.length > 0) {
          params.set('types', t.join(','))
        }
        setData(await get(params))
      }
    } catch (err) {
      if (err instanceof DOMException && err.name === 'AbortError') return
      setData(null)
    } finally {
      setLoading(false)
    }
  }, [perType])

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
