/**
 * Money, spelled the way the admin spells it.
 *
 * Every finance screen formats an integer number of cents through
 * `formatCents` (admin/src/modules/finance/accountTypes.ts), which is
 * `Intl.NumberFormat('fr-FR', { style: 'currency' })`. The finance journeys
 * assert on computed amounts — that is what MAG-102 asks for — so they have to
 * build the same string rather than hard-code "1 845,50 €" and hope the two
 * runtimes agree.
 *
 * The whitespace is the whole reason this is a helper. fr-FR groups thousands
 * with a narrow no-break space (U+202F) and puts a no-break space (U+00A0)
 * before the symbol, and *which* one lands where has moved between ICU
 * versions — Node's and Chromium's do not have to match. Playwright normalises
 * whitespace on both sides of a text assertion, so collapsing it here to plain
 * spaces makes the expectation independent of either ICU build.
 */

/** "1 845,50 €" from 184550. */
export function euros(cents: number, currency = 'EUR'): string {
  return new Intl.NumberFormat('fr-FR', { style: 'currency', currency })
    .format(cents / 100)
    .replace(/\s/gu, ' ')
}

/**
 * What the `Amount` component renders with `signed`: a credit carries its `+`,
 * a debit carries the `-` the formatter already gives it.
 */
export function signedEuros(cents: number, currency = 'EUR'): string {
  return `${cents > 0 ? '+' : ''}${euros(cents, currency)}`
}

/** "120,00 € / 200,00 €" — the gauge's own wording, consumed over budget. */
export function overBudget(consumedCents: number, amountCents: number, currency = 'EUR'): string {
  return `${euros(consumedCents, currency)} / ${euros(amountCents, currency)}`
}
