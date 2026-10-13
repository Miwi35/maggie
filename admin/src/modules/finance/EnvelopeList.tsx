import {
  Datagrid,
  ReferenceField,
  FunctionField,
  EditButton,
  DeleteButton,
} from 'react-admin'
import { LiveList } from '../../components/list/LiveList'
import type { LiveConfig } from '../../hooks/liveList'
import { ListEmpty } from '../../components/list/ListEmpty'
import { Amount } from './AmountField'
import { BUDGET_MODE_LABELS, formatPeriod } from './budgetModes'
import { BudgetStatusPanel } from './BudgetStatusPanel'
import type { RaRecord } from 'react-admin'
import { CategoryPathField } from './CategoryPathField'

const ENVELOPE_TOPICS = ['/api/envelopes/{id}']

const ENVELOPE_LIVE: LiveConfig = {
  topics: ENVELOPE_TOPICS,
  required: ['mode', 'amountCents', 'category'],
  references: {
    categoryId: { field: 'category', collection: '/api/categories' },
  },
}

const EnvelopeDatagrid = () => {
  return (
    <Datagrid rowClick="edit">
      <ReferenceField source="category" reference="categories" label="Catégorie" link={false}>
        <CategoryPathField />
      </ReferenceField>
      <FunctionField
        label="Période"
        render={(record: RaRecord) =>
          formatPeriod(record.mode as string, record.year as number, record.month as number | null)
        }
      />
      <FunctionField
        label="Mode"
        render={(record: RaRecord) => BUDGET_MODE_LABELS[record.mode as string] ?? record.mode}
      />
      <FunctionField
        label="Budget"
        textAlign="right"
        render={(record: RaRecord) => (
          <Amount cents={record.amountCents as number} currency={record.currency as string} />
        )}
      />
      <EditButton />
      <DeleteButton />
    </Datagrid>
  )
}

export const EnvelopeList = () => (
  <>
    <BudgetStatusPanel />
    <LiveList
      live={ENVELOPE_LIVE}
      empty={
        <ListEmpty
          title="Aucune enveloppe pour l'instant"
          description="Une enveloppe fixe ce que vous vous autorisez sur une catégorie, et les dépenses s'en déduisent au fil du mois."
          action="Créer une enveloppe"
        />
      }
      sort={{ field: 'year', order: 'DESC' }}
    >
      <EnvelopeDatagrid />
    </LiveList>
  </>
)
