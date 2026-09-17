import { ResourceGuesser } from '@api-platform/admin'
import { CustomRoutes } from 'react-admin'
import { Route } from 'react-router-dom'
import { AccountCreate } from './AccountCreate'
import { AccountEdit } from './AccountEdit'
import { AccountList } from './AccountList'
import { AccountTransactionsView } from './AccountTransactionsView'
import { CategoryCreate } from './CategoryCreate'
import { CategoryEdit } from './CategoryEdit'
import { CategorizationRuleCreate } from './CategorizationRuleCreate'
import { CushionPage } from './CushionPage'
import { FinanceDashboardPage } from './FinanceDashboardPage'
import { LoanCreate } from './LoanCreate'
import { MonthlyReviewPage } from './MonthlyReviewPage'
import { LoanEdit } from './LoanEdit'
import { LoanList } from './LoanList'
import { CategorizationRuleEdit } from './CategorizationRuleEdit'
import { CategorizationRuleList } from './CategorizationRuleList'
import { CategoryList } from './CategoryList'
import { EnvelopeCreate } from './EnvelopeCreate'
import { EnvelopeEdit } from './EnvelopeEdit'
import { EnvelopeList } from './EnvelopeList'
import { TransactionCreate } from './TransactionCreate'
import { TransactionEdit } from './TransactionEdit'
import { TransactionList } from './TransactionList'

export const financeResources = (
  <>
    <CustomRoutes>
      <Route path="/accounts/:id/transactions" element={<AccountTransactionsView />} />
      <Route path="/finance/dashboard" element={<FinanceDashboardPage />} />
      <Route path="/finance/cushion" element={<CushionPage />} />
      <Route path="/finance/monthly-review" element={<MonthlyReviewPage />} />
    </CustomRoutes>
    <ResourceGuesser
      name="accounts"
      list={AccountList}
      create={AccountCreate}
      edit={AccountEdit}
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
      edit={TransactionEdit}
    />
  </>
)
