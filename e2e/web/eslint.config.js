import js from '@eslint/js'
import globals from 'globals'
import playwright from 'eslint-plugin-playwright'
import tseslint from 'typescript-eslint'

// Same shape as admin/eslint.config.js, plus the Playwright plugin — which is
// the reason this file exists rather than `tsc` alone. It catches the mistakes
// that make a suite lie: an `expect` nobody awaited (passes whatever happens),
// a conditional assertion, a `test.only` left behind, a `waitForTimeout` where
// a locator should have been waited on.
export default tseslint.config(
  { ignores: ['node_modules', 'playwright-report', 'test-results'] },
  {
    extends: [js.configs.recommended, ...tseslint.configs.recommended],
    files: ['**/*.ts'],
    languageOptions: {
      ecmaVersion: 2022,
      // Both: the helpers run in Node, and `page.evaluate` bodies are typed
      // against the browser's globals.
      globals: { ...globals.node, ...globals.browser },
    },
  },
  // Everything, not just `tests/` — most of the suite's `expect()` calls live
  // in the page objects and helpers, and `missing-playwright-await` (the rule
  // this plugin is here for) would have been switched off over all of them.
  // An un-awaited `expect(locator).toBeVisible()` passes whatever happens.
  {
    ...playwright.configs['flat/recommended'],
    files: ['**/*.ts'],
    rules: {
      ...playwright.configs['flat/recommended'].rules,
      // The assertions live in the page objects and helpers, which is the
      // point of having them — without this the rule reports every journey as
      // having none, and standing warnings are warnings nobody reads. Names
      // are matched exactly (no globs), so this list *is* the harness's
      // assertion surface: add to it when you add a helper that asserts, and
      // the rule keeps catching a journey that really checks nothing.
      'playwright/expect-expect': [
        'warn',
        {
          assertFunctionNames: [
            'expect',
            'expectCalendarView',
            'expectItemEventually',
            'expectLoaded',
            'expectReady',
            'expectRealtimeSync',
            'expectShown',
            'expectSilence',
            'openEvent',
            'openMind',
            'waitFor',
            'waitForIndexed',
          ],
        },
      ],
    },
  },
  {
    // The two rules that only make sense inside a test file. A page object is
    // *supposed* to hold a bare `expect` outside a `test()`, and it has no
    // `test()` of its own for `expect-expect` to look into.
    files: ['pages/**/*.ts', 'helpers/**/*.ts', 'fixtures/**/*.ts'],
    rules: {
      'playwright/no-standalone-expect': 'off',
      'playwright/expect-expect': 'off',
    },
  },
)
