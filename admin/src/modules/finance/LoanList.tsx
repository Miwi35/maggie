import {
  List,
  Datagrid,
  TextField,
  FunctionField,
  EditButton,
  DeleteButton,
  useListContext,
} from 'react-admin'
import { useMercure } from '../../hooks/useMercure'
import { formatCents } from './accountTypes'
import { DebtTimelinePanel } from './DebtTimelinePanel'
import { formatRate } from './loans'
import type { RaRecord } from 'react-admin'

const LOAN_TOPICS = ['/api/loans/{id}']

const LoanDatagrid = () => {
  const { refetch } = useListContext()
  useMercure(LOAN_TOPICS, () => { refetch() })

  return (
    <Datagrid rowClick="edit">
      <TextField source="name" label="Prêt" />
      <TextField source="lender" label="Organisme" />
      <FunctionField
        label="Capital restant"
        render={(record: RaRecord) =>
          formatCents(record.principalRemainingCents as number, record.currency as string)
        }
      />
      <FunctionField
        label="Mensualité"
        render={(record: RaRecord) =>
          formatCents(record.monthlyPaymentCents as number, record.currency as string)
        }
      />
      <FunctionField
        label="Taux"
        render={(record: RaRecord) => formatRate(record.annualRateBasisPoints as number)}
      />
      <TextField source="priority" label="Priorité" />
      <EditButton />
      <DeleteButton />
    </Datagrid>
  )
}

export const LoanList = () => (
  <>
    <DebtTimelinePanel />
    <List sort={{ field: 'priority', order: 'DESC' }}>
      <LoanDatagrid />
    </List>
  </>
)
