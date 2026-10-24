import type { ComponentType } from 'react'
import { ResourceContextProvider } from 'react-admin'
import { AccountList } from './AccountList'
import { BankConnectionsPage } from './BankConnectionsPage'
import { CategorizationRuleList } from './CategorizationRuleList'
import { CategoryList } from './CategoryList'
import { ContactList } from './ContactList'
import { CushionPage } from './CushionPage'
import { EnvelopeList } from './EnvelopeList'
import { IndependencePanel } from './IndependencePanel'
import { LoanList } from './LoanList'
import { PeerDebtsPage } from './PeerDebtsPage'
import { ProjectsPage } from './ProjectsPage'
import { ReviewPage } from './ReviewPage'
import { TimelinePanel } from './TimelinePanel'
import { RecurringOperationsPage } from './RecurringOperationsPage'
import { SavingsAdvancesPage } from './SavingsAdvancesPage'
import { StatementImportPage } from './StatementImportPage'
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
  import: StatementImportPage,
  contacts: inResource('contacts', ContactList),
  review: ReviewPage,
  rules: inResource('categorization_rules', CategorizationRuleList),
  recurring: RecurringOperationsPage,
  envelopes: inResource('envelopes', EnvelopeList),
  cushion: CushionPage,
  advances: SavingsAdvancesPage,
  'peer-debts': PeerDebtsPage,
  outlook: TreasuryPage,
  projects: ProjectsPage,
  loans: inResource('loans', LoanList),
  independence: IndependencePanel,
  timeline: TimelinePanel,
  banks: BankConnectionsPage,
  categories: inResource('categories', CategoryList),
}
