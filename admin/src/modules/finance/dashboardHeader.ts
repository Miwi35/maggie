const MONTHS = [
  'Janvier',
  'Février',
  'Mars',
  'Avril',
  'Mai',
  'Juin',
  'Juillet',
  'Août',
  'Septembre',
  'Octobre',
  'Novembre',
  'Décembre',
]

/** « Octobre, jour 13 sur 31 ». */
export const dayOfMonthLabel = (now: Date): string => {
  const daysInMonth = new Date(now.getFullYear(), now.getMonth() + 1, 0).getDate()

  return `${MONTHS[now.getMonth()]}, jour ${now.getDate()} sur ${daysInMonth}`
}

/** « Synchronisé il y a 12 min » (« à l'instant » under a minute, then h, then j). */
export const syncedLabel = (syncedAt: Date, now: Date): string => {
  const minutes = Math.max(0, Math.floor((now.getTime() - syncedAt.getTime()) / 60000))
  if (minutes < 1) return "Synchronisé à l'instant"
  if (minutes < 60) return `Synchronisé il y a ${minutes} min`
  if (minutes < 24 * 60) return `Synchronisé il y a ${Math.floor(minutes / 60)} h`

  return `Synchronisé il y a ${Math.floor(minutes / (24 * 60))} j`
}

/** « octobre »: the month named in the lower case of a sentence (« Dépenses d'octobre »). */
export const monthName = (now: Date): string => MONTHS[now.getMonth()].toLowerCase()

/** « d'octobre », « de mars »: the month after « de », elided before a vowel. */
export const ofMonth = (now: Date): string => {
  const name = monthName(now)

  return /^[aeiouyéèâàô]/.test(name) ? `d'${name}` : `de ${name}`
}
