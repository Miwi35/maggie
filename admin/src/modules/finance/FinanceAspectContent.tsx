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
import { MonthlyReviewPage } from './MonthlyReviewPage'
import { PeerDebtsPage } from './PeerDebtsPage'
import { RecognisedToControlPage } from './RecognisedToControlPage'
import { RecurringOperationsPage } from './RecurringOperationsPage'
import { SavingsAdvancesPage } from './SavingsAdvancesPage'
import { StatementImportPage } from './StatementImportPage'
import { ToSortPage } from './ToSortPage'
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
  'to-sort': ToSortPage,
  'to-control': RecognisedToControlPage,
  'monthly-review': MonthlyReviewPage,
  rules: inResource('categorization_rules', CategorizationRuleList),
  recurring: RecurringOperationsPage,
  envelopes: inResource('envelopes', EnvelopeList),
  cushion: CushionPage,
  advances: SavingsAdvancesPage,
  'peer-debts': PeerDebtsPage,
  outlook: TreasuryPage,
  loans: inResource('loans', LoanList),
  independence: IndependencePanel,
  banks: BankConnectionsPage,
  categories: inResource('categories', CategoryList),
}
