export interface LabelledAccount {
  name?: string | null
  bank?: string | null
  currency?: string | null
}

/** « Boursorama · EUR · Compte courant »: two banks often both have a « Compte courant ». */
export function accountLabel(account: LabelledAccount): string {
  return [account.bank, account.currency, account.name]
    .map((part) => part?.trim())
    .filter(Boolean)
    .join(' · ')
}
