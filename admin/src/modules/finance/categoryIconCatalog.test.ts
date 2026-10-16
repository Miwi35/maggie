import { readFileSync, statSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { describe, test, expect } from 'vitest'
import { CATEGORY_ICON_SHAPES } from './categoryIconShapes.generated'
import { CATEGORY_ICON_CATALOG, filterCategoryIcons, findCategoryIcon, normalizeSearch } from './categoryIconCatalog'

/**
 * `design/category-icons.json` is the catalogue both clients are generated from
 * (`task icons:generate`); the mobile `CategoryIconCatalogTest` reads the same file. It must be
 * found, never skipped: `task wt:test:admin` mounts `design/` as `/design`.
 */
const catalogueFile = (): string => {
  let candidate = resolve(process.cwd())
  for (;;) {
    const file = join(candidate, 'design', 'category-icons.json')
    try {
      statSync(file)
      return file
    } catch {
      const parent = dirname(candidate)
      if (parent === candidate) break
      candidate = parent
    }
  }
  throw new Error(`No design/category-icons.json found above ${process.cwd()}.`)
}

interface SharedIcon {
  name: string
  label: string
  keywords: string[]
}

const shared = (JSON.parse(readFileSync(catalogueFile(), 'utf8')) as { icons: SharedIcon[] }).icons

const names = (query: string) => filterCategoryIcons(query).map((entry) => entry.name)

describe('category icon catalogue', () => {
  test('lists exactly the names, labels and keywords of design/category-icons.json, in order', () => {
    expect(CATEGORY_ICON_CATALOG.map(({ name, label, keywords }) => ({ name, label, keywords }))).toEqual(shared)
  })

  test('covers every Material icon the two clients share', () => {
    expect(CATEGORY_ICON_CATALOG.length).toBeGreaterThan(1500)
    expect(new Set(CATEGORY_ICON_CATALOG.map((entry) => entry.name)).size).toBe(CATEGORY_ICON_CATALOG.length)
  })

  test('names are kebab case and every one has a shape to draw, and only those', () => {
    for (const entry of CATEGORY_ICON_CATALOG) {
      expect(entry.name, entry.name).toMatch(/^[a-z][a-z0-9]*(-[a-z0-9]+)*$/)
      expect(CATEGORY_ICON_SHAPES[entry.name], entry.name).toBeDefined()
    }
    expect(Object.keys(CATEGORY_ICON_SHAPES).sort()).toEqual(CATEGORY_ICON_CATALOG.map((entry) => entry.name).sort())
  })

  test('keeps the icons chosen before the catalogue grew, with their label', () => {
    expect(CATEGORY_ICON_CATALOG.slice(0, 3).map((entry) => entry.name)).toEqual(['home', 'shopping-cart', 'repeat'])
    expect(findCategoryIcon('directions-car')?.label).toBe('Voiture')
    expect(findCategoryIcon(' Shopping-Cart ')?.label).toBe('Courses')
    expect(findCategoryIcon('icone-disparue')).toBeUndefined()
    expect(findCategoryIcon(null)).toBeUndefined()
  })

  test('gives every icon a non-empty label', () => {
    for (const entry of CATEGORY_ICON_CATALOG) {
      expect(entry.label.trim(), entry.name).not.toBe('')
    }
  })

  test('a blank query keeps everything', () => {
    expect(filterCategoryIcons('')).toHaveLength(CATEGORY_ICON_CATALOG.length)
    expect(filterCategoryIcons('   ')).toHaveLength(CATEGORY_ICON_CATALOG.length)
  })

  test.each([
    ['chien', 'pets'],
    ['médecin', 'medical-services'],
    ['bebe', 'child-care'],
    ['parking', 'local-parking'],
    ['train', 'train'],
    ['voiture', 'directions-car'],
    ['courses', 'shopping-cart'],
    ['carburant', 'local-gas-station'],
  ])('« %s » finds %s', (query, expected) => {
    expect(names(query)).toContain(expected)
  })

  test('the French label comes before a mere keyword', () => {
    expect(names('voiture')[0]).toBe('directions-car')
    expect(names('courses')[0]).toBe('shopping-cart')
  })

  test('searches the English name, split into words', () => {
    expect(names('pets')).toContain('pets')
    expect(names('gas station')).toContain('local-gas-station')
    expect(names('local gas')).toContain('local-gas-station')
    expect(names('gas-station')).toContain('local-gas-station')
  })

  test('every word of the query must match', () => {
    expect(names('gas zzz')).toEqual([])
  })

  test('ignores case and accents, on both sides', () => {
    expect(names('EPARGNE')).toContain('savings')
    expect(names('électricité')).toContain('bolt')
    expect(names('electricite')).toContain('bolt')
    expect(names('coeur')).toContain('favorite')
  })

  test('an unknown word leaves nothing', () => {
    expect(names('zzz')).toEqual([])
  })

  test('normalises accents, case, ligatures and separators', () => {
    expect(normalizeSearch(' Cœur À Épargne ')).toBe('coeur a epargne')
    expect(normalizeSearch('local_gas-station')).toBe('local gas station')
  })
})
