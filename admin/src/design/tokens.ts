/**
 * The design tokens, as the admin reads them (MAG-39).
 *
 * This file is a **mirror** of `design/tokens.json`, which is the source the
 * mobile app reads too. It is hand-written on purpose — a generated module
 * would make the Vite build depend on a file outside `admin/` — and
 * `tokens.contract.test.ts` fails the moment the two disagree.
 *
 * Changing a value here alone changes nothing: edit `design/tokens.json`, this
 * file and `mobile/.../ui/theme/Tokens.kt` in the same commit.
 * Rules, and what is deliberately *not* a token:
 * `agent-os/standards/global/design-system.md`.
 */
export const TOKENS = {
  brand: {
    primary: '#9055FD',
    onPrimary: '#FFFFFF',
    secondaryLight: '#A270FF',
    secondaryDark: '#FF83F6',
    // Material 3's `primaryContainer` / `onPrimaryContainer`, per mode. MUI has
    // no container role, so the admin declares them and does not read them.
    containerLight: '#E8DEFF',
    onContainerLight: '#21005D',
    containerDark: '#6200EE',
    onContainerDark: '#E8DEFF',
  },
  surface: {
    light: { background: '#F0F1F6', paper: '#FFFFFF', text: '#544F5A', textMuted: '#89868D' },
    dark: { background: '#110E1C', paper: '#151221', text: '#FFFFFF', textMuted: '#B8B7BB' },
  },
  /** The sign-in, loading and lock screens, on both platforms, and the Android splash. */
  night: { background: '#1A1A2E', raised: '#16213E', text: '#FFFFFF', textMutedAlpha: 0.6 },
  /** Feedback about *this* interaction — MUI's alert roles. */
  feedback: { error: '#DB488B', warning: '#F2E963', info: '#3ED0EB', success: '#0FBF9F' },
  /** Labels on *data* — a criticality, a thread's state, an item just ticked off. */
  signal: {
    success: '#4CAF50',
    warning: '#FF9800',
    danger: '#F44336',
    info: '#2196F3',
    critical: '#9C27B0',
    neutral: '#9E9E9E',
  },
  criticality: { low: 'success', medium: 'warning', high: 'danger', critical: 'critical' },
  contextState: { active: 'success', dormant: 'warning', closed: 'neutral' },
  source: { meals: '#FF6B35', tasks: '#1976D2', done: 'neutral' },
  /** Slots 1 and 2 of the palette validated on the finance dashboard, per mode. */
  chart: {
    categorical: [
      { light: '#2A78D6', dark: '#3987E5' },
      { light: '#EB6834', dark: '#D95926' },
    ],
  },
  typography: {
    family: 'Gabarito, tahoma, sans-serif',
    weight: { regular: 400, medium: 500, semibold: 600, bold: 700 },
    size: { xs: 11, sm: 12, md: 14, lg: 16, xl: 20, xxl: 24, xxxl: 28 },
  },
  radius: { xs: 4, sm: 6, md: 12, lg: 16, xl: 28 },
  /** Absolute px, the 4-px grid. Not `theme.spacing`, whose unit radiant sets to 10. */
  space: { xs: 4, sm: 8, md: 12, lg: 16, xl: 24, xxl: 32 },
} as const

export type SignalName = keyof typeof TOKENS.signal
export type ThemeMode = 'light' | 'dark'

const signal = (name: SignalName): string => TOKENS.signal[name]

/**
 * The colour of a task's criticality. An unknown criticality reads as `low`:
 * the API's enum may grow, and a missing colour would draw a transparent chip.
 */
export const criticalityColor = (criticality: string): string =>
  signal(
    (TOKENS.criticality as Record<string, SignalName>)[criticality] ?? TOKENS.criticality.low,
  )

/** The colour of a conversation thread's state in the Mind panel. */
export const contextStateColor = (state: string): string =>
  signal(
    (TOKENS.contextState as Record<string, SignalName>)[state] ?? TOKENS.contextState.closed,
  )

/** What a calendar entry comes from: an agenda's own colour, or one of these. */
export const SOURCE_COLORS = {
  meals: TOKENS.source.meals,
  tasks: TOKENS.source.tasks,
  done: signal(TOKENS.source.done),
} as const

/** Categorical slot `index` (1-based, as a chart's legend numbers them). */
export const chartColor = (index: number, mode: ThemeMode): string =>
  TOKENS.chart.categorical[index - 1][mode]

/** The secondary text of the night screens — white, at the token's alpha. */
export const NIGHT_TEXT_MUTED = `rgba(255, 255, 255, ${TOKENS.night.textMutedAlpha})`
