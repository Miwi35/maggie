export interface DateRange {
  start: string
  end: string
}

export const getToday = (): DateRange => {
  const now = new Date()
  const start = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  const end = new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1)
  return { start: start.toISOString(), end: end.toISOString() }
}

export const getTomorrow = (): DateRange => {
  const now = new Date()
  const start = new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1)
  const end = new Date(now.getFullYear(), now.getMonth(), now.getDate() + 2)
  return { start: start.toISOString(), end: end.toISOString() }
}

export const getThisWeek = (): DateRange => {
  const now = new Date()
  const start = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  const end = new Date(now.getFullYear(), now.getMonth(), now.getDate() + 7)
  return { start: start.toISOString(), end: end.toISOString() }
}

export const getThisMonth = (): DateRange => {
  const now = new Date()
  const start = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  const end = new Date(now.getFullYear(), now.getMonth() + 1, now.getDate())
  return { start: start.toISOString(), end: end.toISOString() }
}
