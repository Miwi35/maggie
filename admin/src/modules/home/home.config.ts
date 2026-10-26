/** Where a card sits: the cards of the page, the full-width strip of launchers, or a tile of the kiosk. */
export type HomeSlot = 'card' | 'launch' | 'kiosk-tile'

/** The columns of the `card` slot: the left column, or the grid of the other modules. */
export type HomeColumn = 'left' | 'grid'

export interface HomeCardConfig {
  key: string
  /** Defaults to true. */
  enabled?: boolean
  /** Defaults to `grid`; only read in the `card` slot. */
  column?: HomeColumn
}

/** The Accueil of the admin: per slot, the cards in the order they are shown. */
export type HomeConfig = Partial<Record<HomeSlot, HomeCardConfig[]>>

const homeConfig: HomeConfig = {
  card: [
    { key: 'tasks', column: 'left' },
    { key: 'agenda', column: 'left' },
    { key: 'grocery', column: 'grid' },
    { key: 'cuisine', column: 'grid' },
    { key: 'health', column: 'grid' },
    { key: 'finance', column: 'grid' },
    { key: 'leisure', column: 'grid' },
  ],
  launch: [],
}

export default homeConfig
