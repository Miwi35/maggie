import { ROUTINE_TOPICS } from '../calendar/routinesApi'
import { PLACEMENT_TOPIC } from '../finance/placementsApi'
import { PROJECT_TOPIC } from '../finance/projectsApi'
import { SCENARIO_TOPIC } from '../finance/scenariosApi'

/**
 * Every card of the Accueil that reads business resources listens on this one array, so the browser opens
 * one stream for all of them: over HTTP/1.1 it keeps six connections per origin and each EventSource holds
 * one for good, so a stream per card leaves the page unable to send a request (MAG-473). A card that moves
 * to the resources adds its topics here instead of declaring its own.
 */
const GROCERY_TOPICS = [
  '/api/grocery_lists/{id}',
  '/api/stores/{id}',
  '/api/products/{id}',
  '/api/ingredients/{id}',
  '/api/aisles/{id}',
  '/api/meals/{id}',
  '/api/recipes/{id}',
]

/** What the Finance dashboard is made of: spending, envelopes and series, the accounts, and the plan (projects, scenarios, loans, cushion, placements). */
const FINANCE_TOPICS = [
  '/api/transactions/{id}',
  '/api/accounts/{id}',
  '/api/envelopes/{id}',
  '/api/categories/{id}',
  '/api/recurring_operations/{id}',
  '/api/loans/{id}',
  '/api/safety_cushions/{id}',
  PROJECT_TOPIC,
  SCENARIO_TOPIC,
  PLACEMENT_TOPIC,
]

export const HOME_RESOURCE_TOPICS = [
  '/api/tasks/{id}',
  ...ROUTINE_TOPICS,
  '/api/events/{id}',
  '/api/agendas/{id}',
  ...GROCERY_TOPICS,
  ...FINANCE_TOPICS,
]
