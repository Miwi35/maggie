/** True when the key was meant for a field, a list or a dialog, not for the activity. */
export const keyBelongsElsewhere = (event: KeyboardEvent): boolean => {
  const target = event.target as HTMLElement | null
  if (!target || typeof target.closest !== 'function') return false
  if (target.closest('[role="dialog"], [role="listbox"], [role="menu"], [data-testid="chat-panel"]')) return true
  if (target.isContentEditable) return true
  const tag = target.tagName
  if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return true
  // Enter on a button or a link already presses it.
  return event.key === 'Enter' && (tag === 'BUTTON' || tag === 'A' || target.getAttribute('role') === 'button')
}
