import { useNotify, useRecordContext, useRefresh } from 'react-admin'
import { FormSection } from '../../components/form/FormSection'
import { EnvelopeSplitSlider } from './EnvelopeSplitSlider'
import { useBudgetStatus } from './useBudgetStatus'

/** Looks up the stored envelope's daughters in the period it belongs to. */
const StoredEnvelopeSplit = ({ id, year, month }: { id: string; year: number; month: number | null }) => {
  const { status, refresh } = useBudgetStatus(year, month ?? 1)
  const notify = useNotify()
  const refreshRecord = useRefresh()

  const lines = [...(status?.budgets ?? []), ...(status?.outsideMonthlyBudget?.annual ?? [])]
  const mother = lines.find((line) => line.id === id)
  const daughters = lines.filter((line) => line.parentEnvelopeId === id)
  if (!mother || daughters.length === 0) {
    return null
  }

  const onSaved = () => {
    notify('Répartition enregistrée', { type: 'info' })
    refresh()
    refreshRecord()
  }

  return (
    <>
      <FormSection title="Répartition entre les sous-catégories" />
      <EnvelopeSplitSlider
        key={`${mother.amountCents}:${daughters.map((daughter) => daughter.amountCents).join(',')}`}
        mother={mother}
        daughters={daughters}
        onSaved={onSaved}
        saveLabel="Enregistrer la répartition"
      />
    </>
  )
}

/** The ruler of a stored mother envelope, in its edit form; nothing for a new envelope or one without daughters. */
export const EnvelopeSplitSection = () => {
  const record = useRecordContext()
  if (!record?.id || record.mode === 'one_off') {
    return null
  }

  const id = String(record.id).split('/').pop() as string

  return <StoredEnvelopeSplit id={id} year={record.year as number} month={(record.month as number | null) ?? null} />
}
