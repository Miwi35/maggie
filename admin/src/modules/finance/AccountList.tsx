import {
  List,
  Datagrid,
  TextField,
  BooleanField,
  FunctionField,
  EditButton,
  DeleteButton,
  useListContext,
} from 'react-admin'
import { useMercure } from '../../hooks/useMercure'
import { ListEmpty } from '../../components/list/ListEmpty'
import { ACCOUNT_TYPE_LABELS } from './accountTypes'
import { Amount } from './AmountField'
import type { RaRecord } from 'react-admin'

const ACCOUNT_TOPICS = ['/api/accounts/{id}']

const AccountDatagrid = () => {
  const { refetch } = useListContext()
  useMercure(ACCOUNT_TOPICS, () => { refetch() })

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
  <List
    empty={
      <ListEmpty
        title="Aucun compte pour l'instant"
        description="Ajoutez vos comptes courants et vos livrets : c'est d'eux que partent les opérations, le matelas et la vue d'ensemble."
        action="Ajouter un compte"
      />
    }
  >
    <AccountDatagrid />
  </List>
)
