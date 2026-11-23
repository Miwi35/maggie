import type { ReactNode } from 'react'

export interface BilanAsk {
  label: string
  onClick: () => void
}

/** What a step puts on the screen: the centre column, Maggie's line (local, never the model's) and her answers. */
export interface BilanSlot {
  content: ReactNode
  say?: string
  asks?: BilanAsk[]
}
