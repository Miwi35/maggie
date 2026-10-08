export type EventStatus = 'confirmed' | 'tentative'

/** `cancelled` never reaches a screen: an event is cancelled by deleting it. */
export const toEventStatus = (value: unknown): EventStatus => (value === 'tentative' ? 'tentative' : 'confirmed')

export const statusClassNames = (value: unknown): string[] => (value === 'tentative' ? ['event-tentative'] : [])
