import { Children, isValidElement } from 'react'
import type { ReactElement, ReactNode } from 'react'
import { describe, expect, test } from 'vitest'
import { calendarResources } from '../calendar'
import { cookbookResources } from '../cookbook'
import { financeResources } from '../finance'
import { groceryResources } from '../grocery'
import { settingsResources } from '../settings'
import { searchResources } from './resources'
import { getResultPath, SEARCH_INDEX_CONFIG } from './searchConfig'
import { notificationLink } from '../../components/maggie/interruptions'

interface Declared {
  /** Pages declared with a `<Route>`. */
  routes: string[]
  /** The pages each resource declares itself: a guessed page is none (the guesser draws what the API shape suggests). */
  resources: Record<string, Set<string>>
}

const PAGES = ['list', 'create', 'edit', 'show'] as const

function declared(): Declared {
  const found: Declared = { routes: [], resources: {} }
  const walk = (node: ReactNode) => {
    Children.forEach(node, (child) => {
      if (!isValidElement(child)) return
      const props = (child as ReactElement<Record<string, unknown>>).props
      if (typeof props.path === 'string') found.routes.push(props.path)
      if (typeof props.name === 'string') found.resources[props.name] = new Set(PAGES.filter((page) => props[page]))
      walk(props.children as ReactNode)
    })
  }
  walk([calendarResources, groceryResources, cookbookResources, financeResources, settingsResources, searchResources])

  return found
}

/** Whether the admin has a page at `path` (`/` is the dashboard; an id is one URL segment, encoded). */
function hasPage(path: string, { routes, resources }: Declared): boolean {
  const pathname = path.split(/[?#]/)[0]
  if (pathname === '/' || routes.includes(pathname)) return true
  const [, name, id, action] = pathname.split('/')
  const pages = resources[name]
  if (!pages) return false
  if (!id) return pages.has('list')
  if (id === 'create' && !action) return pages.has('create')
  if (action === 'show') return pages.has('show')

  return !action && pages.has('edit')
}

describe('where a hit or a notification leads', () => {
  const pages = declared()

  test.each(Object.keys(SEARCH_INDEX_CONFIG))('a %s hit opens a page the admin has', (index) => {
    const path = getResultPath({ index, id: '01ABC', score: 1, data: {}, highlights: {} })

    expect(hasPage(path, pages), path).toBe(true)
  })

  test.each(['events', 'tasks', 'grocery_items', 'recipes'])('a %s notification opens a page the admin has', (resource) => {
    const link = notificationLink(`/api/${resource}/01ABC`)

    expect(link).not.toBeNull()
    expect(hasPage(link!.path, pages), link!.path).toBe(true)
  })

  test('the check itself refuses a show page nobody declared', () => {
    expect(hasPage('/tasks/%2Fapi%2Ftasks%2F01ABC/show', pages)).toBe(false)
  })
})
