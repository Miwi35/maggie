import {
  List,
  Datagrid,
  TextField,
  BooleanField,
  ReferenceField,
  FunctionField,
  EditButton,
  DeleteButton,
  CreateButton,
  TopToolbar,
  useListContext,
  useNotify,
  useRefresh,
} from 'react-admin'
import Button from '@mui/material/Button'
import PlayArrowIcon from '@mui/icons-material/PlayArrow'
import { useMercure } from '../../hooks/useMercure'
import { FinanceEmpty } from './FinanceEmpty'
import { MATCH_TYPE_LABELS, describeRuleScope } from './categorizationRules'
import { useApplyCategorizationRules } from './useApplyCategorizationRules'
import type { RaRecord } from 'react-admin'

const RULE_TOPICS = ['/api/categorization_rules/{id}']

const ApplyRulesButton = () => {
  const { apply, running } = useApplyCategorizationRules()
  const notify = useNotify()
  const refresh = useRefresh()

  const onClick = async () => {
    const result = await apply()
    if (result === null) {
      notify("Impossible d'appliquer les règles", { type: 'error' })

      return
    }
    notify(
      `${result.categorized} transaction(s) catégorisée(s) sur ${result.scanned} analysée(s)`,
      { type: 'info' },
    )
    refresh()
  }

  return (
    <Button startIcon={<PlayArrowIcon />} onClick={onClick} disabled={running}>
      Appliquer les règles
    </Button>
  )
}

const RuleDatagrid = () => {
  const { refetch } = useListContext()
  useMercure(RULE_TOPICS, () => { refetch() })

  return (
    <Datagrid rowClick="edit">
      <TextField source="labelPattern" label="Libellé" />
      <FunctionField
        label="Correspondance"
        render={(record: RaRecord) => MATCH_TYPE_LABELS[record.matchType as string] ?? record.matchType}
      />
      <ReferenceField source="category" reference="categories" label="Catégorie" link={false}>
        <TextField source="name" />
      </ReferenceField>
      <FunctionField
        label="Portée"
        render={(record: RaRecord) =>
          describeRuleScope(
            record.direction as string,
            record.minAmountCents as number | null,
            record.maxAmountCents as number | null,
          )
        }
      />
      <TextField source="priority" label="Priorité" />
      <BooleanField source="isActive" label="Active" />
      <EditButton />
      <DeleteButton />
    </Datagrid>
  )
}

const RuleActions = () => (
  <TopToolbar>
    <ApplyRulesButton />
    <CreateButton />
  </TopToolbar>
)

export const CategorizationRuleList = () => (
  <List
    empty={
      <FinanceEmpty
        title="Aucune règle pour l'instant"
        description="Une règle classe toute seule les opérations dont le libellé correspond — une fois écrite, elle vaut pour tout l'historique."
        action="Écrire une règle"
      />
    }
    resource="categorization_rules"
    actions={<RuleActions />}
    sort={{ field: 'priority', order: 'DESC' }}
  >
    <RuleDatagrid />
  </List>
)
