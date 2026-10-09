import path from 'node:path'
import { SourceMapConsumer } from 'source-map-js'
import type { RawSourceMap } from 'source-map-js'

/**
 * Chromium's JS coverage of a bundle, brought back to the lines of `admin/src`.
 *
 * Pure functions, no browser: what `coverage.ts` feeds them is what
 * `page.coverage.stopJSCoverage()` returns, and the source map the bundle
 * points at.
 *
 * Not `v8-to-istanbul`: it rebuilds an Istanbul report per script and per call,
 * when all a journey needs is « which source lines ran ». The bundle's mappings
 * are decoded once per worker ({@link indexScript}), and each test then costs a
 * pass over its ranges ({@link executedBitmap}) and one over the mappings
 * ({@link executedLines}).
 */

/** One block of V8's precise coverage: offsets in UTF-16 units of the script's source. */
export interface V8Range {
  startOffset: number
  endOffset: number
  count: number
}

export interface V8Function {
  ranges: V8Range[]
}

/** A bundle's mappings that land in the sources we keep, as flat arrays. */
export interface IndexedScript {
  /** Repo-relative source paths (`admin/src/main.tsx`). */
  files: string[]
  /** For each mapping: its generated offset in the bundle, the index of its file, its line there. */
  offsets: Int32Array
  fileIndex: Int32Array
  lines: Int32Array
}

/**
 * One byte per character of the script: 1 where the code ran, 0 where it did not.
 *
 * V8's ranges nest — a function, then the blocks inside it that did not run —
 * and the innermost range covering an offset is the one that counts. Painted
 * from the longest to the shortest, the innermost is painted last and wins.
 */
export function executedBitmap(length: number, functions: V8Function[]): Uint8Array {
  const ranges = functions.flatMap((fn) => fn.ranges)
  ranges.sort((a, b) => b.endOffset - b.startOffset - (a.endOffset - a.startOffset))

  const bitmap = new Uint8Array(length)
  for (const range of ranges) {
    const end = Math.min(range.endOffset, length)
    bitmap.fill(range.count > 0 ? 1 : 0, Math.max(range.startOffset, 0), end)
  }

  return bitmap
}

/** Where each line of `source` starts, as an offset. */
function lineStarts(source: string): number[] {
  const starts = [0]
  for (let i = source.indexOf('\n'); -1 !== i; i = source.indexOf('\n', i + 1)) {
    starts.push(i + 1)
  }

  return starts
}

/**
 * Decodes a bundle's source map once, keeping the mappings whose source
 * `toRepoPath` accepts (it returns `null` for `node_modules` and the like).
 */
export function indexScript(
  source: string,
  map: RawSourceMap,
  toRepoPath: (mapSource: string) => string | null,
): IndexedScript {
  const consumer = new SourceMapConsumer(map)
  const starts = lineStarts(source)
  const files: string[] = []
  const known = new Map<string, number>()
  const offsets: number[] = []
  const fileIndex: number[] = []
  const lines: number[] = []

  consumer.eachMapping((mapping) => {
    if (!mapping.source || !mapping.originalLine) {
      return
    }
    const lineStart = starts[mapping.generatedLine - 1]
    if (undefined === lineStart) {
      return
    }

    let index = known.get(mapping.source)
    if (undefined === index) {
      const repoPath = toRepoPath(mapping.source)
      index = null === repoPath ? -1 : files.push(repoPath) - 1
      known.set(mapping.source, index)
    }
    if (index < 0) {
      return
    }

    offsets.push(lineStart + mapping.generatedColumn)
    fileIndex.push(index)
    lines.push(mapping.originalLine)
  })

  return {
    files,
    offsets: Int32Array.from(offsets),
    fileIndex: Int32Array.from(fileIndex),
    lines: Int32Array.from(lines),
  }
}

/** The source lines a mapping of which landed on executed code, per file, added to `into`. */
export function executedLines(
  script: IndexedScript,
  bitmap: Uint8Array,
  into: Map<string, Set<number>> = new Map(),
): Map<string, Set<number>> {
  for (let i = 0; i < script.offsets.length; i++) {
    if (1 !== bitmap[script.offsets[i]]) {
      continue
    }
    const file = script.files[script.fileIndex[i]]
    let lines = into.get(file)
    if (!lines) {
      lines = new Set()
      into.set(file, lines)
    }
    lines.add(script.lines[i])
  }

  return into
}

/**
 * Where a source named in a map sits in the repository.
 *
 * Vite writes the sources relative to the map's own location
 * (`admin/dist/assets/index-abc.js.map` names `../../src/main.tsx`), so the
 * map's directory in the repository plus the source, normalised, is the path.
 * Anything outside `keepPrefix` — `node_modules`, Vite's virtual modules — is
 * dropped.
 */
export function repoPathOf(mapDirInRepo: string, mapSource: string, keepPrefix: string): string | null {
  if (/^[a-z][a-z0-9+.-]*:/i.test(mapSource) || mapSource.startsWith('/')) {
    return null
  }
  const resolved = path.posix.normalize(path.posix.join(mapDirInRepo, mapSource))

  return resolved.startsWith(keepPrefix) ? resolved : null
}
