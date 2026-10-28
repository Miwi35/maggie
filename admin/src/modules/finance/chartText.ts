import { useCallback, useLayoutEffect, useState } from 'react'

/** Chart text is drawn in CSS pixels whatever the width: the `viewBox` zoom is undone label by label. */
export const LABEL_PX = 11

const GLYPH_RATIO = 0.56

/**
 * How many viewBox units one rendered pixel covers, for an `<svg width="100%">`.
 * 1 until the element has a width (first paint, jsdom), so a label is never scaled to nothing.
 */
export const useUnitsPerPixel = (viewBoxWidth: number) => {
  const [element, setElement] = useState<SVGSVGElement | null>(null)
  const [width, setWidth] = useState(0)
  const ref = useCallback((node: SVGSVGElement | null) => setElement(node), [])

  useLayoutEffect(() => {
    if (!element) {
      return undefined
    }
    const measure = () => setWidth(element.getBoundingClientRect().width)
    measure()
    if (typeof ResizeObserver === 'undefined') {
      return undefined
    }
    const observer = new ResizeObserver(measure)
    observer.observe(element)

    return () => observer.disconnect()
  }, [element])

  return { ref, unitsPerPixel: width > 0 ? viewBoxWidth / width : 1 }
}

export const textWidthPx = (text: string, fontPx: number = LABEL_PX): number => text.length * fontPx * GLYPH_RATIO

/** « BOUYGUES TELECOM BOUYGUES TELECO » → « BOUYGUES TELE… »: a marker names its debit, the list below spells it out. */
export const truncate = (text: string, max: number): string =>
  text.length <= max ? text : `${text.slice(0, Math.max(1, max - 1)).trimEnd()}…`

export interface LabelSlot {
  /** Centre of the label, pixels from the left of the plot. */
  centerPx: number
  widthPx: number
}

export interface PlacedLabel {
  /** Centre kept inside [0, containerPx], pixels. */
  xPx: number
  row: number
}

/**
 * Stacks labels on rows so that no two on the same row touch: left to right, each takes the first row
 * where it clears the previous label of that row. Past `maxRows` the last row is shared.
 */
export const placeLabels = (slots: LabelSlot[], containerPx: number, gapPx: number, maxRows: number): PlacedLabel[] => {
  const rowEnds: number[] = []
  const placed: PlacedLabel[] = new Array(slots.length)
  const order = slots.map((_, index) => index).sort((a, b) => slots[a].centerPx - slots[b].centerPx)

  for (const index of order) {
    const { centerPx, widthPx } = slots[index]
    const half = widthPx / 2
    const xPx = Math.min(Math.max(centerPx, half), Math.max(half, containerPx - half))
    const left = xPx - half
    let row = rowEnds.findIndex((end) => left >= end + gapPx)
    if (row < 0) {
      row = Math.min(rowEnds.length, maxRows - 1)
    }
    rowEnds[row] = Math.max(rowEnds[row] ?? 0, xPx + half)
    placed[index] = { xPx, row }
  }

  return placed
}
