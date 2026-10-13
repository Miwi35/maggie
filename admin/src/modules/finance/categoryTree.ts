/** Categories sit on three levels at most (Loisirs › Bars › Obsydian); the API enforces it, the pickers avoid offering what it would refuse. */
export const CATEGORY_MAX_DEPTH = 3

export interface CategoryNode {
  id: string | number
  name?: string
  path?: string
  depth?: number
  parent?: string | null
}

export interface TreeRow<T extends CategoryNode> {
  category: T
  depth: number
}

/** What names a category on screen: its full path when the API gave one, else its own name. */
export const categoryLabel = (category: Pick<CategoryNode, 'name' | 'path'> | null | undefined): string =>
  category?.path || category?.name || ''

const byName = (a: CategoryNode, b: CategoryNode) =>
  (a.name ?? '').localeCompare(b.name ?? '', 'fr', { sensitivity: 'base' })

const childrenIndex = <T extends CategoryNode>(categories: T[]) => {
  const known = new Set<CategoryNode['id']>(categories.map((c) => c.id))
  const children = new Map<CategoryNode['id'] | null, T[]>()
  for (const category of categories) {
    // A parent outside the list (another page) leaves the row at the top.
    const key = category.parent && known.has(category.parent) ? category.parent : null
    children.set(key, [...(children.get(key) ?? []), category])
  }
  for (const list of children.values()) list.sort(byName)

  return children
}

/** The categories in tree order — each parent followed by its children — with their depth (1 for a main category). */
export function categoryTree<T extends CategoryNode>(categories: T[]): TreeRow<T>[] {
  const children = childrenIndex(categories)
  const rows: TreeRow<T>[] = []
  const seen = new Set<CategoryNode['id']>()
  const walk = (key: CategoryNode['id'] | null, depth: number) => {
    for (const category of children.get(key) ?? []) {
      if (seen.has(category.id)) continue
      seen.add(category.id)
      rows.push({ category, depth })
      walk(category.id, depth + 1)
    }
  }
  walk(null, 1)
  // Only a cycle leaves rows unreached; show them rather than lose them.
  for (const category of [...categories].sort(byName)) {
    if (seen.has(category.id)) continue
    seen.add(category.id)
    rows.push({ category, depth: 1 })
    walk(category.id, 2)
  }

  return rows
}

/** The category and everything under it. */
function subtreeOf<T extends CategoryNode>(categories: T[], id: CategoryNode['id']): TreeRow<T>[] {
  const rows = categoryTree(categories)
  const start = rows.findIndex((row) => row.category.id === id)
  if (start === -1) return []
  const root = rows[start].depth
  let end = start + 1
  while (end < rows.length && rows[end].depth > root) end++

  return rows.slice(start, end)
}

/**
 * The categories a category can be attached to, in tree order: not itself nor
 * its descendants, and none that would push its own subtree past three levels.
 * With no `editedId` (a new category) that leaves the levels 1 and 2.
 */
export function parentChoices<T extends CategoryNode>(categories: T[], editedId?: CategoryNode['id'] | null): T[] {
  const subtree = editedId ? subtreeOf(categories, editedId) : []
  const excluded = new Set(subtree.map((row) => row.category.id))
  const height = subtree.length > 0 ? Math.max(...subtree.map((row) => row.depth)) - subtree[0].depth + 1 : 1

  return categoryTree(categories)
    .filter((row) => !excluded.has(row.category.id) && row.depth + height <= CATEGORY_MAX_DEPTH)
    .map((row) => row.category)
}

/** The categories ordered as a tree, for a picker that shows `categoryLabel`. */
export const orderedCategories = <T extends CategoryNode>(categories: T[]): T[] =>
  categoryTree(categories).map((row) => row.category)
