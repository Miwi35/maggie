import { useRecordContext } from 'react-admin'
import { FormSection } from '../../components/form/FormSection'
import { RecurrenceSelect } from './RecurrenceSelect'
import { bareId } from './recurring'
import { useRecurringItems } from './useRecurringItems'

/**
 * « Récurrent » on the product sheet. It acts at once, apart from « Enregistrer »:
 * it creates, changes or deletes the product's recurring item. Edit only — a
 * product being created has no id for the item to point at.
 */
export const ProductRecurrence = () => {
  const record = useRecordContext<{ id?: string }>()

  return record?.id ? <RecurrenceSetting productId={bareId(String(record.id))} /> : null
}

const RecurrenceSetting = ({ productId }: { productId: string }) => {
  const recurring = useRecurringItems()
  const current = recurring.byProduct.get(productId)

  return (
    <>
      <FormSection
        title="Achat récurrent"
        description="Un produit récurrent revient seul sur la liste de courses, à la fréquence choisie. S'applique tout de suite, sans « Enregistrer »."
      />
      <RecurrenceSelect
        label="Récurrent"
        value={current?.frequency ?? ''}
        disabled={recurring.items === null}
        onChange={(frequency) => recurring.setProductFrequency(productId, frequency || null)}
      />
    </>
  )
}
