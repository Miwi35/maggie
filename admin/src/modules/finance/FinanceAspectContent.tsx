import type { ComponentType } from 'react'
import { ResourceContextProvider } from 'react-admin'
import { AccountList } from './AccountList'
import { BankConnectionsPage } from './BankConnectionsPage'
import { CategoryList } from './CategoryList'
import { EnvelopeList, EnvelopeOutside } from './EnvelopeList'
import { IndependencePanel } from './IndependencePanel'
import { LoanList } from './LoanList'
import { PlacementsSection } from './PlacementsSection'
import { ProjectsPage } from './ProjectsPage'
import { ReviewPage } from './ReviewPage'
import { TreasuryPage } from './TreasuryPage'

/** A list screen is built for its resource's route: outside it, it is told which resource it lists. */
const inResource = (resource: string, List: ComponentType): ComponentType => {
  const InResource = () => (
    <ResourceContextProvider value={resource}>
      <List />
    </ResourceContextProvider>
  )
  InResource.displayName = `InResource(${resource})`

  return InResource
}

/**
 * The body of each aspect's tab (MAG-518), by the aspect's id in `FINANCE_MENUS`:
 * the detailed screen itself, never a summary of it.
 */
export const ASPECT_CONTENT: Record<string, ComponentType> = {
  accounts: inResource('accounts', AccountList),
  review: ReviewPage,
  month: inResource('envelopes', EnvelopeList),
  outside: inResource('envelopes', EnvelopeOutside),
  treasury: TreasuryPage,
  projects: ProjectsPage,
  loans: inResource('loans', LoanList),
  placements: PlacementsSection,
  independence: IndependencePanel,
  banks: BankConnectionsPage,
  categories: inResource('categories', CategoryList),
}
