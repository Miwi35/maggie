import {
  Datagrid,
  TextField,
  BooleanField,
  ReferenceField,
  FunctionField,
  EditButton,
  DeleteButton,
  CreateButton,
  TopToolbar,
  useNotify,
  useRefresh,
} from 'react-admin'
import Button from '@mui/material/Button'
import PlayArrowIcon from '@mui/icons-material/PlayArrow'
import { LiveList } from '../../components/list/LiveList'
import type { LiveConfig } from '../../hooks/liveList'
import { ListEmpty } from '../../components/list/ListEmpty'
import { MATCH_TYPE_LABELS, describeRuleScope } from './categorizationRules'
import { DetectInternalTransfersButton } from './DetectInternalTransfersButton'
import { RecurringCatchUpButton } from './RecurringCatchUp'
import { useApplyCategorizationRules } from './useApplyCategorizationRules'
import type { RaRecord } from 'react-admin'
import { CategoryPathField } from './CategoryPathField'

const RULE_TOPICS = ['/api/categorization_rules/{id}']

const RULE_LIVE: LiveConfig = {
  topics: RULE_TOPICS,
  required: ['labelPattern', 'matchType', 'category'],
  references: {
    categoryId: { field: 'category', collection: '/api/categories' },
  },
}

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
  return (
    <Datagrid rowClick="edit">
      <FunctionField
        label="Libellé"
        render={(record: RaRecord) => (record.labelPattern as string | null) || 'Tout libellé'}
      />
      <FunctionField
        label="Correspondance"
        render={(record: RaRecord) =>
          record.labelPattern ? (MATCH_TYPE_LABELS[record.matchType as string] ?? record.matchType) : '—'
        }
      />
      <ReferenceField source="category" reference="categories" label="Catégorie" link={false}>
        <CategoryPathField />
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
    <DetectInternalTransfersButton />
    <RecurringCatchUpButton />
    <CreateButton />
  </TopToolbar>
)

export const CategorizationRuleList = () => (
  <LiveList
    live={RULE_LIVE}
    empty={
      <ListEmpty
        title="Aucune règle pour l'instant"
        description="Une règle classe toute seule les transactions dont le libellé correspond — une fois écrite, elle vaut pour tout l'historique."
        action="Écrire une règle"
        secondaryAction={<DetectInternalTransfersButton />}
      />
    }
    resource="categorization_rules"
    actions={<RuleActions />}
    sort={{ field: 'priority', order: 'DESC' }}
  >
    <RuleDatagrid />
  </LiveList>
)
