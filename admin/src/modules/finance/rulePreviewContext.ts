import { createContext, useContext } from 'react'

interface RulePreviewContextValue {
  /** How many transactions would change category; null until a preview is in. */
  changeCount: number | null
  report: (changeCount: number | null) => void
}

/** Lets the preview panel tell the toolbar's checkbox what it found. */
export const RulePreviewContext = createContext<RulePreviewContextValue>({
  changeCount: null,
  report: () => {},
})

export const useRulePreviewContext = () => useContext(RulePreviewContext)
