const capitalise = (text: string) => text.charAt(0).toUpperCase() + text.slice(1)

/** « Jeudi 12 mars »: read as the calendar day it is, whatever the browser's zone. */
export const dayTitle = (isoDate: string) =>
  capitalise(
    new Intl.DateTimeFormat('fr-FR', { weekday: 'long', day: 'numeric', month: 'long', timeZone: 'UTC' }).format(
      new Date(`${isoDate}T00:00:00Z`),
    ),
  )

/** « Mars 2026 ». */
export const monthTitle = (month: string) =>
  capitalise(
    new Intl.DateTimeFormat('fr-FR', { month: 'long', year: 'numeric', timeZone: 'UTC' }).format(
      new Date(`${month}-01T00:00:00Z`),
    ),
  )

/** « le 5 », « le 1er ». */
export const dueDay = (isoDate: string) => {
  const day = Number(isoDate.slice(8, 10))

  return day === 1 ? 'le 1er' : `le ${day}`
}
