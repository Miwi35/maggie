/** « À conserver », « Superflue », or not said yet. */
export type Satisfaction = 'unrated' | 'keep' | 'avoidable'

export const KEEP_LABEL = 'À conserver'
export const AVOIDABLE_LABEL = 'Superflue'

/** The two verdicts a spend can be given; saying « unrated » takes the verdict back. */
export const SATISFACTION_CHOICES: { value: Exclude<Satisfaction, 'unrated'>; label: string }[] = [
  { value: 'keep', label: KEEP_LABEL },
  { value: 'avoidable', label: AVOIDABLE_LABEL },
]

export const SATISFACTION_NAME: Record<Exclude<Satisfaction, 'unrated'>, string> = {
  keep: KEEP_LABEL,
  avoidable: AVOIDABLE_LABEL,
}
