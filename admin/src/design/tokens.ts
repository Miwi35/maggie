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
    // The accent — « la lueur de Maggie », the only vivid colour outside the module hues.
    primary: '#A68BFF',
    primaryHover: '#C7B5FF',
    onPrimary: '#0F0E17',
    // The same accent darkened for the light mode, so it holds 4.5:1 on ivory.
    primaryLight: '#6B4BD6',
    primaryHoverLight: '#5636B8',
    onPrimaryLight: '#FFFFFF',
    // Material 3's `primaryContainer` / `onPrimaryContainer`, per mode. MUI has
    // no container role, so the admin declares them and does not read them.
    containerLight: '#E6DEFB',
    onContainerLight: '#24105F',
    containerDark: '#3A2E6E',
    onContainerDark: '#E7DEFF',
  },
  surface: {
    light: {
      background: '#F7F4FA',
      paper: '#FFFFFF',
      raised: '#EFEBF6',
      track: '#E3DEEC',
      text: '#1B1826',
      textMuted: '#585468',
      caption: '#6A6679',
    },
    dark: {
      background: '#0F0E17',
      paper: '#1A1824',
      raised: '#25222F',
      track: '#282534',
      text: '#F3F1F8',
      textMuted: '#ABA6B8',
      caption: '#8E899C',
    },
  },
  /** Alpha of the hairline between rows — white on the dark surfaces, ink on the light ones. */
  divider: { light: 0.08, dark: 0.06 },
  /** The sign-in, loading and lock screens, on both platforms, and the Android splash. */
  night: { background: '#1A1A2E', raised: '#16213E', text: '#FFFFFF', textMutedAlpha: 0.6 },
  /** Feedback about *this* interaction — MUI's alert roles. */
  feedback: {
    light: { error: '#B3261E', warning: '#8A5F00', info: '#2F62B8', success: '#1E7A3E' },
    dark: { error: '#F4766E', warning: '#F2C65A', info: '#8FB8FF', success: '#6FCF8E' },
  },
  /** One hue per module, of the same lightness; the menu draws it at 16 % behind the icon. */
  module: {
    cuisine: { light: '#2F62B8', dark: '#8FB8FF' },
    comptes: { light: '#1B7352', dark: '#7FD1AE' },
    sport: { light: '#BF372D', dark: '#FF8A80' },
    travail: { light: '#4F7011', dark: '#B8DE6F' },
  },
  /** What Maggie's own surfaces — the interruption and the chat — are drawn with. */
  maggie: {
    avatarFrom: '#C7B5FF',
    avatarTo: '#6E55D9',
    bubble: { light: '#FFFFFF', dark: '#1F1B2C' },
    panel: { light: '#FBF9FE', dark: '#17151F' },
    reply: { light: '#EFEBF6', dark: '#221F2D' },
  },
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
    family: 'Geist, system-ui, sans-serif',
    mono: "'Geist Mono', ui-monospace, monospace",
    weight: { light: 300, regular: 400, medium: 500, semibold: 600 },
    size: { xs: 11, sm: 12, md: 14, lg: 16, xl: 20, xxl: 24, xxxl: 28 },
  },
  radius: { xs: 4, sm: 8, md: 12, lg: 16, xl: 20, pill: 999 },
  /** Absolute px, the 4-px grid. Not `theme.spacing`, whose unit radiant sets to 10. */
  space: { xs: 4, sm: 8, md: 12, lg: 16, xl: 24, xxl: 32 },
  /** The interface's spring (stiffness, damping) and its two durations, in ms. */
  motion: { springStiffness: 300, springDamping: 30, fastMs: 180, baseMs: 320 },
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
