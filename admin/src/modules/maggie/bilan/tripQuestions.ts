import type { AgendaHomeEvent } from '../../home/cards/agendaTimeline'
import { clock } from '../../home/cards/agendaTimeline'
import type { Trip } from '../../calendar/trips'
import { isReturn } from '../../calendar/trips'

export type TripQuestionKind = 'origin' | 'mode' | 'parking'

/** A reply: `message` is what Maggie receives (she keeps the trip with `set_trip`); null means the owner will say it aloud. */
export interface TripChoice {
  label: string
  message: string | null
}

export interface TripQuestion {
  eventId: string
  kind: TripQuestionKind
  text: string
  choices: TripChoice[]
  /** The key `answers.trips` keeps once it is answered. */
  key: string
}

export const tripAnswerKey = (eventId: string, kind: TripQuestionKind): string => `${eventId}:${kind}`

const MODES = [
  { mode: 'drive', label: 'Voiture', phrase: 'en voiture' },
  { mode: 'transit', label: 'Transports', phrase: 'en transports' },
  { mode: 'bicycle', label: 'Vélo', phrase: 'à vélo' },
  { mode: 'walk', label: 'À pied', phrase: 'à pied' },
]

export const modePhrase = (mode: string): string => MODES.find((entry) => entry.mode === mode)?.phrase ?? 'en voiture'

export const lowerFirst = (text: string): string => text.charAt(0).toLowerCase() + text.slice(1)

const tripMessage = (event: AgendaHomeEvent, answer: string): string => `Bilan du soir, trajet de demain · événement ${event.id} « ${event.summary} » : ${answer}.`

const tripEventId = (trip: Trip): string => trip.event.slice(trip.event.lastIndexOf('/') + 1)

const place = (event: AgendaHomeEvent): string => event.locationShort ?? event.summary

const originQuestion = (event: AgendaHomeEvent, previous: AgendaHomeEvent | undefined): TripQuestion => {
  const key = tripAnswerKey(event.id, 'origin')
  if (!previous) {
    return {
      eventId: event.id,
      kind: 'origin',
      key,
      text: `D’où partez-vous pour ${place(event)} ?`,
      choices: [
        { label: 'De la maison', message: tripMessage(event, 'je pars de la maison') },
        { label: 'De ma position', message: tripMessage(event, 'je pars de ma position') },
      ],
    }
  }

  return {
    eventId: event.id,
    kind: 'origin',
    key,
    text: `Vous repassez chez vous entre ${lowerFirst(previous.summary)} et ${lowerFirst(event.summary)} ?`,
    choices: [
      { label: 'Oui', message: tripMessage(event, 'je pars de la maison') },
      { label: 'Non, je reste en ville', message: tripMessage(event, `je pars de l’événement ${previous.id} « ${previous.summary} »`) },
    ],
  }
}

const modeQuestion = (event: AgendaHomeEvent, trip: Trip): TripQuestion => ({
  eventId: event.id,
  kind: 'mode',
  key: tripAnswerKey(event.id, 'mode'),
  text: `Comment allez-vous à ${place(event)} ? Je compte ${modePhrase(trip.mode)}.`,
  choices: MODES.map((entry) => ({ label: entry.label, message: tripMessage(event, `j’y vais ${entry.phrase}`) })),
})

const parkingQuestion = (event: AgendaHomeEvent): TripQuestion => ({
  eventId: event.id,
  kind: 'parking',
  key: tripAnswerKey(event.id, 'parking'),
  text: `Où vous garez-vous à ${place(event)} ?`,
  choices: [
    { label: 'Dans la rue', message: tripMessage(event, 'je me gare dans la rue') },
    { label: 'Dans un parking', message: tripMessage(event, 'je me gare dans un parking') },
    { label: 'Je précise', message: null },
  ],
})

/**
 * The first trip question of tomorrow not answered yet, event by event in the order of the day, or null: nothing to
 * prepare. A question is answered once the trip's origin was chosen by the owner, or its key is in `answered`.
 */
export const tripQuestion = (events: AgendaHomeEvent[], trips: Trip[], answered: ReadonlySet<string>): TripQuestion | null => {
  const located = events
    .filter((event) => !event.allDay && event.status !== 'cancelled' && !!event.location?.trim() && !!event.startAt)
    .sort((a, b) => Date.parse(a.startAt ?? '') - Date.parse(b.startAt ?? ''))

  for (const [index, event] of located.entries()) {
    const trip = trips.find((candidate) => tripEventId(candidate) === event.id && !isReturn(candidate))
    const originKnown = trip?.originDecidedBy === 'user' || answered.has(tripAnswerKey(event.id, 'origin'))
    if (!originKnown) return originQuestion(event, located[index - 1])
    if (!trip) continue
    if (!answered.has(tripAnswerKey(event.id, 'mode'))) return modeQuestion(event, trip)
    if (trip.mode === 'drive' && !answered.has(tripAnswerKey(event.id, 'parking'))) return parkingQuestion(event)
  }

  return null
}

const spoken = (event: AgendaHomeEvent): string => {
  const [hours, minutes] = clock(event.startAt ?? '', event.timeZone).split(':').map(Number)

  return minutes === 0 ? `${hours} h` : `${hours} h ${String(minutes).padStart(2, '0')}`
}

/** Maggie's line: the first two appointments, in her voice, then the question when there is one. */
export const tomorrowSay = (events: AgendaHomeEvent[], question: TripQuestion | null): string => {
  const timed = events.filter((event) => !event.allDay && event.status !== 'cancelled' && event.startAt).sort((a, b) => Date.parse(a.startAt ?? '') - Date.parse(b.startAt ?? ''))
  const programme =
    timed.length === 0
      ? events.length === 0
        ? 'Rien à l’agenda demain.'
        : 'Voici ce qui vous attend demain.'
      : timed.length === 1
        ? `${timed[0].summary} à ${spoken(timed[0])}.`
        : `${timed[0].summary} à ${spoken(timed[0])}, puis ${lowerFirst(timed[1].summary)} à ${spoken(timed[1])}.`

  return question ? `${programme} ${question.text}` : programme
}
