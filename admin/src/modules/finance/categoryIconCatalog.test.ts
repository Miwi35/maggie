import { describe, test, expect } from 'vitest'
import { CATEGORY_ICONS } from './CategoryGlyph'
import { CATEGORY_ICON_CATALOG, filterCategoryIcons, normalizeSearch } from './categoryIconCatalog'

/** The reference list: the mobile `CategoryIconCatalogTest` carries the same one. Add a name to both. */
const REFERENCE_NAMES = [
  'home',
  'shopping-cart',
  'repeat',
  'water-drop',
  'bolt',
  'wifi',
  'tv',
  'cloud',
  'sports-esports',
  'trending-up',
  'local-gas-station',
  'account-balance',
  'error-outline',
  'payments',
  'star',
  'volunteer-activism',
  'undo',
  'redeem',
  'restaurant',
  'directions-car',
  'flight',
  'school',
  'favorite',
  'fitness-center',
  'movie',
  'savings',
]

const names = (query: string) => filterCategoryIcons(query).map((entry) => entry.name)

describe('category icon catalogue', () => {
  test('lists the same icons as the reference list, in order', () => {
    expect(CATEGORY_ICON_CATALOG.map((entry) => entry.name)).toEqual(REFERENCE_NAMES)
  })

  test('draws exactly the icons it lists', () => {
    expect(Object.keys(CATEGORY_ICONS).sort()).toEqual([...REFERENCE_NAMES].sort())
  })

  test('gives every icon a label and at least one search word', () => {
    for (const entry of CATEGORY_ICON_CATALOG) {
      expect(entry.label.trim(), entry.name).not.toBe('')
      expect(entry.keywords.length, entry.name).toBeGreaterThan(0)
    }
  })

  test('a blank query keeps everything', () => {
    expect(names('')).toEqual(REFERENCE_NAMES)
    expect(names('   ')).toEqual(REFERENCE_NAMES)
  })

  test('« voiture » leaves the car alone, « courses » the cart alone', () => {
    expect(names('voiture')).toEqual(['directions-car'])
    expect(names('courses')).toEqual(['shopping-cart'])
  })

  test('searches the keywords and the icon name, not only the label', () => {
    expect(names('garage')).toEqual(['directions-car'])
    expect(names('gas-station')).toEqual(['local-gas-station'])
  })

  test('ignores case and accents, on both sides', () => {
    expect(names('EPARGNE')).toEqual(['savings'])
    expect(names('électricité')).toEqual(['bolt'])
    expect(names('electricite')).toEqual(['bolt'])
    expect(names('coeur')).toEqual(['favorite'])
  })

  test('an unknown word leaves nothing', () => {
    expect(names('zzz')).toEqual([])
  })

  test('normalises accents, case and ligatures', () => {
    expect(normalizeSearch(' Cœur À Épargne ')).toBe('coeur a epargne')
  })
})
