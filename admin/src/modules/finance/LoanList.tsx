import {
  Datagrid,
  TextField,
  FunctionField,
  EditButton,
  DeleteButton,
} from 'react-admin'
import { LiveList } from '../../components/list/LiveList'
import type { LiveConfig } from '../../hooks/liveList'
import { ListEmpty } from '../../components/list/ListEmpty'
import { Amount } from './AmountField'
import { DebtTimelinePanel } from './DebtTimelinePanel'
import { formatRate } from './loans'
import type { RaRecord } from 'react-admin'

const LOAN_TOPICS = ['/api/loans/{id}']

const LOAN_LIVE: LiveConfig = {
  topics: LOAN_TOPICS,
  required: ['name', 'principalRemainingCents'],
}

const LoanDatagrid = () => {
  return (
    <Datagrid rowClick="edit">
      <TextField source="name" label="Prêt" />
      <TextField source="lender" label="Organisme" />
      <FunctionField
        label="Capital restant"
        textAlign="right"
        render={(record: RaRecord) => (
          <Amount
            cents={record.principalRemainingCents as number}
            currency={record.currency as string}
          />
        )}
      />
      <FunctionField
        label="Mensualité"
        textAlign="right"
        render={(record: RaRecord) => (
          <Amount
            cents={record.monthlyPaymentCents as number}
            currency={record.currency as string}
          />
        )}
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
    <LiveList
      live={LOAN_LIVE}
      empty={
        <ListEmpty
          title="Aucun prêt enregistré"
          description="Renseignez vos crédits en cours pour savoir quand chaque mensualité se libère, et ce qu'il vous reste vraiment à épargner."
          action="Ajouter un prêt"
        />
      }
      sort={{ field: 'priority', order: 'DESC' }}
    >
      <LoanDatagrid />
    </LiveList>
  </>
)
