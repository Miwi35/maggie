const RELOAD_KEY = 'maggie:chunk-reload-at'
// A reload that did not fix the load within this window is a real outage, not a deploy.
const RETRY_WINDOW_MS = 60_000

interface Options {
  storage?: Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>
  reload?: () => void
  now?: () => number
}

// A tab opened before a deploy still points at the previous build's hashed
// chunks, which the new image no longer ships: the import fails (nginx answers
// index.html for the missing file). Reloading fetches the current index.html.
export async function loadWithReload<T>(load: () => Promise<T>, options: Options = {}): Promise<T> {
  const storage = options.storage ?? window.sessionStorage
  const reload = options.reload ?? (() => window.location.reload())
  const now = options.now ?? Date.now

  try {
    const module = await load()
    storage.removeItem(RELOAD_KEY)
    return module
  } catch (error) {
    const message = error instanceof Error ? error.message : ''
    const isStaleChunk =
      message.includes('dynamically imported module') || message.includes('Importing a module script failed')
    const lastReload = storage.getItem(RELOAD_KEY)
    const reloadedRecently = lastReload !== null && now() - Number(lastReload) < RETRY_WINDOW_MS
    if (!isStaleChunk || reloadedRecently) throw error

    storage.setItem(RELOAD_KEY, String(now()))
    reload()
    return new Promise<T>(() => {})
  }
}
