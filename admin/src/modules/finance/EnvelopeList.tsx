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
import { formatCents } from './accountTypes'
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
        render={(record: RaRecord) => formatCents(record.amountCents as number, record.currency as string)}
      />
      <EditButton />
      <DeleteButton />
    </Datagrid>
  )
}

export const EnvelopeList = () => (
  <>
    <BudgetStatusPanel />
    <List sort={{ field: 'year', order: 'DESC' }}>
      <EnvelopeDatagrid />
    </List>
  </>
)
