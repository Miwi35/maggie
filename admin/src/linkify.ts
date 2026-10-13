/** A piece of a message: plain text, or a link to open (MAG-384). */
export interface TextPart {
  text: string
  url?: string
}

const URL_PATTERN = /https?:\/\/[^\s<>]+/g
const TRAILING_PUNCTUATION = /[.,;:!?'"»”’\]}]$/

/** The address without what ends the sentence around it: « (voir https://a.fr/x). » is https://a.fr/x. */
function trimUrl(raw: string): string {
  let url = raw
  for (;;) {
    if (TRAILING_PUNCTUATION.test(url)) {
      url = url.slice(0, -1)
    } else if (url.endsWith(')') && count(url, ')') > count(url, '(')) {
      url = url.slice(0, -1)
    } else {
      return url
    }
  }
}

function count(text: string, char: string): number {
  return text.split(char).length - 1
}

/**
 * Cuts a message into text and web addresses, in order.
 *
 * Messages are plain text everywhere — stored, streamed, shown on the phone — so the sources
 * Maggie cites after a web search are the last line of her answer, « Source : domaine — lien ».
 * An address stops at the next space and keeps no final punctuation.
 */
export function splitLinks(content: string): TextPart[] {
  const parts: TextPart[] = []
  let cursor = 0

  for (const match of content.matchAll(URL_PATTERN)) {
    const url = trimUrl(match[0])
    if (!/^https?:\/\/[^/\s.]+\.[^\s]/.test(url) && !/^https?:\/\/localhost\b/.test(url)) continue

    if (match.index > cursor) parts.push({ text: content.slice(cursor, match.index) })
    parts.push({ text: url, url })
    cursor = match.index + url.length
  }

  if (cursor < content.length) parts.push({ text: content.slice(cursor) })
  return parts
}
