import { digestMoment } from '../digest/digestApi'

/** The `bilan` block of a message (`blocks` of `GET /agent/messages`). */
export interface BilanBlock {
  type: 'bilan'
  /** `YYYY-MM-DD`: the day the review is for. */
  day: string
  route?: string
}

export const bilanBlockOf = (message: { blocks?: { type: string }[] | null } | null | undefined): BilanBlock | null => {
  const block = message?.blocks?.find((b) => b.type === 'bilan') as Partial<BilanBlock> | undefined

  return block && typeof block.day === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(block.day) ? (block as BilanBlock) : null
}

/** « lun. 26 oct. · 5 étapes », the line under the card's title. */
export const bilanMoment = (day: string): string => `${digestMoment(day, 0, 'short').replace(/ · .*$/, '')} · 5 étapes`
