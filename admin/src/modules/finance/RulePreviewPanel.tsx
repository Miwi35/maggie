import { useRecordContext } from 'react-admin'
import { useWatch } from 'react-hook-form'
import { idOf } from './categorizationRules'
import { RuleMatchesPanel } from './RuleMatchesPanel'
import { useRulePreviewContext } from './rulePreviewContext'
import type { RulePreviewCriteria } from './useCategorizationRulePreview'

const textOrNull = (value: unknown) => (typeof value === 'string' && value !== '' ? value : null)
const centsOrNull = (value: unknown) => (typeof value === 'number' ? value : null)

/** The rule form's own matches: the criteria are the ones being typed in the host form. */
export const RulePreviewPanel = () => {
  const values = useWatch() as Record<string, unknown>
  const record = useRecordContext()
  const { report } = useRulePreviewContext()

  const criteria: RulePreviewCriteria = {
    labelPattern: typeof values.labelPattern === 'string' ? values.labelPattern : '',
    matchType: textOrNull(values.matchType) ?? 'contains',
    direction: textOrNull(values.direction) ?? 'any',
    minAmountCents: centsOrNull(values.minAmountCents),
    maxAmountCents: centsOrNull(values.maxAmountCents),
    categoryId: typeof values.category === 'string' && values.category ? idOf(values.category) : null,
    priority: typeof values.priority === 'number' ? values.priority : 0,
    isActive: values.isActive !== false,
    ruleId: typeof record?.id === 'string' ? idOf(record.id) : null,
  }

  return <RuleMatchesPanel criteria={criteria} onChangeCount={report} />
}
