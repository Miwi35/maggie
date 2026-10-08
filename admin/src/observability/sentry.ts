import * as Sentry from '@sentry/react'

// Error tracking (GlitchTip, Sentry-compatible). The DSN is baked in at build:
// empty in dev, test and e2e, so nothing is ever sent from there.
export interface SentryEnv {
  VITE_SENTRY_DSN?: string
  VITE_SENTRY_RELEASE?: string
  VITE_SENTRY_ENVIRONMENT?: string
}

export function initSentry(env: SentryEnv = import.meta.env): boolean {
  const dsn = env.VITE_SENTRY_DSN?.trim()
  if (!dsn) return false

  Sentry.init({
    dsn,
    release: env.VITE_SENTRY_RELEASE || undefined,
    environment: env.VITE_SENTRY_ENVIRONMENT || 'prod',
    // No personal data: no user info (e-mail, IP), cookies, headers, bodies or
    // query strings. (`dataCollection` replaces `sendDefaultPii` since v11.)
    dataCollection: {
      userInfo: false,
      cookies: false,
      httpHeaders: false,
      httpBodies: [],
      urlQueryParams: false,
    },
    // Errors only: no tracing, no replay. The default integrations keep the
    // global handlers for uncaught errors and unhandled promise rejections.
    tracesSampleRate: 0,
    // The Linear bridge files each problem by the component that raised it.
    initialScope: { tags: { component: 'admin' } },
  })
  return true
}
