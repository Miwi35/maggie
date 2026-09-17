import {
  List,
  Datagrid,
  TextField,
  ReferenceField,
  FunctionField,
  EditButton,
  DeleteButton,
  useListContext,
} from 'react-admin'
import { useMercure } from '../../hooks/useMercure'
import { ListEmpty } from '../../components/list/ListEmpty'
import { Amount } from './AmountField'
import { BUDGET_MODE_LABELS, formatPeriod } from './budgetModes'
import { BudgetStatusPanel } from './BudgetStatusPanel'
import type { RaRecord } from 'react-admin'

const ENVELOPE_TOPICS = ['/api/envelopes/{id}']

const EnvelopeDatagrid = () => {
  const { refetch } = useListContext()
  useMercure(ENVELOPE_TOPICS, () => { refetch() })

  return (
    <Datagrid rowClick="edit">
      <ReferenceField source="category" reference="categories" label="Catégorie" link={false}>
        <TextField source="name" />
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
    <List
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
    </List>
  </>
)
