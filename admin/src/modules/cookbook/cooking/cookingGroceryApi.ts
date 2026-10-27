import type { CookingGroceryItem } from './cookingGrocery'

const authorization = (): Record<string, string> => {
  const token = localStorage.getItem('token')

  return token ? { Authorization: `Bearer ${token}` } : {}
}

const get = async <T>(path: string, signal: AbortSignal): Promise<T> => {
  const response = await fetch(path, { signal, headers: { Accept: 'application/ld+json', ...authorization() } })
  if (!response.ok) throw new Error(`GET ${path} answered ${response.status}`)

  return (await response.json()) as T
}

/** The lines of the open list: the user has a single one, whose own resource embeds them. */
export const fetchGroceryItems = async (signal: AbortSignal): Promise<CookingGroceryItem[]> => {
  const lists = await get<{ member?: { id: string }[] }>('/api/grocery_lists?order[createdAt]=DESC&itemsPerPage=1', signal)
  const id = lists.member?.[0]?.id
  if (!id) return []
  const list = await get<{ items?: CookingGroceryItem[] }>(`/api/grocery_lists/${encodeURIComponent(id)}`, signal)

  return list.items ?? []
}

export const patchChecked = async (id: string, checked: boolean): Promise<boolean> => {
  try {
    const response = await fetch(`/api/grocery_items/${encodeURIComponent(id)}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/merge-patch+json', ...authorization() },
      body: JSON.stringify({ checked }),
    })

    return response.ok
  } catch {
    return false
  }
}
