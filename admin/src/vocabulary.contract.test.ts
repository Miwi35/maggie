import { readFileSync, readdirSync } from 'node:fs'
import { dirname, join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

/**
 * « Transaction » is the real line of the account; « opération récurrente » is
 * the abstract entity (MAG-334). The word « opération » alone is no longer a
 * name for a transaction, so it may only appear in front of « récurrente ».
 */
const BARE_OPERATION = /(?<!\p{L})[Oo]pérations?(?!\p{L})(?!\s+récurrent)/gu

const SRC = dirname(fileURLToPath(import.meta.url))

function sourceFiles(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const full = join(dir, entry.name)
    if (entry.isDirectory()) return entry.name === 'test' ? [] : sourceFiles(full)
    return /\.tsx?$/.test(entry.name) && !/\.(test|spec)\.tsx?$/.test(entry.name) ? [full] : []
  })
}

describe('finance vocabulary', () => {
  it('never calls a transaction an « opération »', () => {
    const offenders = sourceFiles(SRC).flatMap((file) =>
      readFileSync(file, 'utf8')
        .split('\n')
        .flatMap((line, index) => (line.match(BARE_OPERATION) ? [`${relative(SRC, file)}:${index + 1}  ${line.trim()}`] : [])),
    )

    expect(offenders, 'say « transaction », or « opération récurrente » for the abstract entity').toEqual([])
  })

  it('recognises the bare word and lets the recurring one through', () => {
    expect('3 opération(s) importée(s)'.match(BARE_OPERATION)).not.toBeNull()
    expect('Les opérations'.match(BARE_OPERATION)).not.toBeNull()
    expect('Nouvelle opération récurrente'.match(BARE_OPERATION)).toBeNull()
    expect('Modifier l’opération récurrente'.match(BARE_OPERATION)).toBeNull()
    expect('Opérations récurrentes'.match(BARE_OPERATION)).toBeNull()
  })
})
