// The REST API sends a date-time, Mercure a plain date: the first ten
// characters are the day in both.
export const formatBuyAfter = (buyAfter: string) => {
  const [y, m, d] = buyAfter.slice(0, 10).split('-')
  return `${d}/${m}/${y}`
}
