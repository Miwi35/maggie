import type { ComponentType } from 'react'
import { FinanceHomeCard } from '../finance/FinanceHomeCard'
import { GroceryHomeCard } from '../grocery/GroceryHomeCard'
import { TasksCard } from './cards/TasksCard'
import { CuisineHomeCard } from '../cookbook/CuisineHomeCard'
import { AgendaCard } from './cards/AgendaCard'
import type { HomeDay } from './homeDay'

/** Which component draws the card of a key; the order and what is shown live in `home.config.ts`. */
export type CardRegistry = Partial<Record<string, ComponentType<{ day: HomeDay }>>>

export const cardRegistry: CardRegistry = {
  tasks: TasksCard,
  agenda: AgendaCard,
  grocery: GroceryHomeCard,
  cuisine: CuisineHomeCard,
  finance: FinanceHomeCard,
}
