import {
  Datagrid,
  TextField,
  BooleanField,
  FunctionField,
  EditButton,
  DeleteButton,
} from 'react-admin'
import { LiveList } from '../../components/list/LiveList'
import type { LiveConfig } from '../../hooks/liveList'
import { ListEmpty } from '../../components/list/ListEmpty'
import { ACCOUNT_TYPE_LABELS } from './accountTypes'
import { Amount } from './AmountField'
import type { RaRecord } from 'react-admin'

const ACCOUNT_TOPICS = ['/api/accounts/{id}']

const ACCOUNT_LIVE: LiveConfig = {
  topics: ACCOUNT_TOPICS,
  required: ['name', 'type', 'balanceCents'],
}

const AccountDatagrid = () => {
  return (
    <Datagrid rowClick={(id) => `/accounts/${String(id).split('/').pop()}/transactions`}>
      <TextField source="name" label="Nom" />
      <TextField source="bank" label="Banque" />
      <FunctionField
        label="Type"
        render={(record: RaRecord) => ACCOUNT_TYPE_LABELS[record.type as string] ?? record.type}
      />
      <FunctionField
        label="Solde"
        textAlign="right"
        render={(record: RaRecord) => (
          <Amount
            cents={record.balanceCents as number}
            currency={record.currency as string}
            bold
          />
        )}
      />
      <BooleanField source="isCushion" label="Matelas" />
      <EditButton />
      <DeleteButton />
    </Datagrid>
  )
}

export const AccountList = () => (
  <LiveList
    live={ACCOUNT_LIVE}
    empty={
      <ListEmpty
        title="Aucun compte pour l'instant"
        description="Ajoutez vos comptes courants et vos livrets : c'est d'eux que partent les transactions, le matelas et la vue d'ensemble."
        action="Ajouter un compte"
      />
    }
  >
    <AccountDatagrid />
  </LiveList>
)
