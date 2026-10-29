const TAG = String.raw`\[[^\[\]\n]{1,40}\]`
const TAG_BEFORE_PUNCTUATION = new RegExp(String.raw`\s*${TAG}(?=\s*[.,;:!?…])`, 'g')
const ANY_TAG = new RegExp(String.raw`\s*${TAG}\s*`, 'g')
const OPEN_TAG_AT_END = /\s*\[[^[\]\n]{0,40}$/

/**
 * The text without Maggie's voice tags (« [warmly] », « [softly] »…), same rule as the agent's
 * `strip_delivery_tags`; a tag still open at the end (streaming) is held back until it closes.
 * For display only: what goes to speech synthesis keeps its tags.
 */
export function stripDeliveryTags(text: string): string {
  const closed = text.replace(TAG_BEFORE_PUNCTUATION, '').replace(ANY_TAG, ' ').replace(/ {2,}/g, ' ')
  return closed.replace(OPEN_TAG_AT_END, '').trim()
}
