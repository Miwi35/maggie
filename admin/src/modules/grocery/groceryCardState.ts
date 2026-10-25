import type { NeededEntry } from './groceryQuantity'
import type { RecipeNeed } from './homeRecipeNeeds'

/** Lines shown per store; the rest is told as a count. */
export const ITEMS_PER_STORE = 5

/** A line of `GET /api/grocery_lists/{id}` (its `items`). */
export interface ApiGroceryItem {
  id: string
  label: string
  product?: { packagingUnit?: string | null } | null
  quantity?: number | null
  unit?: string | null
  needed?: NeededEntry[] | null
  checked?: boolean
  store?: { id: string; name: string; visitOrder: number } | null
  /** A date-time from the REST API: its first ten characters are the day. */
  buyAfter?: string | null
  position?: number | null
  aisle?: { position: number; name: string } | null
}

/** An entry of `GET /api/grocery/recipe-needs`: one product a meal lacks. */
export interface ApiRecipeNeed {
  productId: string
  name: string
  date: string
  slot: 'lunch' | 'dinner'
}

export interface HomeItem {
  id: string
  name: string
  quantity: number | null
  unit: string | null
  needed: NeededEntry[] | null
}

export interface HomeStore {
  id: string | null
  name: string | null
  count: number
  items: HomeItem[]
  more: number
}

export interface GroceryHomeState {
  count: number
  forRecipes: RecipeNeed[]
  stores: HomeStore[]
}

export const emptyGroceryState = (): GroceryHomeState => ({ count: 0, forRecipes: [], stores: [] })

const compare = (a: string | number, b: string | number): number => (a < b ? -1 : a > b ? 1 : 0)

/** Aisle by aisle, then by the line's own position; « Sans rayon » last. */
const byAisle = (a: ApiGroceryItem, b: ApiGroceryItem): number =>
  compare(a.aisle ? 0 : 1, b.aisle ? 0 : 1) ||
  compare(a.aisle?.position ?? 0, b.aisle?.position ?? 0) ||
  compare(a.aisle?.name ?? '', b.aisle?.name ?? '') ||
  compare(a.position ?? 0, b.position ?? 0)

const line = (item: ApiGroceryItem): HomeItem => ({
  id: item.id,
  name: item.label,
  quantity: item.quantity ?? null,
  unit: item.unit ?? null,
  // A packaged line is counted in packagings: that count is what to buy, not the need behind it.
  needed: item.product?.packagingUnit ? null : (item.needed ?? null),
})

/**
 * The card's state from the two resources it reads. `today` is the user's local day: a line waiting for a
 * later `buyAfter` is not today's shopping, and a recipe need dated today is « ce midi / ce soir ».
 */
export function composeGroceryState(today: string, items: ApiGroceryItem[], needs: ApiRecipeNeed[]): GroceryHomeState {
  const open = items.filter((item) => !item.checked && (!item.buyAfter || item.buyAfter.slice(0, 10) <= today))

  const groups = new Map<string, { store: ApiGroceryItem['store']; items: ApiGroceryItem[] }>()
  for (const item of open) {
    const key = item.store?.id ?? ''
    const group = groups.get(key) ?? { store: item.store ?? null, items: [] }
    group.items.push(item)
    groups.set(key, group)
  }

  const stores = [...groups.values()]
    .sort((a, b) => compare(a.store?.visitOrder ?? Infinity, b.store?.visitOrder ?? Infinity))
    .map(({ store, items: lines }): HomeStore => {
      const sorted = [...lines].sort(byAisle)

      return {
        id: store?.id ?? null,
        name: store?.name ?? null,
        count: sorted.length,
        items: sorted.slice(0, ITEMS_PER_STORE).map(line),
        more: Math.max(0, sorted.length - ITEMS_PER_STORE),
      }
    })

  const seen = new Set<string>()
  const forRecipes = needs
    .filter((need) => !seen.has(need.productId) && seen.add(need.productId))
    .map((need): RecipeNeed => ({ name: need.name, day: need.date === today ? 'today' : 'tomorrow', slot: need.slot }))

  return { count: open.length, forRecipes, stores }
}
