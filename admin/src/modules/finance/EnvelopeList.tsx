import Typography from '@mui/material/Typography'
import {
  Datagrid,
  WrapperField,
  useRecordContext,
  ReferenceField,
  FunctionField,
  EditButton,
  DeleteButton,
} from 'react-admin'
import { LiveList } from '../../components/list/LiveList'
import type { LiveConfig } from '../../hooks/liveList'
import { ListEmpty } from '../../components/list/ListEmpty'
import { Amount } from './AmountField'
import { BUDGET_MODE_LABELS, ONE_OFF, formatPeriod, formatSeason, seasonFor } from './budgetModes'
import type { Season } from './budgetModes'
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

/** A one-off envelope is named after its event, the others after their category. */
const EnvelopeLabel = () => {
  const record = useRecordContext()
  if (record?.mode === ONE_OFF) {
    return <Typography variant="body2">{record.name as string}</Typography>
  }

  return (
    <ReferenceField source="category" reference="categories" link={false}>
      <CategoryPathField />
    </ReferenceField>
  )
}

const EnvelopeDatagrid = () => {
  return (
    <Datagrid rowClick="edit">
      <WrapperField label="Enveloppe">
        <EnvelopeLabel />
      </WrapperField>
      <FunctionField
        label="Période"
        render={(record: RaRecord) =>
          formatPeriod(record.mode as string, record.year as number | null, record.month as number | null)
        }
      />
      <FunctionField
        label="Mode"
        render={(record: RaRecord) => BUDGET_MODE_LABELS[record.mode as string] ?? record.mode}
      />
      <FunctionField
        label="Budget"
        textAlign="right"
        render={(record: RaRecord) => {
          // A month a season covers is budgeted at the season's amount (MAG-415).
          const season = seasonFor(record.seasonalAmounts as Season[] | undefined, record.month as number | null)
          return (
            <>
              <Amount cents={season?.amountCents ?? (record.amountCents as number)} currency={record.currency as string} />
              {season && (
                <Typography variant="caption" color="text.secondary" component="div">
                  {formatSeason(season)}
                </Typography>
              )}
            </>
          )
        }}
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
          description="Une enveloppe fixe ce que vous vous autorisez sur une catégorie, et les dépenses s'en déduisent au fil du mois. Une enveloppe ponctuelle budgète un événement."
          action="Créer une enveloppe"
        />
      }
      sort={{ field: 'year', order: 'DESC' }}
    >
      <EnvelopeDatagrid />
    </LiveList>
  </>
)
