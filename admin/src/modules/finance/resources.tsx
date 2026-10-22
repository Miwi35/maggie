import { ResourceGuesser } from '@api-platform/admin'
import { CustomRoutes } from 'react-admin'
import { Navigate, Route } from 'react-router-dom'
import { LEGACY_REVIEW_REDIRECTS } from './reviewFilter'
import { REVIEW_ACTIVITY_PATH } from './reviewActivity'
import { lazyPage } from '../../lazyPage'

const AccountCreate = lazyPage(() => import('./AccountCreate'), 'AccountCreate')
const AccountEdit = lazyPage(() => import('./AccountEdit'), 'AccountEdit')
const AccountList = lazyPage(() => import('./AccountList'), 'AccountList')
const AccountTransactionsView = lazyPage(() => import('./AccountTransactionsView'), 'AccountTransactionsView')
const BankConnectionsPage = lazyPage(() => import('./BankConnectionsPage'), 'BankConnectionsPage')
const CategorizationRuleCreate = lazyPage(() => import('./CategorizationRuleCreate'), 'CategorizationRuleCreate')
const CategorizationRuleEdit = lazyPage(() => import('./CategorizationRuleEdit'), 'CategorizationRuleEdit')
const CategorizationRuleList = lazyPage(() => import('./CategorizationRuleList'), 'CategorizationRuleList')
const CategoryCreate = lazyPage(() => import('./CategoryCreate'), 'CategoryCreate')
const CategoryEdit = lazyPage(() => import('./CategoryEdit'), 'CategoryEdit')
const CategoryList = lazyPage(() => import('./CategoryList'), 'CategoryList')
const ContactCreate = lazyPage(() => import('./ContactCreate'), 'ContactCreate')
const ContactEdit = lazyPage(() => import('./ContactEdit'), 'ContactEdit')
const ContactList = lazyPage(() => import('./ContactList'), 'ContactList')
const CushionPage = lazyPage(() => import('./CushionPage'), 'CushionPage')
const EnvelopeCreate = lazyPage(() => import('./EnvelopeCreate'), 'EnvelopeCreate')
const EnvelopeEdit = lazyPage(() => import('./EnvelopeEdit'), 'EnvelopeEdit')
const EnvelopeList = lazyPage(() => import('./EnvelopeList'), 'EnvelopeList')
const FinanceDashboardPage = lazyPage(() => import('./FinanceDashboardPage'), 'FinanceDashboardPage')
const FinanceSummaryPage = lazyPage(() => import('./FinanceSummaryPage'), 'FinanceSummaryPage')
const LoanCreate = lazyPage(() => import('./LoanCreate'), 'LoanCreate')
const LoanEdit = lazyPage(() => import('./LoanEdit'), 'LoanEdit')
const LoanList = lazyPage(() => import('./LoanList'), 'LoanList')
const PeerDebtsPage = lazyPage(() => import('./PeerDebtsPage'), 'PeerDebtsPage')
const ProjectsPage = lazyPage(() => import('./ProjectsPage'), 'ProjectsPage')
const RecurringOperationsPage = lazyPage(() => import('./RecurringOperationsPage'), 'RecurringOperationsPage')
const ReviewActivityPage = lazyPage(() => import('./ReviewActivityPage'), 'ReviewActivityPage')
const ReviewPage = lazyPage(() => import('./ReviewPage'), 'ReviewPage')
const SavingsAdvancesPage = lazyPage(() => import('./SavingsAdvancesPage'), 'SavingsAdvancesPage')
const StatementImportPage = lazyPage(() => import('./StatementImportPage'), 'StatementImportPage')
const TransactionCreate = lazyPage(() => import('./TransactionCreate'), 'TransactionCreate')
const TransactionList = lazyPage(() => import('./TransactionList'), 'TransactionList')
const TransactionSheet = lazyPage(() => import('./TransactionSheet'), 'TransactionSheet')
const TreasuryPage = lazyPage(() => import('./TreasuryPage'), 'TreasuryPage')

export const financeResources = (
  <>
    <CustomRoutes>
      <Route path="/accounts/:id/transactions" element={<AccountTransactionsView />} />
      <Route path="/finance/dashboard" element={<FinanceDashboardPage />} />
      <Route path="/finance/dashboard/current" element={<FinanceDashboardPage initialTab="current" />} />
      <Route path="/finance/summary/:key/:aspect?" element={<FinanceSummaryPage />} />
      <Route path="/finance/banks" element={<BankConnectionsPage />} />
      <Route path="/finance/review" element={<ReviewPage />} />
      <Route path="/finance/peer-debts" element={<PeerDebtsPage />} />
      <Route path="/finance/import" element={<StatementImportPage />} />
      <Route path="/finance/treasury" element={<TreasuryPage />} />
      <Route path="/finance/projects" element={<ProjectsPage />} />
      <Route path="/finance/cushion" element={<CushionPage />} />
      <Route path="/finance/savings-advances" element={<SavingsAdvancesPage />} />
      {/* The three Revue pages of before are one list (MAG-539): their addresses open it, filtered as they were. */}
      <Route path="/finance/to-sort" element={<Navigate replace to={LEGACY_REVIEW_REDIRECTS['/finance/to-sort']} />} />
      <Route path="/finance/to-control" element={<Navigate replace to={LEGACY_REVIEW_REDIRECTS['/finance/to-control']} />} />
      <Route path="/finance/monthly-review" element={<Navigate replace to={LEGACY_REVIEW_REDIRECTS['/finance/monthly-review']} />} />
      <Route path="/finance/recurring-operations" element={<RecurringOperationsPage />} />
    </CustomRoutes>
    {/* The monthly review as an activity (MAG-456): full screen, out of the menus. */}
    <CustomRoutes noLayout>
      <Route path={REVIEW_ACTIVITY_PATH} element={<ReviewActivityPage />} />
    </CustomRoutes>
    <ResourceGuesser
      name="accounts"
      list={AccountList}
      create={AccountCreate}
      edit={AccountEdit}
    />
    {/* The people behind the transfers (MAG-450), reached from the Comptes area. */}
    <ResourceGuesser
      name="contacts"
      list={ContactList}
      create={ContactCreate}
      edit={ContactEdit}
    />
    <ResourceGuesser
      name="categories"
      list={CategoryList}
      create={CategoryCreate}
      edit={CategoryEdit}
    />
    <ResourceGuesser
      name="envelopes"
      list={EnvelopeList}
      create={EnvelopeCreate}
      edit={EnvelopeEdit}
    />
    <ResourceGuesser
      name="categorization_rules"
      list={CategorizationRuleList}
      create={CategorizationRuleCreate}
      edit={CategorizationRuleEdit}
    />
    <ResourceGuesser
      name="loans"
      list={LoanList}
      create={LoanCreate}
      edit={LoanEdit}
    />
    {/* Transactions are reached through their account (see AccountTransactionsView),
        not from the menu; the resource stays fully registered for routing. */}
    <ResourceGuesser
      name="transactions"
      list={TransactionList}
      create={TransactionCreate}
      edit={TransactionSheet}
    />
  </>
)
