/**
 * The screen the mobile assistant was summoned from, kept out of the bubble (MAG-30).
 *
 * The conversations are synchronised, so a question asked from the phone's assistant
 * overlay is read here too. The screen behind that overlay — the app, the page, the text
 * on it — goes to the model; it is not what was said, and the web chat must not show it.
 *
 * The agent no longer stores the block: it travels in its own field on the chat routes.
 * What is left for this to catch are the exchanges recorded before that fix, which
 * `GET /agent/messages` still returns word for word, and a message sent from a copy of
 * the mobile app that predates it.
 */

/** The first line the mobile app writes, and the marker [saidInMessage] looks for. */
export const SCREEN_CONTEXT_HEADER = "[Contexte de l'écran]"

/**
 * What was said, out of a message that may carry a screen-context block.
 *
 * Only a message *starting* with the header is one: anything else is the user's own text,
 * header quoted or not. The cut is the first blank line, which works because every line
 * of the block is single-spaced — the app collapses the whitespace of each entry it
 * collects. A block with nothing said after it is left whole: better an ugly bubble than
 * an empty one.
 */
export function saidInMessage(content: string): string {
  if (!content.startsWith(SCREEN_CONTEXT_HEADER)) return content

  const blankLine = content.indexOf('\n\n')
  if (blankLine === -1) return content

  const said = content.slice(blankLine + 2)
  return said.trim() ? said : content
}
