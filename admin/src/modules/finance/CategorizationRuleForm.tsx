import { useMemo, useState } from 'react'
import Box from '@mui/material/Box'
import {
  SimpleForm,
  TextInput,
  NumberInput,
  SelectInput,
  BooleanInput,
  FormDataConsumer,
  required,
} from 'react-admin'
import { MATCH_TYPE_CHOICES, DIRECTION_CHOICES, RULE_NEEDS_IDENTITY, centsInput, ruleIdentifies } from './categorizationRules'
import { FormSection } from '../../components/form/FormSection'
import { RuleSummary } from './RuleSummary'
import { RulePreviewPanel } from './RulePreviewPanel'
import { CategorizationRuleToolbar } from './CategorizationRuleToolbar'
import { RulePreviewContext } from './rulePreviewContext'
import { QualificationInput, RequalifiedNoticeInput } from './Qualification'
import type { Sense } from './qualificationKinds'

const senseOfDirection = (direction: string | undefined): Sense =>
  direction === 'debit' ? 'out' : direction === 'credit' ? 'in' : 'unknown'

const formSx = { maxWidth: 680 }

/** Cross-field: the label may be left out when a direction and an amount bound recognise the rule. */
const validateRule = (values: Record<string, unknown>) =>
  ruleIdentifies(values.labelPattern, values.direction, values.minAmountCents, values.maxAmountCents)
    ? {}
    : { labelPattern: RULE_NEEDS_IDENTITY }
const rowSx = { display: 'flex', gap: 2, flexWrap: 'wrap', width: '100%' }
const halfSx = { flex: '1 1 240px' }

interface CategorizationRuleFormProps {
  withDefaults?: boolean
}

export const CategorizationRuleForm = ({ withDefaults = false }: CategorizationRuleFormProps) => {
  const [changeCount, setChangeCount] = useState<number | null>(null)
  const preview = useMemo(() => ({ changeCount, report: setChangeCount }), [changeCount])

  return (
    <RulePreviewContext.Provider value={preview}>
      <SimpleForm sx={formSx} validate={validateRule} toolbar={<CategorizationRuleToolbar />}>
        <FormSection
          first
          title="Ce que la règle reconnaît"
          description="La règle lit le libellé de chaque transaction. La casse et les accents n'ont pas d'importance. Le libellé est facultatif si vous choisissez un sens et un montant ci-dessous."
        />

        <Box sx={rowSx}>
          <SelectInput
            source="matchType"
            label="Le libellé…"
            choices={MATCH_TYPE_CHOICES}
            defaultValue={withDefaults ? 'contains' : undefined}
            validate={required()}
            sx={halfSx}
            helperText="Comment comparer le libellé au texte ci-contre."
          />
          <TextInput
            source="labelPattern"
            label="…ce texte"
            sx={halfSx}
            helperText="Le nom du commerçant suffit : CARREFOUR, SNCF, NETFLIX… Facultatif avec un sens et un montant."
          />
        </Box>

        <FormSection
          title="Où la classer"
          description="La catégorie posée sur les transactions reconnues."
        />

        <FormDataConsumer>
          {({ formData }) => (
            <QualificationInput
              required
              sense={senseOfDirection(formData.direction as string | undefined)}
              categoryHelperText="Une qualification posée à la main ne sera jamais écrasée par cette règle."
            />
          )}
        </FormDataConsumer>
        <RequalifiedNoticeInput filterKey="qualifiedByRule" />

        <FormSection
          title="Restreindre (facultatif)"
          description="Pour distinguer deux dépenses au même libellé — par exemple un plein d'essence d'un café dans la même station. Sans libellé, un sens (dépense ou revenu) et un montant minimum ou maximum reconnaissent à eux seuls la transaction."
        />

        <SelectInput
          source="direction"
          label="Sens du mouvement"
          choices={DIRECTION_CHOICES}
          defaultValue={withDefaults ? 'any' : undefined}
          validate={required()}
          fullWidth
          helperText="Dépense pour les débits, Revenu pour les crédits."
        />

        <Box sx={rowSx}>
          <NumberInput
            source="minAmountCents"
            label="Montant minimum (€)"
            {...centsInput}
            sx={halfSx}
            helperText="Laissez vide pour ne pas fixer de plancher."
          />
          <NumberInput
            source="maxAmountCents"
            label="Montant maximum (€)"
            {...centsInput}
            sx={halfSx}
            helperText="Montants hors signe : une dépense de 15,99 € vaut 15,99."
          />
        </Box>

        <FormSection
          title="Arbitrage"
          description="Quand plusieurs règles reconnaissent la même transaction, celle de plus haute priorité l'emporte."
        />

        <Box sx={rowSx}>
          <NumberInput
            source="priority"
            label="Priorité"
            defaultValue={withDefaults ? 0 : undefined}
            sx={halfSx}
            helperText="Plus le nombre est grand, plus la règle passe en premier. 0 par défaut."
          />
          <BooleanInput
            source="isActive"
            label="Règle active"
            defaultValue={withDefaults ? true : undefined}
            sx={halfSx}
            helperText="Désactivez-la pour la mettre de côté sans la supprimer."
          />
        </Box>

        <FormDataConsumer>
          {({ formData }) => (
            <RuleSummary
              category={formData.category as string | undefined}
              matchType={formData.matchType as string | undefined}
              labelPattern={formData.labelPattern as string | undefined}
              direction={formData.direction as string | undefined}
              minAmountCents={formData.minAmountCents as number | null | undefined}
              maxAmountCents={formData.maxAmountCents as number | null | undefined}
            />
          )}
        </FormDataConsumer>

        <RulePreviewPanel />
      </SimpleForm>
    </RulePreviewContext.Provider>
  )
}
