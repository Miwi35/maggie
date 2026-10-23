// Proves the rules of eslint.config.js can still refuse: lints the examples next to this
// file, which the main run ignores, and fails when one of them comes back clean.
// Run by `task e2e:web:lint`, after `eslint .`.
import { ESLint } from 'eslint'

const eslint = new ESLint({ ignore: false })
const refused = {
  'partial-name.ts': { rule: 'no-restricted-syntax', times: 2 },
}

let failed = 0
for (const [file, { rule, times }] of Object.entries(refused)) {
  const [result] = await eslint.lintFiles([new URL(file, import.meta.url).pathname])
  const count = result.messages.filter((message) => message.ruleId === rule).length
  if (count === times) {
    console.log(`  ✓ lint-examples/${file}: ${rule} refuses it ${count} time(s)`)
  } else {
    console.error(`  ✗ lint-examples/${file}: expected ${times} ${rule} error(s), got ${count} — the rule no longer matches what it was written for`)
    failed++
  }
}
process.exit(failed > 0 ? 1 : 0)
