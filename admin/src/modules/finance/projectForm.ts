import type { Project, ProjectDateMode, ProjectInput, ProjectWellbeing } from './projectsApi'

/** The project form's values, checks and conversions (MAG-519). */

export interface ProjectFormValues {
  name: string
  /** As typed: « 1 200,50 ». */
  amount: string
  wellbeing: ProjectWellbeing
  dateMode: ProjectDateMode
  targetOn: string
  dependsOn: string[]
  note: string
}

export type ProjectFormField = 'name' | 'amount' | 'targetOn'
export type ProjectFormErrors = Partial<Record<ProjectFormField, string>>

export const emptyValues = (): ProjectFormValues => ({
  name: '',
  amount: '',
  wellbeing: 'medium',
  dateMode: 'none',
  targetOn: '',
  dependsOn: [],
  note: '',
})

export const valuesOf = (project: Project): ProjectFormValues => ({
  name: project.name,
  amount: String(project.amountCents / 100).replace('.', ','),
  wellbeing: project.wellbeing,
  dateMode: project.dateMode,
  targetOn: project.targetOn ?? '',
  dependsOn: project.dependsOn,
  note: project.note ?? '',
})

/** « 1 200,50 » → 120050; null when it is not an amount. */
export const eurosToCents = (typed: string): number | null => {
  const normalised = typed.replace(/[\s\u00a0\u202f€]/g, '').replace(',', '.')
  if (!/^\d+(\.\d{1,2})?$/.test(normalised)) return null

  return Math.round(Number(normalised) * 100)
}

/** What the API would refuse, said before it is sent. */
export const validateProject = (values: ProjectFormValues): ProjectFormErrors => {
  const errors: ProjectFormErrors = {}
  if (values.name.trim() === '') errors.name = 'Le nom est obligatoire.'
  const cents = eurosToCents(values.amount)
  if (cents === null || cents <= 0) errors.amount = 'Indiquez un montant supérieur à 0.'
  if (values.dateMode !== 'none' && values.targetOn === '') {
    errors.targetOn = values.dateMode === 'fixed' ? 'Choisissez la date du projet.' : 'Choisissez la date limite.'
  }

  return errors
}

export const toInput = (values: ProjectFormValues): ProjectInput => ({
  name: values.name.trim(),
  amountCents: eurosToCents(values.amount) ?? 0,
  wellbeing: values.wellbeing,
  dateMode: values.dateMode,
  targetOn: values.dateMode === 'none' ? null : values.targetOn,
  dependsOn: values.dependsOn,
  note: values.note.trim() === '' ? null : values.note.trim(),
})

