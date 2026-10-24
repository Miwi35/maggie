import { createElement } from 'react'
import type { ReactNode } from 'react'
import { FinanceHomeCard } from '../finance/FinanceHomeCard'
import { GroceryHomeCard } from '../grocery/GroceryHomeCard'
import { TasksCard } from './cards/TasksCard'
import { NutritionHomeCard } from '../cookbook/NutritionHomeCard'
import { AgendaCard } from './cards/AgendaCard'
import type { HomeDay } from './homeDay'

/** Where a card sits: the full-width strip of actions, the left column, or the grid of the other modules. */
export type HomeZone = 'action' | 'left' | 'grid'

export interface HomeCardEntry {
  module: string
  zone: HomeZone
  /** Within its zone, ascending. */
  order: number
  render: (props: { day: HomeDay }) => ReactNode
}

/**
 * The modules of the Accueil, in the order the page shows them: the position of
 * a module here is the `order` of its card, so two tickets cannot disagree on it.
 */
export const HOME_MODULES = ['tasks', 'agenda', 'grocery', 'nutrition', 'health', 'finance', 'leisure'] as const

export const homeOrder = (module: (typeof HOME_MODULES)[number]): number => HOME_MODULES.indexOf(module)

/** Each card ticket adds its entry here. */
export const cardRegistry: HomeCardEntry[] = [
  {
    module: 'grocery',
    zone: 'grid',
    order: homeOrder('grocery'),
    render: ({ day }) => createElement(GroceryHomeCard, { day }),
  },
  { module: 'tasks', zone: 'left', order: homeOrder('tasks'), render: ({ day }) => createElement(TasksCard, { day }) },
  {
    module: 'nutrition',
    zone: 'grid',
    order: homeOrder('nutrition'),
    render: ({ day }) => createElement(NutritionHomeCard, { day }),
  },
  {
    module: 'finance',
    zone: 'grid',
    order: homeOrder('finance'),
    render: ({ day }) => createElement(FinanceHomeCard, { day }),
  },
  { module: 'agenda', zone: 'left', order: homeOrder('agenda'), render: ({ day }) => createElement(AgendaCard, { day }) },
]
